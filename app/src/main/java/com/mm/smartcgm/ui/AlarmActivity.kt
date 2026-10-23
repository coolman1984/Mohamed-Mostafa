package com.mm.smartcgm.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mm.smartcgm.core.AlertType
import com.mm.smartcgm.engine.Alarms

/**
 * P1-2: dedicated full-screen urgent-low alarm. Opens over the lock screen, turns the screen on, and the sound keeps
 * looping until "✅ أخدت سكر" is pressed. Back does nothing (it must be a deliberate tap). There is NO snooze here:
 * urgent low is never snoozable, and the engine re-alarms in 5 minutes if glucose is still low.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            // Shown above the lock screen without unlocking the phone (no keyguard dismiss needed to press the button).
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = Unit })
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "🚨 هبوط خطير"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AlarmContent(title, body) { acknowledge() }
            }
        }
    }

    /** Silences the sound and clears the notification; the alert schedule itself is untouched. */
    fun acknowledge() {
        Alarms.stopSound()
        getSystemService(NotificationManager::class.java)?.cancel(1000 + AlertType.URGENT_LOW.ordinal)
        acknowledged = true
        finish()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        /** Visible for tests. */
        @Volatile var acknowledged = false

        fun intent(ctx: Context, title: String, body: String) = Intent(ctx, AlarmActivity::class.java)
            .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BODY, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@Composable
fun AlarmContent(title: String, body: String, onAck: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFFB71C1C)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text("🚨", fontSize = 80.sp)
        Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
        Text("كُل أو اشرب حاجة مسكرة حالاً (عصير أو 3 معالق سكر)، وبعدين دوس الزرار.", color = Color.White,
            fontSize = 18.sp, textAlign = TextAlign.Center)
        Button(onClick = onAck, Modifier.fillMaxWidth().height(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C))) {
            Text("✅ أخدت سكر", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Text("الإنذار هيرجع بعد 5 دقايق لو السكر لسه واطي.", color = Color.White, fontSize = 14.sp)
    }
}
