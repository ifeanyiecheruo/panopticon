package com.panopticon.phoneapp.http.routes

import android.content.Context
import com.panopticon.phoneapp.CameraConfigChange
import com.panopticon.phoneapp.camera.CameraCapabilitiesReader
import com.panopticon.phoneapp.camera.CameraCatalog
import com.panopticon.phoneapp.camera.CameraControlValidation
import com.panopticon.phoneapp.camera.CamerasResponse
import com.panopticon.phoneapp.camera.ActiveCameraRequest
import com.panopticon.phoneapp.camera.ActiveCameraResponse
import com.panopticon.phoneapp.camera.CameraStatePatch
import com.panopticon.phoneapp.camera.CameraStateResponse
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
 * Camera-selection + manual-control routes (phone-http-api.md "Cameras" and
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
            call.respond(
                CameraStateResponse(
                    cameraId = id,
                    rotationDegrees = cfg.rotationDegrees,
                    manualControlEnabled = cfg.cameraControls.manualControlEnabled,
                    keys = cfg.cameraControls.keys,
                ),
            )
        }

        post("/api/camera/state") {
            val patch = call.receive<CameraStatePatch>()
            val cfg = appConfig.get()
            val next = cfg.cameraControls.withPatch(patch)

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
            CameraControlValidation.validate(next.keys, caps)?.let { err ->
                call.respond(HttpStatusCode.BadRequest, ControlErrorBody(err.reason, err.key))
                return@post
            }

            val nextRotation = patch.rotationDegrees ?: cfg.rotationDegrees
            appConfig.update { it.copy(cameraControls = next, rotationDegrees = nextRotation) }
            onCameraConfigChanged(CameraConfigChange.CONTROLS)
            call.respond(
                CameraStateResponse(
                    cameraId = id,
                    rotationDegrees = nextRotation,
                    manualControlEnabled = next.manualControlEnabled,
                    keys = next.keys,
                ),
            )
        }
    }
}
