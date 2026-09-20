package com.panopticon.phoneapp.http.routes

import android.content.Context
import android.hardware.camera2.CameraManager
import com.panopticon.phoneapp.CameraConfigChange
import com.panopticon.phoneapp.calibration.RectNorm
import com.panopticon.phoneapp.camera.CameraCapabilities
import com.panopticon.phoneapp.camera.CameraCapabilitiesReader
import com.panopticon.phoneapp.camera.CameraCatalog
import com.panopticon.phoneapp.camera.CameraControlKeys
import com.panopticon.phoneapp.camera.CameraControlValidation
import com.panopticon.phoneapp.camera.CameraFraming
import com.panopticon.phoneapp.camera.CamerasResponse
import com.panopticon.phoneapp.camera.ActiveCameraRequest
import com.panopticon.phoneapp.camera.ActiveCameraResponse
import com.panopticon.phoneapp.camera.CameraStatePatch
import com.panopticon.phoneapp.camera.CameraStateResponse
import com.panopticon.phoneapp.camera.RecordingSizeSelection
import com.panopticon.phoneapp.camera.ViewportRect
import com.panopticon.phoneapp.http.ErrorBody
import com.panopticon.phoneapp.state.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** `POST /api/camera/state` rejection body - names the offending key so the controller can point at it. */
@Serializable
data class ControlErrorBody(val error: String, val key: String)

/**
 * Camera-selection + manual-control routes (docs/design/http-api.md "Cameras" and
 * "Camera control").
 *
 *  - `GET  /api/cameras`               - physical cameras + which is active
 *  - `POST /api/cameras/active`        - switch active camera (disruptive reconfigure)
 *  - `GET  /api/camera/capabilities`   - declared per-key ranges (any mode, optional ?cameraId=)
 *  - `GET  /api/camera/state`          - current manual-control state
 *  - `POST /api/camera/state`          - validate-then-apply a new state; 400 {error,key} on a bad key
 *
 * `activeCameraId` + the control keys live in `DeviceConfig` (persisted), so a
 * state tuned while watching the live preview also governs recording and
 * survives a restart. Both writes fire [onCameraConfigChanged] so the service -
 * which owns the pipelines - does the reconfigure.
 */
fun Route.cameraRoutes(
    androidContext: Context,
    cameraCatalog: CameraCatalog,
    appConfig: AppConfig,
    onCameraConfigChanged: (CameraConfigChange) -> Unit,
) {
    // Authenticated by the global installAuth() intercept.
    run {
        get("/api/cameras") {
            val active = appConfig.get().activeCameraId
            call.respond(CamerasResponse(cameraCatalog.list(active)))
        }

        post("/api/cameras/active") {
            val body = call.receive<ActiveCameraRequest>()
            if (!cameraCatalog.has(body.cameraId)) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("unknown cameraId '${body.cameraId}'"))
                return@post
            }
            val current = appConfig.get().activeCameraId
            if (body.cameraId != current) {
                appConfig.update { it.copy(activeCameraId = body.cameraId) }
                onCameraConfigChanged(CameraConfigChange.ACTIVE_CAMERA)
            }
            call.respond(ActiveCameraResponse(activeCameraId = body.cameraId))
        }

        get("/api/camera/capabilities") {
            val requested = call.request.queryParameters["cameraId"]
            val id = requested ?: cameraCatalog.resolveActiveId(appConfig.get().activeCameraId)
            if (id == null || !cameraCatalog.has(id)) {
                call.respond(HttpStatusCode.NotFound, ErrorBody("unknown cameraId '${requested ?: id}'"))
                return@get
            }
            call.respond(CameraCapabilitiesReader.read(androidContext, id))
        }

        get("/api/camera/state") {
            val cfg = appConfig.get()
            val id = cameraCatalog.resolveActiveId(cfg.activeCameraId) ?: ""
            val caps = if (id.isNotEmpty()) CameraCapabilitiesReader.read(androidContext, id) else null
            call.respond(
                CameraStateResponse(
                    cameraId = id,
                    rotationDegrees = cfg.rotationDegrees,
                    videoResolution = effectiveResolution(cfg.videoResolution, caps),
                    manualControlEnabled = cfg.cameraControls.manualControlEnabled,
                    keys = cfg.cameraControls.keys,
                ),
            )
        }

        post("/api/camera/state") {
            val patch = call.receive<CameraStatePatch>()
            val cfg = appConfig.get()

            val id = cameraCatalog.resolveActiveId(cfg.activeCameraId)
            if (id == null) {
                call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody("device reports no cameras"))
                return@post
            }
            if (patch.rotationDegrees != null && patch.rotationDegrees !in setOf(0, 90, 180, 270)) {
                call.respond(HttpStatusCode.BadRequest, ControlErrorBody("must be one of 0/90/180/270", "rotationDegrees"))
                return@post
            }
            val caps = CameraCapabilitiesReader.read(androidContext, id)
            if (patch.videoResolution != null &&
                caps.outputResolutions.isNotEmpty() &&
                patch.videoResolution !in caps.outputResolutions
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ControlErrorBody("must be one of ${caps.outputResolutions}", "videoResolution"),
                )
                return@post
            }

            val nextRotation = patch.rotationDegrees ?: cfg.rotationDegrees
            val nextResolution = patch.videoResolution ?: cfg.videoResolution

            var next = cfg.cameraControls.withPatch(patch)
            if (patch.keys != null) {
                next = next.copy(
                    keys = adjustIncomingRects(
                        incoming = next.keys,
                        stored = cfg.cameraControls.keys,
                        rotationDegrees = cfg.rotationDegrees,
                        androidContext = androidContext,
                        sizingCameraId = CameraCapabilitiesReader.splitTarget(id).let { (logical, physical) -> physical ?: logical },
                        videoResolution = nextResolution,
                    ),
                )
            }

            CameraControlValidation.validate(next.keys, caps)?.let { err ->
                call.respond(HttpStatusCode.BadRequest, ControlErrorBody(err.reason, err.key))
                return@post
            }
            val resolutionChanged = patch.videoResolution != null && patch.videoResolution != cfg.videoResolution
            val rotationChanged = patch.rotationDegrees != null && patch.rotationDegrees != cfg.rotationDegrees
            appConfig.update {
                it.copy(cameraControls = next, rotationDegrees = nextRotation, videoResolution = nextResolution)
            }
            // A size or rotation change needs a full pipeline rebuild (the encoder's dimensions
            // can change for either - rotation swaps them for 90/270 - like a camera switch);
            // a plain control change is just a request rebuild.
            onCameraConfigChanged(
                if (resolutionChanged || rotationChanged) CameraConfigChange.ACTIVE_CAMERA else CameraConfigChange.CONTROLS,
            )
            call.respond(
                CameraStateResponse(
                    cameraId = id,
                    rotationDegrees = nextRotation,
                    videoResolution = effectiveResolution(nextResolution, caps),
                    manualControlEnabled = next.manualControlEnabled,
                    keys = next.keys,
                ),
            )
        }
    }
}

/**
 * The controller's drag-a-box picker (AE spot / AF point - the zoom rect is temporarily disabled,
 * see [com.panopticon.phoneapp.camera.CameraControlApply]'s doc comment) draws over the live
 * preview - against whatever rotation is currently in effect - so a rect that differs from what's
 * already [stored] is a *fresh* draw and needs [ViewportRect.toSensorSpace] to land on the
 * sensor-relative rect [com.panopticon.phoneapp.camera.CameraControlApply] expects. A rect that's
 * unchanged from [stored] is just being carried forward wholesale by an unrelated control update
 * ([com.panopticon.phoneapp.camera.CameraControlSpec.withPatch] replaces `keys` in full) and is
 * left alone - it's already sensor-relative from the last time it *was* fresh.
 *
 * Both rects also need GL's fixed [com.panopticon.phoneapp.camera.CameraFraming.computeTexCrop]
 * factored into the viewport itself - it crops the displayed frame, so even the full-frame
 * viewport isn't the whole sensor (see [ViewportRect]'s doc comment) - or a rect drawn near the
 * edge of the visible preview lands on the wrong point on the sensor.
 */
private fun adjustIncomingRects(
    incoming: CameraControlKeys,
    stored: CameraControlKeys,
    rotationDegrees: Int,
    androidContext: Context,
    sizingCameraId: String,
    videoResolution: String,
): CameraControlKeys {
    // GL applies its fixed texCrop regardless of zoom (see ViewportRect's doc comment), so every
    // fresh rect needs it to land on the correct on-screen viewport.
    val cameraManager = androidContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val recordingSize = RecordingSizeSelection.recordModeSize(cameraManager, sizingCameraId, videoResolution)
    val (texCropX, texCropY) = if (recordingSize != null) {
        val sourceSize = CameraFraming.pickSourceSize(cameraManager, sizingCameraId, recordingSize)
        CameraFraming.computeTexCrop(sourceSize, recordingSize)
    } else {
        1f to 1f
    }

    fun freshSensorRect(new: RectNorm?, old: RectNorm?): RectNorm? {
        if (new == null || new == old) return new
        return ViewportRect.toSensorSpace(new, stored, rotationDegrees, texCropX, texCropY)
    }

    return incoming.copy(
        aeRegionNorm = freshSensorRect(incoming.aeRegionNorm, stored.aeRegionNorm),
        afRegionNorm = freshSensorRect(incoming.afRegionNorm, stored.afRegionNorm),
    )
}

/** The concrete record/broadcast size to report: the stored choice if it's
 * still offered, otherwise the camera's largest supported size, otherwise a
 * 720p default. */
private fun effectiveResolution(stored: String, caps: CameraCapabilities?): String {
    val list = caps?.outputResolutions ?: emptyList()
    return when {
        stored.isNotEmpty() && (list.isEmpty() || stored in list) -> stored
        list.isNotEmpty() -> list.first()
        else -> "1280x720"
    }
}
