package cn.zzmllk.amadeus

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import cn.zzmllk.amadeus.ui.AmadeusApp
import cn.zzmllk.amadeus.ui.AmadeusTheme
import cn.zzmllk.amadeus.ui.enableAmadeusEdgeToEdge
import cn.zzmllk.amadeus.web.WebConsoleActivity

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deleteSharedPreferences("amadeus_codex_conversation")
        enableAmadeusEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            AmadeusTheme {
                AmadeusApp(
                    viewModel = viewModel,
                    onOpenConsole = { request ->
                        startActivity(
                            Intent(this, WebConsoleActivity::class.java)
                                .putExtra(WebConsoleActivity.EXTRA_TITLE, request.title)
                                .putExtra(WebConsoleActivity.EXTRA_URL, request.url)
                        )
                    }
                )
            }
        }
    }
}
