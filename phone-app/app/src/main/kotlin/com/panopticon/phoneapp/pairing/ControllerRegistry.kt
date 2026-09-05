package com.panopticon.phoneapp.pairing

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom

@Serializable
data class PairedController(
    val controllerId: String,
    val token: String,
    val name: String,
    val kind: String,
    val publicKey: String,
    val pairedAtMs: Long,
)

/**
 * Per-controller bearer-token registry, per phone-http-api.md's auth model: each paired
 * controller gets its own token (not one shared secret), so it can be revoked individually.
 * `list()`/`revoke(controllerId)` are deliberately NOT exposed as HTTP routes (per the doc,
 * they're a local/owner concern) - only `register()` (called from POST /api/pair) and
 * `revokeByToken()` (called from DELETE /api/pair, self-unpair) are used by the HTTP layer.
 *
 * Persisted as a single JSON blob in SharedPreferences - fine at the scale of "a handful of
 * paired controllers."
 */
class ControllerRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("panopticon_controllers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    @Synchronized
    private fun loadAll(): MutableMap<String, PairedController> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        return try {
            json.decodeFromString<Map<String, PairedController>>(raw).toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    private fun saveAll(map: Map<String, PairedController>) {
        prefs.edit().putString(KEY, json.encodeToString(map)).apply()
    }

    @Synchronized
    fun register(name: String, kind: String, publicKey: String): PairedController {
        val all = loadAll()
        val controllerId = "ctl_" + randomHex(4)
        val token = randomHex(32)
        val controller = PairedController(
            controllerId = controllerId,
            token = token,
            name = name,
            kind = kind,
            publicKey = publicKey,
            pairedAtMs = System.currentTimeMillis(),
        )
        all[controllerId] = controller
        saveAll(all)
        return controller
    }

    @Synchronized
    fun findByToken(token: String): PairedController? =
        loadAll().values.firstOrNull { it.token == token }

    @Synchronized
    fun revokeByToken(token: String): Boolean {
        val all = loadAll()
        val match = all.values.firstOrNull { it.token == token } ?: return false
        all.remove(match.controllerId)
        saveAll(all)
        return true
    }

    /** Local/owner-only - not an HTTP route. Used by a future Controllers screen. */
    @Synchronized
    fun list(): List<PairedController> = loadAll().values.toList()

    /** Local/owner-only - not an HTTP route. */
    @Synchronized
    fun revoke(controllerId: String): Boolean {
        val all = loadAll()
        val removed = all.remove(controllerId) != null
        if (removed) saveAll(all)
        return removed
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY = "controllers_json"
    }
}
