package com.panopticon.phoneapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.panopticon.phoneapp.service.PanopticonService
import com.panopticon.phoneapp.ui.nav.PanopticonNavHost
import com.panopticon.phoneapp.ui.theme.PanopticonColors
import com.panopticon.phoneapp.ui.theme.PanopticonTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            PanopticonService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = PanopticonApplication.from(this)

        if (hasCameraPermission()) {
            PanopticonService.start(this)
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        setContent {
            PanopticonTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = PanopticonColors.bg) {
                    PanopticonNavHost(app)
                }
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
