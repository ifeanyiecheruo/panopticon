package com.panopticon.phoneapp.http.routes

import android.content.Context
import com.panopticon.phoneapp.CameraConfigChange
import com.panopticon.phoneapp.calibration.RectNorm
import com.panopticon.phoneapp.camera.CameraCapabilities
import com.panopticon.phoneapp.camera.CameraCapabilitiesReader
import com.panopticon.phoneapp.camera.CameraCatalog
import com.panopticon.phoneapp.camera.CameraControlKeys
import com.panopticon.phoneapp.camera.CameraControlSpec
import com.panopticon.phoneapp.camera.CameraControlValidation
import com.panopticon.phoneapp.camera.CamerasResponse
import com.panopticon.phoneapp.camera.ActiveCameraRequest
import com.panopticon.phoneapp.camera.ActiveCameraResponse
import com.panopticon.phoneapp.camera.CameraStatePatch
import com.panopticon.phoneapp.camera.CameraStateResponse
import com.panopticon.phoneapp.camera.ViewportRect
import com.panopticon.phoneapp.camera.ZoomCalibrationLut
import com.panopticon.phoneapp.camera.ZoomGeometry
import com.panopticon.phoneapp.calibration.CalibrationStore
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
    /** The crop the live pipeline is currently rendering with, for mapping drawn rects back to
     *  the sensor - see [resolveSelections]. Null when no preview is running. */
    liveTexCrop: () -> Pair<Float, Float>?,
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
                cameraStateResponse(
                    androidContext = androidContext,
                    id = id,
                    rotationDegrees = cfg.rotationDegrees,
                    videoResolution = effectiveResolution(cfg.videoResolution, caps),
                    spec = cfg.cameraControls,
                    liveTexCrop = liveTexCrop(),
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
                // Selections are checked before they are consumed, so a bad value is reported
                // under the field the caller actually sent.
                CameraControlValidation.validateSelections(patch.keys, caps)?.let { err ->
                    call.respond(HttpStatusCode.BadRequest, ControlErrorBody(err.reason, err.key))
                    return@post
                }
                next = next.copy(
                    keys = resolveSelections(
                        incoming = next.keys,
                        storedSpec = cfg.cameraControls,
                        rotationDegrees = cfg.rotationDegrees,
                        liveTexCrop = liveTexCrop(),
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
                cameraStateResponse(
                    androidContext = androidContext,
                    id = id,
                    rotationDegrees = nextRotation,
                    videoResolution = effectiveResolution(nextResolution, caps),
                    spec = next,
                    liveTexCrop = liveTexCrop(),
                ),
            )
        }
    }
}

/**
 * Turn the request-only, viewer-space **selections** in an incoming patch into the absolute state
 * the pipelines and the HAL consume. See docs/design/http-api.md, "Coordinate spaces, and why some
 * keys are request-only".
 *
 * The controller draws over the live preview and always sends what it drew, in the preview's own
 * `0..1` coordinates - already rotated, already cropped by whatever zoom is in effect. It never
 * converts to sensor coordinates. Everything below is that conversion, and it is the only place it
 * happens.
 *
 * Precedence per control: a fresh `*SelectNorm` wins; failing that a `zoomRatio` (the slider);
 * failing that an absolute value the caller sent outright; failing that whatever was already
 * [stored]. That last rung is what lets a patch about some *other* control leave zoom and focus
 * alone, while still allowing a deliberate clear - [CameraControlSpec.withPatch] replaces `keys`
 * wholesale, so a field the controller omits comes through as null and clears.
 *
 * The viewport AE/AF rects are untransformed through is the one that was on screen *before* this
 * request - the user drew on the old frame, not the one this patch is about to produce.
 *
 * Both rects also need GL's output-aspect trim factored into that viewport - it crops the
 * displayed frame, so even the un-zoomed viewport isn't the whole sensor - or a rect drawn near
 * the edge of the preview lands on the wrong point on the sensor. That trim is taken from the
 * running live pipeline rather than recomputed here: it depends on the camera's `SurfaceTexture`
 * transform matrix, which only the pipeline has (see [CameraFraming.correctedTexCrop]). A rect can
 * only be drawn against a running preview, so in practice it is always available; if it isn't,
 * fall back to the untrimmed viewport rather than guessing a crop that could be wrong on the wrong
 * axis.
 */
private fun resolveSelections(
    incoming: CameraControlKeys,
    storedSpec: CameraControlSpec,
    rotationDegrees: Int,
    liveTexCrop: Pair<Float, Float>?,
): CameraControlKeys {
    val (texCropX, texCropY) = liveTexCrop ?: (1f to 1f)
    // What was on screen when the user drew. With the manual master switch off the pipelines run
    // full auto and render no zoom at all, so the frame they drew on was the un-zoomed one -
    // composing onto a stored-but-inactive zoom would land the rect somewhere they never saw.
    val stored = if (storedSpec.manualControlEnabled) storedSpec.keys else CameraControlKeys()

    val currentView = stored.zoomViewNorm ?: ZoomGeometry.FULL
    val nextView = when {
        // A freshly-drawn box composes ONTO the current view, so selections compound: drawing the
        // same box twice zooms twice.
        incoming.zoomSelectNorm != null ->
            ZoomGeometry.compose(currentView, incoming.zoomSelectNorm, rotationDegrees)
        // The slider magnifies about the current view's centre, so it never throws away the
        // framing a rect selection set up.
        incoming.zoomRatio != null -> ZoomGeometry.viewForRatio(currentView, incoming.zoomRatio)
        else -> incoming.zoomViewNorm
    }

    // The viewport the user was looking at when they drew - the pre-patch zoom state.
    val viewport = ViewportRect.currentViewport(stored, texCropX, texCropY)
    fun sensorRect(selection: RectNorm?, absolute: RectNorm?): RectNorm? {
        if (selection == null) return absolute
        val preRotation = ViewportRect.inverseRotateRect(selection, rotationDegrees)
        return ViewportRect.composeWithViewport(preRotation, viewport)
    }

    return incoming.copy(
        // Selections are instructions, not state: they are consumed here and never persisted or
        // echoed back, which is what stops a read-modify-write from re-applying them.
        zoomSelectNorm = null,
        aeSelectNorm = null,
        afSelectNorm = null,
        zoomViewNorm = nextView,
        zoomRatio = nextView?.let { ZoomGeometry.ratioOf(it) },
        aeRegionNorm = sensorRect(incoming.aeSelectNorm, incoming.aeRegionNorm),
        afRegionNorm = sensorRect(incoming.afSelectNorm, incoming.afRegionNorm),
    )
}

/**
 * A state response, with the zoom split reported alongside the keys so the UI can say where the
 * magnification is coming from: [CameraStateResponse.hwZoomRatio] is what the camera was asked
 * for, [CameraStateResponse.glResidual] the upscale GL adds on top (which buys no new detail -
 * the capture resolution is deliberately not raised to feed it).
 *
 * The split is decided against this phone's own calibration sweep. An uncalibrated phone yields
 * an empty map, which simply means "ask the hardware for nothing, let GL do it all" - always
 * safe, and it still shows exactly the region the user selected, just softer.
 */
private fun cameraStateResponse(
    androidContext: Context,
    id: String,
    rotationDegrees: Int,
    videoResolution: String,
    spec: CameraControlSpec,
    liveTexCrop: Pair<Float, Float>?,
): CameraStateResponse {
    val (texCropX, texCropY) = liveTexCrop ?: (1f to 1f)
    val zoomView = spec.keys.zoomViewNorm.takeIf { spec.manualControlEnabled } ?: ZoomGeometry.FULL
    val (w, h) = parseSize(videoResolution)
    val lut = runCatching {
        ZoomCalibrationLut.build(CalibrationStore(androidContext).load(), id, w, h)
    }.getOrNull() ?: ZoomCalibrationLut.Lut.NONE
    val split = ZoomGeometry.split(
        viewSensor = ZoomGeometry.viewToSensor(zoomView, texCropX, texCropY),
        lut = lut.entries,
        texCropX = texCropX,
        texCropY = texCropY,
    )
    return CameraStateResponse(
        cameraId = id,
        rotationDegrees = rotationDegrees,
        videoResolution = videoResolution,
        manualControlEnabled = spec.manualControlEnabled,
        keys = spec.keys,
        hwZoomRatio = split.hwRatio,
        glResidual = split.glResidual,
    )
}

/** "<w>x<h>" -> the pair, falling back to 720p so a malformed value can't break a state read. */
private fun parseSize(s: String): Pair<Int, Int> {
    val parts = s.split('x')
    val w = parts.getOrNull(0)?.toIntOrNull()
    val h = parts.getOrNull(1)?.toIntOrNull()
    return if (w != null && h != null && w > 0 && h > 0) w to h else 1280 to 720
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
