package com.clearcall.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide sign-in flag so a 401 from [com.clearcall.net.ApiClient] or an explicit
 * sign-out can gate navigation without threading a callback through every screen.
 */
object AuthState {
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun setSignedIn(value: Boolean) {
        _signedIn.value = value
    }
}
