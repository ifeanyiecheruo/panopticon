package com.panopticon.phoneapp.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppMode { RECORD, LIVE }

enum class RecordingStatus { IDLE, RECORDING, UNAVAILABLE }

/**
 * In-memory, process-wide runtime state - mode, camera health, live "viewer" stub count.
 * Read by both the HTTP server (/api/status, /api/mode) and the Compose UI (Home screen's
 * recording pill), written by the camera pipeline and the mode route. A StateFlow rather than
 * plain vars so Compose screens can collect it directly.
 */
class AppState {
    private val _mode = MutableStateFlow(AppMode.RECORD)
    val mode: StateFlow<AppMode> = _mode

    private val _recordingStatus = MutableStateFlow(RecordingStatus.IDLE)
    val recordingStatus: StateFlow<RecordingStatus> = _recordingStatus

    private val _cameraHealthy = MutableStateFlow(true)
    val cameraHealthy: StateFlow<Boolean> = _cameraHealthy

    // LIVE mode is a stub for this vertical slice - no real HLS pipeline, just a flag flip so
    // POST /api/mode round-trips correctly for controller integration testing.
    private val _liveViewers = MutableStateFlow(0)
    val liveViewers: StateFlow<Int> = _liveViewers

    fun setMode(mode: AppMode) {
        _mode.value = mode
    }

    fun setRecordingStatus(status: RecordingStatus) {
        _recordingStatus.value = status
    }

    fun setCameraHealthy(healthy: Boolean) {
        _cameraHealthy.value = healthy
    }
}
