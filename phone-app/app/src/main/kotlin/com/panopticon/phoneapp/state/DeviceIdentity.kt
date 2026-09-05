package com.panopticon.phoneapp.state

import android.content.Context
import java.security.SecureRandom

/** A stable per-install phone id, generated once and persisted - used in pairing responses. */
object DeviceIdentity {
    private const val PREFS = "panopticon_identity"
    private const val KEY = "phone_id"

    fun getOrCreatePhoneId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val random = SecureRandom()
        val buf = ByteArray(4)
        random.nextBytes(buf)
        val id = "ph_" + buf.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
