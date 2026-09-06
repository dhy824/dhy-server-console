package cn.zzmllk.amadeus.ui

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

fun ComponentActivity.enableAmadeusEdgeToEdge(darkTheme: Boolean = false) {
    val transparentStyle = if (darkTheme) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }
    val navigationStyle = if (darkTheme) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, LEGACY_NAVIGATION_SCRIM)
    }
    enableEdgeToEdge(
        statusBarStyle = transparentStyle,
        navigationBarStyle = navigationStyle
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

private const val LEGACY_NAVIGATION_SCRIM: Int = 0x40000000
