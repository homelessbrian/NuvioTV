package com.nuvio.tv.livetv.sync

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the settings screen shows. */
data class DriveSyncState(
    val available: Boolean = false,
    val connected: Boolean = false,
    val email: String? = null,
    val autoSync: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val lastSyncMs: Long = 0L,
    /** Set while signing in: show this code and address to the user. */
    val signIn: SignInPrompt? = null
)

data class SignInPrompt(val userCode: String, val url: String, val expiresAtMs: Long)

/**
 * Syncs the Live TV setup through the user's own Google Drive.
 *
 * The data goes in Drive's hidden app data folder (scope drive.appdata): it lives in the user's
 * Drive, only this app can read it, and nothing is stored anywhere else. Sign-in uses Google's
 * "TVs and limited input devices" flow: the TV shows a code, the user approves on their phone.
 * Sync is "newest wins": changes are backed up shortly after they happen, and a newer copy from
 * another TV is applied when the app starts or when you tap Restore.
 */
@Singleton
class LiveTvDriveSync @Inject constructor(
    @ApplicationContext context: Context,
    private val prefs: LiveTvPreferences,
    private val repository: LiveTvRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = context.applicationContext.getSharedPreferences("livetv_drive_sync", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val clientId = BuildConfig.GOOGLE_DRIVE_CLIENT_ID
    private val clientSecret = BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<DriveSyncState> = _state.asStateFlow()

    private var started = false
    private var signInJob: Job? = null
    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L
    /** True while a restored setup is being written, so it isn't backed up straight back. */
    @Volatile private var applying = false

    private val deviceId: String
        get() = store.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            store.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private fun loadState() = DriveSyncState(
        available = clientId.isNotBlank() && clientSecret.isNotBlank(),
        connected = store.getString(KEY_REFRESH, null) != null,
        email = store.getString(KEY_EMAIL, null),
        autoSync = store.getBoolean(KEY_AUTO, true),
        lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L)
    )

    /** Starts automatic sync: check Drive for a newer setup, then back up local changes. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            if (_state.value.connected && _state.value.autoSync) pull(force = false)
        }
        scope.launch {
            prefs.changes.drop(1).debounce(8_000).collect {
                val s = _state.value
                if (s.connected && s.autoSync && !applying) push(force = false)
            }
        }
    }

    // ------------------------------------------------------------------ sign-in

    fun beginSignIn() {
        if (!_state.value.available) return
        signInJob?.cancel()
        signInJob = scope.launch {
            _state.value = _state.value.copy(busy = true, message = null, signIn = null)
            val codes = runCatching {
                postForm(
                    "https://oauth2.googleapis.com/device/code",
                    mapOf("client_id" to clientId, "scope" to SCOPES)
                )
            }.getOrElse {
                _state.value = _state.value.copy(busy = false, message = "Couldn't reach Google. Try again.")
                return@launch
            }
            val deviceCode = codes.optString("device_code")
            val userCode = codes.optString("user_code")
            if (deviceCode.isBlank() || userCode.isBlank()) {
                _state.value = _state.value.copy(busy = false, message = googleError(codes))
                return@launch
            }
            val url = codes.optString("verification_url").ifBlank { "https://www.google.com/device" }
            val expiresAt = System.currentTimeMillis() + codes.optLong("expires_in", 1800) * 1000
            var interval = codes.optLong("interval", 5).coerceAtLeast(5)
            _state.value = _state.value.copy(busy = false, signIn = SignInPrompt(userCode, url, expiresAt))

            while (System.currentTimeMillis() < expiresAt) {
                delay(interval * 1000)
                val token = runCatching {
                    postForm(
                        "https://oauth2.googleapis.com/token",
                        mapOf(
                            "client_id" to clientId,
                            "client_secret" to clientSecret,
                            "device_code" to deviceCode,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                        )
                    )
                }.getOrNull() ?: continue
                when (token.optString("error")) {
                    "" -> {
                        onSignedIn(token)
                        return@launch
                    }
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    "access_denied" -> {
                        _state.value = _state.value.copy(signIn = null, message = "Sign-in was declined.")
                        return@launch
                    }
                    else -> {
                        _state.value = _state.value.copy(signIn = null, message = googleError(token))
                        return@launch
                    }
                }
            }
            _state.value = _state.value.copy(signIn = null, message = "The code expired. Try again.")
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _state.value = _state.value.copy(signIn = null, busy = false)
    }

    private suspend fun onSignedIn(token: JSONObject) {
        val refresh = token.optString("refresh_token")
        if (refresh.isBlank()) {
            _state.value = _state.value.copy(signIn = null, message = "Google didn't return a sign-in. Try again.")
            return
        }
        accessToken = token.optString("access_token")
        accessTokenExpiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000
        val email = emailFromIdToken(token.optString("id_token"))
        store.edit().putString(KEY_REFRESH, refresh).putString(KEY_EMAIL, email).apply()
        _state.value = _state.value.copy(connected = true, email = email, signIn = null, message = null)
        // First connection: use the Drive copy if there is one, otherwise back this TV up.
        if (!pull(force = true, onlyIfExists = true)) push(force = true)
    }

    fun disconnect() {
        val refresh = store.getString(KEY_REFRESH, null)
        store.edit().remove(KEY_REFRESH).remove(KEY_EMAIL).remove(KEY_FILE_ID).remove(KEY_LAST_HASH).apply()
        accessToken = null
        _state.value = _state.value.copy(connected = false, email = null, message = "Disconnected. Your backup stays in your Google Drive.")
        if (refresh != null) scope.launch {
            runCatching { postForm("https://oauth2.googleapis.com/revoke", mapOf("token" to refresh)) }
        }
    }

    fun setAutoSync(enabled: Boolean) {
        store.edit().putBoolean(KEY_AUTO, enabled).apply()
        _state.value = _state.value.copy(autoSync = enabled)
    }

    fun backUpNow() { scope.launch { push(force = true) } }
    fun restoreNow() { scope.launch { pull(force = true) } }

    // ------------------------------------------------------------------ backup / restore

    /** Uploads this TV's setup. Skips the upload when nothing changed since the last one. */
    private suspend fun push(force: Boolean) = mutex.withLock {
        val token = token() ?: return@withLock
        _state.value = _state.value.copy(busy = true, message = if (force) "Backing up…" else null)
        val result = runCatching {
            val data = prefs.exportForSync()
            val hash = sha(data.toString())
            if (!force && hash == store.getString(KEY_LAST_HASH, null)) return@runCatching false
            val now = System.currentTimeMillis()
            val body = JSONObject()
                .put("format", 1)
                .put("savedAt", now)
                .put("deviceId", deviceId)
                .put("device", Build.MODEL ?: "TV")
                .put("data", data)
                .toString()
            val fileId = fileId(token)
            if (fileId == null) {
                val meta = JSONObject().put("name", FILE_NAME).put("parents", org.json.JSONArray().put("appDataFolder"))
                val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
                    .addPart(meta.toString().toRequestBody(JSON))
                    .addPart(body.toRequestBody(JSON))
                    .build()
                val created = execute(
                    Request.Builder()
                        .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                        .header("Authorization", "Bearer $token")
                        .post(multipart)
                        .build()
                )
                store.edit().putString(KEY_FILE_ID, JSONObject(created).optString("id")).apply()
            } else {
                execute(
                    Request.Builder()
                        .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                        .header("Authorization", "Bearer $token")
                        .patch(body.toRequestBody(JSON))
                        .build()
                )
            }
            store.edit().putString(KEY_LAST_HASH, hash).putLong(KEY_LAST_SYNC, now).putLong(KEY_APPLIED_AT, now).apply()
            true
        }
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
            message = when {
                result.isFailure -> "Backup failed: ${result.exceptionOrNull()?.message ?: "network error"}"
                force -> "Backed up to Google Drive."
                else -> _state.value.message
            }
        )
    }

    /**
     * Applies the Drive copy if it's newer than what this TV has (or always, when [force]).
     * Returns true if a copy was found and applied.
     */
    private suspend fun pull(force: Boolean, onlyIfExists: Boolean = false): Boolean = mutex.withLock {
        val token = token() ?: return@withLock false
        _state.value = _state.value.copy(busy = true, message = if (force && !onlyIfExists) "Restoring…" else null)
        val result = runCatching {
            val fileId = fileId(token) ?: return@runCatching null
            val text = execute(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            )
            val json = JSONObject(text)
            val savedAt = json.optLong("savedAt")
            val fromThisTv = json.optString("deviceId") == deviceId
            if (!force && (fromThisTv || savedAt <= store.getLong(KEY_APPLIED_AT, 0L))) return@runCatching false
            val data = json.optJSONObject("data") ?: return@runCatching false
            applying = true
            try {
                prefs.importFromSync(data)
                // Download any playlists or guides this TV doesn't have yet, and rebuild the guide.
                repository.reloadAfterSync()
                delay(500)
            } finally {
                applying = false
            }
            store.edit()
                .putLong(KEY_APPLIED_AT, savedAt)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .putString(KEY_LAST_HASH, sha(prefs.exportForSync().toString()))
                .apply()
            true
        }
        val applied = result.getOrNull() == true
        _state.value = _state.value.copy(
            busy = false,
            lastSyncMs = store.getLong(KEY_LAST_SYNC, 0L),
            message = when {
                result.isFailure -> "Restore failed: ${result.exceptionOrNull()?.message ?: "network error"}"
                result.getOrNull() == null && force && !onlyIfExists -> "No backup found in Google Drive yet."
                applied && force -> "Restored from Google Drive."
                applied -> "Updated from your other TV."
                else -> _state.value.message
            }
        )
        applied
    }

    // ------------------------------------------------------------------ Google plumbing

    /** A valid access token, refreshed when needed. Null (and disconnected) if sign-in was revoked. */
    private suspend fun token(): String? {
        val refresh = store.getString(KEY_REFRESH, null) ?: return null
        accessToken?.let { if (System.currentTimeMillis() < accessTokenExpiresAt - 60_000) return it }
        val res = runCatching {
            postForm(
                "https://oauth2.googleapis.com/token",
                mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "refresh_token" to refresh,
                    "grant_type" to "refresh_token"
                )
            )
        }.getOrElse {
            _state.value = _state.value.copy(message = "Couldn't reach Google Drive.")
            return null
        }
        if (res.optString("error") == "invalid_grant") {
            store.edit().remove(KEY_REFRESH).apply()
            _state.value = _state.value.copy(connected = false, message = "Google Drive access was removed. Connect again to keep syncing.")
            return null
        }
        val access = res.optString("access_token").ifBlank { return null }
        accessToken = access
        accessTokenExpiresAt = System.currentTimeMillis() + res.optLong("expires_in", 3600) * 1000
        return access
    }

    /** The backup file's id in the app data folder, or null if there isn't one yet. */
    private suspend fun fileId(token: String): String? {
        store.getString(KEY_FILE_ID, null)?.let { return it }
        val text = execute(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name%3D%27$FILE_NAME%27&fields=files(id)")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        )
        val files = JSONObject(text).optJSONArray("files")
        val id = files?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
        store.edit().putString(KEY_FILE_ID, id).apply()
        return id
    }

    private suspend fun postForm(url: String, fields: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "http_${r.code}") }
        }
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 404) store.edit().remove(KEY_FILE_ID).apply()
            if (!r.isSuccessful) error("HTTP ${r.code}")
            text
        }
    }

    private fun googleError(json: JSONObject): String {
        val e = json.optString("error")
        return when (e) {
            "invalid_client", "unauthorized_client" -> "This build's Google sign-in isn't set up correctly."
            "invalid_scope" -> "Google refused the Drive permission for this app."
            else -> "Google sign-in failed ($e)."
        }
    }

    private fun emailFromIdToken(idToken: String): String? = runCatching {
        val payload = idToken.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        JSONObject(json).optString("email").ifBlank { null }
    }.onFailure { Log.w(TAG, "No email in sign-in") }.getOrNull()

    private fun sha(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "LiveTvDriveSync"
        const val SCOPES = "openid email https://www.googleapis.com/auth/drive.appdata"
        const val FILE_NAME = "nuvio-livetv-sync.json"
        val JSON = "application/json; charset=UTF-8".toMediaType()
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EMAIL = "email"
        const val KEY_AUTO = "auto_sync"
        const val KEY_FILE_ID = "file_id"
        const val KEY_LAST_HASH = "last_hash"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_APPLIED_AT = "applied_at"
        const val KEY_DEVICE_ID = "device_id"
    }
}
