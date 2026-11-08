package com.watchlink.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchlink.phone.ui.PhoneScreen
import com.watchlink.phone.ui.WatchLinkTheme

class MainActivity : ComponentActivity() {

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                LinkService.sendFile(this, uri)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LinkService.start(this)
        requestRuntimePermissions()

        setContent {
            WatchLinkTheme {
                PhoneScreen(
                    onPickFile = { pickFileLauncher.launch(arrayOf("*/*")) },
                    onOpenNotificationAccess = { openNotificationAccessSettings() },
                    onRefresh = { LinkService.start(this) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.setListenerGranted(isNotificationAccessGranted())
    }

    private fun isNotificationAccessGranted(): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }.getOrDefault(false)

    private fun openNotificationAccessSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, packageName + "/" +
                    PhoneNotificationListener::class.java.name),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }
}
