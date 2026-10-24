package com.mm.smartcgm.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.mm.smartcgm.data.AppMode
import com.mm.smartcgm.data.Repo
import com.mm.smartcgm.engine.Alarms
import com.mm.smartcgm.engine.MonitorService
import com.mm.smartcgm.engine.Watchdog

class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        MonitorService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else MonitorService.start(this)

        Watchdog.schedule(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF00796B))) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AppRoot() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app counts as acknowledging a ringing alarm.
        Alarms.stopSound()
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("الرئيسية", Icons.Filled.Home),
    Tab("الكاميرا", Icons.Filled.CameraAlt),
    Tab("الجدول", Icons.Filled.TableChart),
    Tab("التقارير", Icons.Filled.BarChart),
    Tab("الإعدادات", Icons.Filled.Settings),
)

@androidx.compose.runtime.Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val settings by Repo.settings.collectAsState()
    val follower = settings.mode == AppMode.FOLLOWER
    Scaffold(bottomBar = {
        NavigationBar {
            TABS.forEachIndexed { i, t ->
                // The camera tab is the patient's tool; followers don't need it.
                if (follower && i == 1) return@forEachIndexed
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(t.icon, null) }, label = { Text(t.label) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (if (follower && tab == 1) 0 else tab) {
                0 -> HomeScreen()
                1 -> ScannerScreen()
                2 -> LogbookScreen()
                3 -> AnalyticsScreen()
                else -> SettingsScreen()
            }
        }
    }
}
