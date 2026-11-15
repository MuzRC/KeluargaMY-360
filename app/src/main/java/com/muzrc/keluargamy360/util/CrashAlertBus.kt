package com.muzrc.keluargamy360.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CrashAlertBus {
    private val _crashDetected = MutableStateFlow(false)
    val crashDetected: StateFlow<Boolean> = _crashDetected.asStateFlow()

    fun triggerCrashCountdown() {
        _crashDetected.value = true
    }

    fun dismissCrashCountdown() {
        _crashDetected.value = false
    }
}
