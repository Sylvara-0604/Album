package com.example.album.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.album.AlbumApplication
import com.example.album.R
import com.example.album.data.AppSettings
import com.example.album.data.BannerInterval
import com.example.album.data.ImageCache
import com.example.album.data.PageSwitchTempo
import com.example.album.data.ThemeMode
import com.example.album.data.ViewerCloseStyle
import com.example.album.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（用户 2026-10-17 令：放真实开关）。
 *
 * 项目：主题模式（固定深色 / 固定浅色 / 跟随系统）、banner 换图间隔（**默认 / 5 秒 / 10 秒 / 1 分钟**，
 * 用户 2026-10-24 令：删掉「不自动切」——因为"切页面回来再换一张"的机制已一并删除）、
 * 清除应用缓存（Coil 内存 + 磁盘）、关于页入口。
 *
 * 没有顶栏、没有返回按钮：进出靠系统返回手势/返回键（用户令）。
 */
@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember(context) { (context.applicationContext as AlbumApplication).container.settings }

    val themeMode by settings.themeMode.collectAsState()
    val bannerInterval by settings.bannerInterval.collectAsState()
    val pageSwitchTempo by settings.pageSwitchTempo.collectAsState()
    val viewerCloseStyle by settings.viewerCloseStyle.collectAsState()

    var intervalDialog by remember { mutableStateOf(false) }
    var tempoDialog by remember { mutableStateOf(false) }
    var closeStyleDialog by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clearedText = stringResource(R.string.settings_cache_cleared)

    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { ImageCache.sizeBytes(context) }
    }

    val contentTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = contentTop + 24.dp, bottom = 96.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            SectionTitle(stringResource(R.string.settings_theme_section))
            ThemeMode.entries.forEach { mode ->
                ThemeRow(
                    label = stringResource(themeLabel(mode)),
                    selected = themeMode == mode,
                    onSelect = { settings.setThemeMode(mode) },
                )
            }

            SectionTitle(stringResource(R.string.settings_banner_section))
            ValueRow(
                label = stringResource(R.string.settings_banner_interval_row),
                value = stringResource(intervalLabel(bannerInterval)),
                onClick = { intervalDialog = true },
            )

            // 换页节拍（用户 2026-10-24 令：把"更贴原版"和"更快"都做出来，设置里切换对比）
            SectionTitle(stringResource(R.string.settings_motion_section))
            ValueRow(
                label = stringResource(R.string.settings_page_switch_row),
                value = stringResource(tempoLabel(pageSwitchTempo)),
                onClick = { tempoDialog = true },
            )

            // 查看页关闭方式（用户 2026-10-25 令：加切换菜单，方便对比"网格盖上"与"原路退出"）
            ValueRow(
                label = stringResource(R.string.settings_viewer_close_row),
                value = stringResource(closeStyleLabel(viewerCloseStyle)),
                onClick = { closeStyleDialog = true },
            )

            SectionTitle(stringResource(R.string.settings_storage_section))
            ValueRow(
                label = stringResource(R.string.settings_clear_cache),
                value = if (cacheBytes >= 0) formatCache(cacheBytes) else "",
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ImageCache.clear(context) }
                        cacheBytes = withContext(Dispatchers.IO) { ImageCache.sizeBytes(context) }
                        snackbar.showSnackbar(clearedText)
                    }
                },
            )

            SectionTitle(stringResource(R.string.settings_about_section))
            ValueRow(
                label = stringResource(R.string.settings_about),
                value = "",
                onClick = onOpenAbout,
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }

    if (intervalDialog) {
        AlertDialog(
            onDismissRequest = { intervalDialog = false },
            title = { Text(stringResource(R.string.settings_banner_interval_row)) },
            text = {
                Column {
                    BannerInterval.entries.forEach { interval ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = bannerInterval == interval,
                                    onClick = {
                                        settings.setBannerInterval(interval)
                                        intervalDialog = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = bannerInterval == interval,
                                onClick = null,
                            )
                            Text(
                                text = stringResource(intervalLabel(interval)),
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { intervalDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (closeStyleDialog) {
        AlertDialog(
            onDismissRequest = { closeStyleDialog = false },
            title = { Text(stringResource(R.string.settings_viewer_close_row)) },
            text = {
                Column {
                    ViewerCloseStyle.entries.forEach { style ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = viewerCloseStyle == style,
                                    onClick = {
                                        settings.setViewerCloseStyle(style)
                                        closeStyleDialog = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = viewerCloseStyle == style,
                                onClick = null,
                            )
                            Text(
                                text = stringResource(closeStyleLabel(style)),
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { closeStyleDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (tempoDialog) {
        AlertDialog(
            onDismissRequest = { tempoDialog = false },
            title = { Text(stringResource(R.string.settings_page_switch_row)) },
            text = {
                Column {
                    PageSwitchTempo.entries.forEach { tempo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = pageSwitchTempo == tempo,
                                    onClick = {
                                        settings.setPageSwitchTempo(tempo)
                                        tempoDialog = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = pageSwitchTempo == tempo,
                                onClick = null,
                            )
                            Text(
                                text = stringResource(tempoLabel(tempo)),
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { tempoDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 单选项行（主题模式）：整行可选，左侧文字 + 右侧 RadioButton。 */
@Composable
private fun ThemeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected = selected, onClick = null)
    }
}

/** 值行（点开对话框 / 进关于页 / 清缓存）：左标签 + 右值。 */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(start = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.settings_theme_system
    ThemeMode.Light -> R.string.settings_theme_light
    ThemeMode.Dark -> R.string.settings_theme_dark
}

private fun intervalLabel(interval: BannerInterval): Int = when (interval) {
    BannerInterval.Default -> R.string.settings_banner_interval_default
    BannerInterval.FiveSeconds -> R.string.settings_banner_interval_5s
    BannerInterval.TenSeconds -> R.string.settings_banner_interval_10s
    BannerInterval.OneMinute -> R.string.settings_banner_interval_60s
}

private fun tempoLabel(tempo: PageSwitchTempo): Int = when (tempo) {
    PageSwitchTempo.Soft -> R.string.settings_page_switch_soft
    PageSwitchTempo.Serial -> R.string.settings_page_switch_serial
    PageSwitchTempo.Fast -> R.string.settings_page_switch_fast
}

private fun closeStyleLabel(style: ViewerCloseStyle): Int = when (style) {
    ViewerCloseStyle.Wipe -> R.string.settings_viewer_close_wipe
    ViewerCloseStyle.SlideBack -> R.string.settings_viewer_close_slide
}

private fun formatCache(bytes: Long): String =
    Formatters.fileSize(bytes).ifBlank { "0 B" }
