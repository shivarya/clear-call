package com.clearcall.net

import com.clearcall.BuildConfig
import com.clearcall.core.AuthState
import com.clearcall.core.Prefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val statusCode: Int, message: String) : Exception(message)

/** Thin OkHttp client for the ClearCall PHP API — mirrors the {success,data,message} envelope. */
class ApiClient(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // PHP's built-in dev server (`php -S`, used locally) is single-threaded and closes
        // the socket after every request — it doesn't support keep-alive. Without this,
        // OkHttp's connection pool reuses what it thinks is a live connection and the next
        // request fails with "unexpected end of stream". Harmless against the real
        // Apache/LiteSpeed production host, which handles keep-alive normally.
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Connection", "close").build())
        }
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * One retry on a transient I/O failure — e.g. a pooled connection the server already
     * closed (a well-known `php -S` quirk locally) or an ordinary mobile-network blip in
     * production. A real API error (a parsed response with success=false) never retries.
     */
    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        lateinit var lastError: Exception
        repeat(2) { attempt ->
            try {
                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string().orEmpty()
                val parsed = runCatching { json.parseToJsonElement(bodyStr) as? JsonObject }.getOrNull()
                    ?: throw ApiException(response.code, "Invalid response from server (${response.code})")

                val success = (parsed["success"] as? JsonPrimitive)?.boolean ?: false
                if (!success) {
                    if (response.code == 401) AuthState.setSignedIn(false)
                    val errMsg = (parsed["error"] as? JsonPrimitive)?.contentOrNull
                        ?: "Request failed (${response.code})"
                    throw ApiException(response.code, errMsg)
                }
                return@withContext parsed
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) delay(150)
            }
        }
        throw ApiException(0, "Network error: ${lastError.message}")
    }

    private fun authed(path: String): Request.Builder {
        val builder = Request.Builder().url(BuildConfig.API_BASE_URL + path)
        prefs.authToken?.let { builder.addHeader("Authorization", "Bearer $it") }
        return builder
    }

    suspend fun googleLogin(idToken: String): AuthResult {
        val body = buildJsonObject { put("idToken", idToken) }
        val req = Request.Builder().url(BuildConfig.API_BASE_URL + "/auth/google")
            .post(body.toString().toRequestBody(jsonMediaType)).build()
        return json.decodeFromJsonElement(AuthResult.serializer(), execute(req).getValue("data"))
    }

    /** Local-only test login (ALLOW_DEV_LOGIN=true on the server). Never works against prod. */
    suspend fun devLogin(as_: Int): AuthResult {
        val body = buildJsonObject { put("as", as_) }
        val req = Request.Builder().url(BuildConfig.API_BASE_URL + "/auth/login")
            .post(body.toString().toRequestBody(jsonMediaType)).build()
        return json.decodeFromJsonElement(AuthResult.serializer(), execute(req).getValue("data"))
    }

    suspend fun getMe(): UserSummary {
        val req = authed("/auth/me").get().build()
        return json.decodeFromJsonElement(UserSummary.serializer(), execute(req).getValue("data"))
    }

    suspend fun registerDevice(fcmToken: String, deviceLabel: String) {
        val body = buildJsonObject {
            put("pushToken", fcmToken)
            put("platform", "android")
            put("deviceLabel", deviceLabel)
        }
        val req = authed("/devices/register").post(body.toString().toRequestBody(jsonMediaType)).build()
        execute(req)
    }

    suspend fun addContact(userCode: String): UserSummary {
        val body = buildJsonObject { put("userCode", userCode) }
        val req = authed("/contacts").post(body.toString().toRequestBody(jsonMediaType)).build()
        return json.decodeFromJsonElement(UserSummary.serializer(), execute(req).getValue("data"))
    }

    suspend fun listContacts(): List<UserSummary> {
        val req = authed("/contacts").get().build()
        return json.decodeFromJsonElement(ListSerializer(UserSummary.serializer()), execute(req).getValue("data"))
    }

    suspend fun removeContact(userId: Int) {
        execute(authed("/contacts/$userId").delete().build())
    }

    suspend fun createCall(calleeUserId: Int): CreateCallResult {
        val body = buildJsonObject { put("calleeUserId", calleeUserId) }
        val req = authed("/calls").post(body.toString().toRequestBody(jsonMediaType)).build()
        return json.decodeFromJsonElement(CreateCallResult.serializer(), execute(req).getValue("data"))
    }

    suspend fun answerCall(callId: Int): AnswerCallResult {
        val req = authed("/calls/$callId/answer").post(EMPTY_BODY).build()
        return json.decodeFromJsonElement(AnswerCallResult.serializer(), execute(req).getValue("data"))
    }

    suspend fun declineCall(callId: Int) {
        execute(authed("/calls/$callId/decline").post(EMPTY_BODY).build())
    }

    suspend fun cancelCall(callId: Int, reason: String) {
        val body = buildJsonObject { put("reason", reason) }
        execute(authed("/calls/$callId/cancel").post(body.toString().toRequestBody(jsonMediaType)).build())
    }

    suspend fun endCall(callId: Int) {
        execute(authed("/calls/$callId/end").post(EMPTY_BODY).build())
    }

    companion object {
        private val EMPTY_BODY = "".toRequestBody("application/json; charset=utf-8".toMediaType())
    }
}
