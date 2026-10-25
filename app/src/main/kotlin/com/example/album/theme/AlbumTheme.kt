package com.example.album.theme

import android.app.Activity
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.example.album.data.ThemeMode

/**
 * Material 3 Expressive 主题。
 *
 * - 配色：系统 dynamic color（Material You，取壁纸主色）。minSdk 33 ⇒ 不需要静态兜底配色。
 * - 深浅：设置页可选「跟随系统 / 固定浅色 / 固定深色」（用户 2026-10-17 令）。
 * - 动效：`MotionScheme.expressive()` —— 全工程的动画规格从这里取，不在业务里散落魔数。
 *
 * 实验性 API 只允许出现在 theme/ 与 ui/components/ 里，业务屏幕不直接引用。
 */
/** 当前**解析后**是不是深色主题（页面级 chrome 判定用，例如查看器/裁剪页恒为深底）。 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** 主题模式 → 是否深色（System 跟随系统）。Activity 侧更新系统栏图标也用它，保证同一判据。 */
@Composable
fun ThemeMode.isDarkTheme(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun AlbumTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = themeMode.isDarkTheme()
    val colorScheme = if (dark) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    if (context is Activity) {
        // 1) 窗口底色跟着**解析后的**配色走：清单里的 `windowBackground` 只有 values/values-night 两套，
        //    "系统浅色 + 应用固定深色"时那套资源是错的（首帧/转场边缘会露一块浅色）。
        // 2) `MaterialTheme` **不 provide `LocalContentColor`**（material3 1.5.0-beta01 实测：默认是纯黑），
        //    而我们的根节点是 `Box.background(surface)` 不是 Surface ⇒ 深色下所有裸 `Text`
        //    （设置页/关于页/空状态/权限页…）都是黑字压深底（≈1.2:1）。这里补上内容色。
        //    依据：`docs/research/DARK_MODE_AUDIT.md` M1/M2。
        SideEffect {
            context.window.setBackgroundDrawable(colorScheme.surface.toArgb().toDrawable())
        }
    }
    CompositionLocalProvider(
        LocalDarkTheme provides dark,
        LocalContentColor provides colorScheme.onSurface,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
