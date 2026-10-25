package com.example.album

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.album.nav.AlbumApp
import com.example.album.theme.AlbumTheme
import com.example.album.theme.isDarkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系统 splash 延到首帧再退（minSdk 33 的系统 splash + 这个库的退出衔接），避免首帧前闪白
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as AlbumApplication).container.settings
        setContent {
            val themeMode by settings.themeMode.collectAsState()
            val dark = themeMode.isDarkTheme()
            // 固定浅色/深色时状态栏图标要跟着换，否则黑底上还是黑图标
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }
            AlbumTheme(themeMode = themeMode) {
                AlbumApp()
            }
        }
    }
}
