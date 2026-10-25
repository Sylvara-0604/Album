package com.example.album.data

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式（用户 2026-10-17 令：固定深色 / 固定浅色 / 跟随系统）。 */
enum class ThemeMode { System, Light, Dark }

/**
 * banner 自动换图间隔（用户 2026-10-24 令：只留 **默认 / 5 秒 / 10 秒 / 1 分钟**）。
 *
 * `Default` = 9.9 的 TTL（可见 5s、不可见 15s）；其余是**固定间隔**（可见与否同一值）。
 * ⚠️ 同批删除了「不自动切」与"切页面再回来强制换一张"的机制（用户令），所以这里不再有 `Off`。
 */
enum class BannerInterval(val visibleMillis: Long, val hiddenMillis: Long) {
    Default(5_000L, 15_000L),
    FiveSeconds(5_000L, 5_000L),
    TenSeconds(10_000L, 10_000L),
    OneMinute(60_000L, 60_000L),
}

/**
 * 换页节拍（用户 2026-10-24 令：「把"更贴原版节拍"和"更快"都做出来，在设置里做切换选项，
 * 当前效果不要删，我对比看看」）。
 *
 * 只改**新页起跑延迟**（出页那 300ms 不变、`PAGE_SWITCH_MILLIS` 也不变）：
 * - [Soft] = 240ms：只在两段"肉眼不可见的尾巴"上重叠（当前效果，最低总 α ≈4%、仅 21ms）；
 * - [Serial] = 300ms：9.9 的严格串行（出完才进，中间约 47ms 两边都 <1%）；
 * - [Fast] = 220ms：更快，仍在"无双影"区间内（<220 会出现两页同时 ≥5%）。
 */
enum class PageSwitchTempo(val enterDelayMillis: Long) {
    Soft(240L),
    Serial(300L),
    Fast(220L),
}

/**
 * 查看页关闭方式（用户 2026-10-25 令：「T7 返回时"父页从右侧整幅推进来盖住，盖满即结束"能否改成
 * 查看页原路退出，你在设置里做个切换菜单，我对比看看」）。
 *
 * - [Wipe] = 当前效果：关闭时父页（网格）从右侧整幅推进来盖住查看页，盖满即结束；
 * - [SlideBack] = 查看页沿来路向右滑出，网格原地露出来（父页不动）。
 *
 * 只影响**查看器**（视频页同属查看器）；裁剪页仍走 wipe。
 */
enum class ViewerCloseStyle { Wipe, SlideBack }

/**
 * 应用级设置（官方 `SharedPreferences` + `StateFlow`，不引 DataStore）。
 *
 * 主题 / banner 间隔 / 换页节拍都是"改完立刻生效"的：写偏好 + 推一次 flow，UI 侧 collectAsState 即可。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val appContext = context.applicationContext

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        // 资源层（窗口底色 / 系统 splash）只认 uiMode ⇒ 把"应用内主题"同步给系统，
        // 否则"系统浅色 + 应用固定深色"时冷启动会先闪一帧浅色（`docs/research/DARK_MODE_AUDIT.md` M2）。
        applyNightMode(_themeMode.value)
    }

    private val _bannerInterval = MutableStateFlow(readBannerInterval())
    val bannerInterval: StateFlow<BannerInterval> = _bannerInterval.asStateFlow()

    private val _pageSwitchTempo = MutableStateFlow(readPageSwitchTempo())
    val pageSwitchTempo: StateFlow<PageSwitchTempo> = _pageSwitchTempo.asStateFlow()

    private val _viewerCloseStyle = MutableStateFlow(readViewerCloseStyle())
    val viewerCloseStyle: StateFlow<ViewerCloseStyle> = _viewerCloseStyle.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        _themeMode.value = mode
        applyNightMode(mode)
    }

    /**
     * 把 `ThemeMode` 映射成系统的"应用级夜间模式"（API 31+ 官方 API）。
     *
     * 这会触发一次 uiMode 配置变更（Activity 重建），但重建后窗口底色 / splash / 系统栏
     * 全都和 Compose 里解析出的配色一致 —— 这正是原来不一致的根因。
     */
    private fun applyNightMode(mode: ThemeMode) {
        val manager = appContext.getSystemService(UiModeManager::class.java) ?: return
        manager.setApplicationNightMode(
            when (mode) {
                ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    fun setBannerInterval(interval: BannerInterval) {
        prefs.edit { putString(KEY_BANNER_INTERVAL, interval.name) }
        _bannerInterval.value = interval
    }

    private fun readThemeMode(): ThemeMode =
        prefs.getString(KEY_THEME_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.System

    private fun readBannerInterval(): BannerInterval =
        prefs.getString(KEY_BANNER_INTERVAL, null)
            ?.let { name -> BannerInterval.entries.firstOrNull { it.name == name } }
            ?: BannerInterval.Default

    fun setPageSwitchTempo(tempo: PageSwitchTempo) {
        prefs.edit { putString(KEY_PAGE_SWITCH_TEMPO, tempo.name) }
        _pageSwitchTempo.value = tempo
    }

    private fun readPageSwitchTempo(): PageSwitchTempo =
        prefs.getString(KEY_PAGE_SWITCH_TEMPO, null)
            ?.let { name -> PageSwitchTempo.entries.firstOrNull { it.name == name } }
            ?: PageSwitchTempo.Soft

    fun setViewerCloseStyle(style: ViewerCloseStyle) {
        prefs.edit { putString(KEY_VIEWER_CLOSE_STYLE, style.name) }
        _viewerCloseStyle.value = style
    }

    private fun readViewerCloseStyle(): ViewerCloseStyle =
        prefs.getString(KEY_VIEWER_CLOSE_STYLE, null)
            ?.let { name -> ViewerCloseStyle.entries.firstOrNull { it.name == name } }
            ?: ViewerCloseStyle.Wipe

    private companion object {
        const val FILE_NAME = "album_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_BANNER_INTERVAL = "banner_interval"
        const val KEY_PAGE_SWITCH_TEMPO = "page_switch_tempo"
        const val KEY_VIEWER_CLOSE_STYLE = "viewer_close_style"
    }
}
