package com.workbuddy.cat

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat

/** Debug-only: a full-screen (immersive) activity to test "hide in full screen". `adb shell am start -n com.workbuddy.cat/.ImmersiveTestActivity` */
class ImmersiveTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = View(this).apply { setBackgroundColor(Color.rgb(20, 60, 40)) }
        setContentView(v)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
