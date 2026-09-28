package com.nuvio.tv.data.simkl

import android.content.Context
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Live TV fork: support for Simkl "Auth V2" client IDs.
 *
 * Nuvio signs in to Simkl with the old PIN flow (GET /oauth/pin). Client IDs registered with
 * Simkl now are V2 apps, which reject that with "use POST /oauth2/device instead". This
 * interceptor sits on Nuvio's Simkl HTTP client and, only when Simkl says the client is a V2
 * app, translates:
 *   - GET /oauth/pin          -> POST /oauth2/device   (answer rewritten to the old PIN shape)
 *   - GET /oauth/pin/{code}   -> POST /oauth2/token     (device_code grant)
 * V2 access tokens expire after 7 days, so it also keeps the refresh token and swaps a fresh
 * access token into every request, refreshing a day early or after a 401. The rest of Nuvio's
 * Simkl code is untouched, and V1 client IDs keep working exactly as before.
 */
class SimklAuthV2Interceptor(
    context: Context,
    private val clientId: String
) : Interceptor {

    private val prefs = context.applicationContext.getSharedPreferences("simkl_auth_v2", Context.MODE_PRIVATE)
    private val deviceCodes = ConcurrentHashMap<String, String>()
    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        if (request.method == "GET" && path == "/oauth/pin") return startPin(chain, request)
        if (request.method == "GET" && path.startsWith("/oauth/pin/")) {
            val userCode = path.removePrefix("/oauth/pin/")
            val deviceCode = deviceCodes[userCode] ?: prefs.getString(KEY_DEVICE_PREFIX + userCode, null)
            if (deviceCode != null) return pollDevice(chain, request, userCode, deviceCode)
            return chain.proceed(request)
        }

        // Authenticated calls: use the current (refreshed) V2 access token.
        val original = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: return chain.proceed(request)
        val record = loadRecord(original) ?: return chain.proceed(request)
        val current = ensureFresh(chain, original, record)
        val response = chain.proceed(withToken(request, current.access))
        if (response.code != 401) return response
        // Token rejected: refresh once and retry.
        response.close()
        val refreshed = refresh(chain, original, current) ?: return chain.proceed(withToken(request, current.access))
        return chain.proceed(withToken(request, refreshed.access))
    }

    // ------------------------------------------------------------ sign-in

    private fun startPin(chain: Interceptor.Chain, request: Request): Response {
        if (!prefs.getBoolean(KEY_IS_V2, false)) {
            val v1 = chain.proceed(request)
            val body = v1.peekBody(64 * 1024).string()
            // Simkl's JSON escapes the slashes ("\/oauth2\/device"), so match on the error code.
            val isV2App = v1.code in 400..403 &&
                (body.contains("unauthorized_client") || body.contains("oauth2"))
            if (!isV2App) return v1
            v1.close()
            prefs.edit().putBoolean(KEY_IS_V2, true).apply()
        }
        val response = chain.proceed(
            post(request, "/oauth2/device", FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", SCOPE)
                .build())
        )
        val json = response.use { runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull() }
            ?: return synthetic(request, JSONObject().put("result", "FAIL"))
        val userCode = json.optString("user_code")
        val deviceCode = json.optString("device_code")
        if (userCode.isBlank() || deviceCode.isBlank()) {
            return synthetic(request, JSONObject().put("result", "FAIL").put("message", json.optString("message")))
        }
        // Nuvio only accepts 4-12 letters/digits as a PIN, so drop any dash (e.g. "ABCD-1234").
        // The sign-in link below already carries the full code.
        val pin = userCode.filter { it.isLetterOrDigit() }.take(12)
        deviceCodes[pin] = deviceCode
        prefs.edit().putString(KEY_DEVICE_PREFIX + pin, deviceCode).apply()
        val verification = json.optString("verification_uri_complete").ifBlank { json.optString("verification_uri") }
        // The old PIN response shape Nuvio expects (no device_code, or it treats the PIN as invalid).
        return synthetic(
            request,
            JSONObject()
                .put("result", "OK")
                .put("user_code", pin)
                .put("verification_url", verification.ifBlank { "https://simkl.com/pin" })
                .put("expires_in", json.optLong("expires_in", 900L))
                .put("interval", json.optInt("interval", 5))
        )
    }

    private fun pollDevice(chain: Interceptor.Chain, request: Request, userCode: String, deviceCode: String): Response {
        val response = chain.proceed(
            post(request, "/oauth2/token", FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", deviceCode)
                .add("client_id", clientId)
                .build())
        )
        val json = response.use { runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull() }
            ?: return synthetic(request, JSONObject().put("result", "KO"))
        val access = json.optString("access_token")
        if (access.isNotBlank()) {
            saveRecord(access, TokenRecord(access, json.optString("refresh_token"), expiresAt(json)))
            forgetDevice(userCode)
            return synthetic(request, JSONObject().put("result", "OK").put("access_token", access))
        }
        return when (json.optString("error")) {
            "authorization_pending", "slow_down", "" -> synthetic(request, JSONObject().put("result", "KO"))
            else -> {
                // Denied or expired: end the sign-in with an error.
                forgetDevice(userCode)
                synthetic(request, JSONObject().put("result", "FAIL").put("message", json.optString("error")))
            }
        }
    }

    private fun forgetDevice(userCode: String) {
        deviceCodes.remove(userCode)
        prefs.edit().remove(KEY_DEVICE_PREFIX + userCode).apply()
    }

    // ------------------------------------------------------------ tokens

    private data class TokenRecord(val access: String, val refresh: String, val expiresAtMs: Long)

    private fun ensureFresh(chain: Interceptor.Chain, original: String, record: TokenRecord): TokenRecord {
        if (System.currentTimeMillis() < record.expiresAtMs - REFRESH_EARLY_MS) return record
        return refresh(chain, original, record) ?: record
    }

    private fun refresh(chain: Interceptor.Chain, original: String, seen: TokenRecord): TokenRecord? = synchronized(refreshLock) {
        // Another request may have refreshed while we waited.
        val latest = loadRecord(original) ?: return@synchronized null
        if (latest.access != seen.access) return@synchronized latest
        if (latest.refresh.isBlank()) return@synchronized null
        val response = runCatching {
            chain.proceed(
                Request.Builder()
                    .url("https://api.simkl.com/oauth2/token")
                    .post(FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", latest.refresh)
                        .add("client_id", clientId)
                        .build())
                    .header("Accept", "application/json")
                    .build()
            )
        }.getOrNull() ?: return@synchronized null
        val json = response.use { runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull() }
            ?: return@synchronized null
        val access = json.optString("access_token")
        if (access.isBlank()) return@synchronized null
        val updated = TokenRecord(access, json.optString("refresh_token").ifBlank { latest.refresh }, expiresAt(json))
        saveRecord(original, updated)
        updated
    }

    private fun expiresAt(json: JSONObject): Long =
        System.currentTimeMillis() + json.optLong("expires_in", DEFAULT_EXPIRES_S) * 1000L

    private fun loadRecord(original: String): TokenRecord? {
        val raw = prefs.getString(KEY_TOKEN_PREFIX + hash(original), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            TokenRecord(o.getString("access"), o.optString("refresh"), o.optLong("expiresAt"))
        }.getOrNull()
    }

    private fun saveRecord(original: String, record: TokenRecord) {
        prefs.edit().putString(
            KEY_TOKEN_PREFIX + hash(original),
            JSONObject().put("access", record.access).put("refresh", record.refresh).put("expiresAt", record.expiresAtMs).toString()
        ).apply()
    }

    // ------------------------------------------------------------ helpers

    private fun post(original: Request, path: String, form: FormBody): Request =
        Request.Builder()
            .url(original.url.newBuilder().encodedPath(path).query(null).build())
            .post(form)
            .header("Accept", "application/json")
            .apply { original.header("User-Agent")?.let { header("User-Agent", it) } }
            .build()

    private fun withToken(request: Request, token: String): Request =
        request.newBuilder().header("Authorization", "Bearer $token").build()

    private fun synthetic(request: Request, json: JSONObject): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toString().toResponseBody("application/json".toMediaType()))
            .build()

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        const val SCOPE = "media:read media:write"
        const val KEY_IS_V2 = "client_is_v2"
        const val KEY_DEVICE_PREFIX = "device_"
        const val KEY_TOKEN_PREFIX = "token_"
        const val DEFAULT_EXPIRES_S = 7L * 24 * 60 * 60
        const val REFRESH_EARLY_MS = 24L * 60 * 60 * 1000
    }
}
