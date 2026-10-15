package com.kirshen.traxxx.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live snapshot of the active recording, published by [RecordingService] and read by the UI. */
object RecordingState {

    data class Snapshot(
        val clientUuid: String,
        val typeName: String,
        val startedAt: Long,
        val pointCount: Int = 0,
        val lastAccuracyM: Double? = null,
        val lastFixAt: Long? = null,
    )

    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state.asStateFlow()

    internal fun set(s: Snapshot?) { _state.value = s }
    internal fun update(block: (Snapshot) -> Snapshot) {
        _state.value = _state.value?.let(block)
    }
}
