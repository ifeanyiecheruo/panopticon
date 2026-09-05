package com.panopticon.phoneapp.pairing

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class Invite(
    val inviteId: String,
    val code: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    var redeemed: Boolean = false,
)

/**
 * Local library functions only, per phone-http-api.md - "Invite generation ... are NOT HTTP
 * routes." Called from the on-device Connect screen; redeemed by POST /api/pair.
 *
 * In-memory only (not persisted across process death) - acceptable for this slice since an
 * invite is meant to be short-lived and re-generating one after a process restart is cheap.
 */
class InviteManager {
    private val invites = ConcurrentHashMap<String, Invite>()
    private val random = SecureRandom()

    fun createInvite(): Invite {
        val inviteId = "inv_" + randomHex(4)
        val code = randomCode()
        val now = System.currentTimeMillis()
        val invite = Invite(
            inviteId = inviteId,
            code = code,
            createdAtMs = now,
            expiresAtMs = now + EXPIRY_MS,
        )
        invites[code] = invite
        return invite
    }

    fun listPendingInvites(): List<Invite> =
        invites.values.filter { !it.redeemed && it.expiresAtMs > System.currentTimeMillis() }

    fun revokeInvite(inviteId: String) {
        invites.values.removeIf { it.inviteId == inviteId }
    }

    /** Returns the invite if `code` is valid and unredeemed, marking it redeemed. Null if unknown. */
    fun redeem(code: String): RedeemResult {
        val invite = invites[code] ?: return RedeemResult.NotFound
        if (invite.redeemed) return RedeemResult.Expired
        if (invite.expiresAtMs < System.currentTimeMillis()) return RedeemResult.Expired
        invite.redeemed = true
        return RedeemResult.Ok(invite)
    }

    sealed class RedeemResult {
        object NotFound : RedeemResult()
        object Expired : RedeemResult()
        data class Ok(val invite: Invite) : RedeemResult()
    }

    private fun randomCode(): String {
        // Short, human-typeable code shown on the Connect screen (e.g. "7F3K-9QRT").
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // no 0/O/1/I ambiguity
        val part = { (0 until 4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
        return "${part()}-${part()}"
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val EXPIRY_MS = 10 * 60 * 1000L // 10 minutes
    }
}
