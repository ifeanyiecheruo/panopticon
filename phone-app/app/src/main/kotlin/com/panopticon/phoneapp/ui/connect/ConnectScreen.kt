package com.panopticon.phoneapp.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.panopticon.phoneapp.PanopticonApplication
import com.panopticon.phoneapp.http.SERVER_PORT
import com.panopticon.phoneapp.pairing.Invite
import com.panopticon.phoneapp.ui.theme.PanopticonColors
import java.net.NetworkInterface
import java.util.Collections

/**
 * On-device Connect screen. Invite generation is a local library call
 * (`InviteManager.createInvite()`), never an HTTP route - per docs/design/http-api.md, only a
 * controller already holding a code can redeem it via `POST /api/pair`. A QR image is a
 * nice-to-have not built here; the code + full pairing URL are shown as plain text/mono, which
 * is enough for manual entry during controller-side testing.
 */
@Composable
fun ConnectScreen(app: PanopticonApplication) {
    var invite by remember { mutableStateOf<Invite?>(null) }
    val lanAddress = remember { lanIpAddress() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Connect a controller", color = PanopticonColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "Generate a one-time invite code, then enter it (and this phone's address) in a controller app.",
            color = PanopticonColors.textDim,
            fontSize = 13.sp,
        )

        Button(
            onClick = { invite = app.inviteManager.createInvite() },
            colors = ButtonDefaults.buttonColors(containerColor = PanopticonColors.accent, contentColor = PanopticonColors.accentInk),
        ) {
            Text("Generate invite")
        }

        val current = invite
        if (current != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PanopticonColors.surface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Invite code", color = PanopticonColors.textDim, fontSize = 12.sp)
                    Text(
                        text = current.code,
                        color = PanopticonColors.accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = "Pairing URL", color = PanopticonColors.textDim, fontSize = 12.sp)
                    Text(
                        text = "http://$lanAddress:$SERVER_PORT/api/pair?invite=${current.code}",
                        color = PanopticonColors.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "Expires in 10 minutes, single use.",
                        color = PanopticonColors.textFaint,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = PanopticonColors.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "This phone's address", color = PanopticonColors.textDim, fontSize = 12.sp)
                Text(
                    text = "$lanAddress:$SERVER_PORT",
                    color = PanopticonColors.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

/** Best-effort LAN IPv4 address for display - falls back to a placeholder if none is found (no network, airplane mode, etc). */
private fun lanIpAddress(): String {
    return try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "unknown (no network)"
    } catch (e: Exception) {
        "unknown"
    }
}
