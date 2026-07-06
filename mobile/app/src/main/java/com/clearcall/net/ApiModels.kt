package com.clearcall.net

import kotlinx.serialization.Serializable

@Serializable
data class UserSummary(
    val id: Int,
    val name: String,
    val email: String,
    val userCode: String,
    val avatarUrl: String? = null,
)

@Serializable
data class AuthResult(
    val token: String,
    val user: UserSummary,
)

@Serializable
data class CreateCallResult(
    val callId: Int,
    val roomName: String,
    val livekitUrl: String,
    val token: String,
    val ringTimeoutSeconds: Int,
    val pushSkipped: Boolean = false,
)

@Serializable
data class AnswerCallResult(
    val callId: Int,
    val roomName: String,
    val livekitUrl: String,
    val token: String,
)
