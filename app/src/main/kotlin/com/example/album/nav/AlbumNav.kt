package com.example.album.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.example.album.AlbumApplication
import com.example.album.data.MediaItem
import com.example.album.data.ViewerCloseStyle
import com.example.album.data.aspectRatio
import com.example.album.ui.common.PLATFORM_FADE_IN_DELAY_MILLIS
import com.example.album.ui.common.PLATFORM_FADE_IN_MILLIS
import com.example.album.ui.common.PLATFORM_SLIDE
import com.example.album.ui.common.PLATFORM_SLIDE_MILLIS
import com.example.album.ui.common.PlatformSlideEasing
import com.example.album.ui.common.platformCloseEnter
import com.example.album.ui.common.platformCloseExit
import com.example.album.ui.common.platformOpenEnter
import com.example.album.ui.common.platformOpenExit
import com.example.album.ui.common.LocalPageSwitch
import com.example.album.ui.common.PAGE_SWITCH_MILLIS
import com.example.album.ui.common.PageId
import com.example.album.ui.common.PageSwitchDirection
import com.example.album.ui.common.PageSwitchState
import com.example.album.ui.common.PermissionsGate
import com.example.album.ui.crop.CropScreen
import com.example.album.ui.folders.FolderGridScreen
import com.example.album.ui.folders.FolderListScreen
import com.example.album.ui.settings.AboutScreen
import com.example.album.ui.settings.SettingsScreen
import com.example.album.ui.timeline.TimelineScreen
import com.example.album.theme.LocalDarkTheme
import com.example.album.ui.viewer.ViewerScreen
import com.example.album.ui.components.BannerAssets
import com.example.album.ui.components.FractionalGridState
import com.example.album.ui.components.rememberBannerAssets
import com.example.album.ui.components.rememberFractionalGridState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 返回栈底：时间线。NavKey 用 @Serializable，返回栈才能被保存/恢复（rememberNavBackStack）。 */
@Serializable
data object Timeline : NavKey

/** 文件夹列表（首页胶囊入口，压栈）。 */
@Serializable
data object FolderList : NavKey

/** 某个文件夹的网格（压栈）。没有顶栏了，`name` 不再需要（标题栏已删）。 */
@Serializable
data class FolderGrid(val bucketId: String) : NavKey

/** 设置（压栈）：主题 / banner 换图间隔 / 清除缓存 / 关于。 */
@Serializable
data object Settings : NavKey

/** 关于页（设置里的入口，压栈）。 */
@Serializable
data object About : NavKey

/**
 * 查看器 = **首页之上的覆盖层**（不是 Nav3 目的地；见 [AlbumApp] 里"覆盖层"那一段的注释）。
 * 字段与旧 `Viewer` NavKey 完全一致：`index` = 被点那一项在所属列表里的绝对下标；
 * `thumbPx` = 那一格缩略图的像素边长；`id`/`uri`/`aspect` = 首帧种子。
 */
private data class ViewerTarget(
    val index: Int,
    val thumbPx: Int,
    val bucketId: String? = null,
    val id: Long = -1L,
    val uri: String = "",
    val aspect: Float = 0f,
)

/** 裁剪 = 压在查看器之上的第二层覆盖层（与 [ViewerTarget] 同样的下标空间约定）。 */
private data class CropTarget(val index: Int, val bucketId: String? = null)

/** 裁剪页进出（与页面切换无关，保持原来的 200ms 纯淡化） */
private const val CROP_FADE_MILLIS = 200

/**
 * 进文件夹时的 ExitTransition：旧页**保持不透明**被留在场景里 300ms（它的 item 自己淡到 0），
 * 新页的 EnterTransition 是 `None` + 内容层 α=0（由 `PageSwitchState` 那个时钟驱动）。
 */
private val pageSwitchHide = fadeOut(tween(durationMillis = 1, delayMillis = PAGE_SWITCH_MILLIS))

/**
 * 导航栈 + 两层覆盖层的**全部可变状态**。
 *
 * ⚠️ 2026-10-24 r47（性能 A：重组波治理）—— **为什么必须收进这个 `@Stable` 持有者**：
 * 以前这些 `var viewerTarget by remember { mutableStateOf(...) }` 直接写在 `AlbumApp` 的组合体里，
 * 而 `AlbumApp` 的组合体又直接构造 `entryProvider { entry<Timeline> { TimelineScreen(...) } }`。
 * 于是「打开查看器」这一下：`viewerTarget` 变 ⇒ `AlbumApp` 重组 ⇒ `entryProvider` 重建 ⇒
 * `TimelineScreen → MediaGrid → 51 个 MediaCell` 全部被标脏 ⇒ 单帧 `measureAndLayout` **130ms**
 * （53 个 scope 的 recompose 51.7 + applyChanges 23.0 + onRemembered 22.7，见 `docs/PERF.md` v0.5.15）。
 * 现在：状态放这里、`AlbumApp` 自己**一个字都不读**（只写几个普通配置字段），
 * 真正读它的是 `BottomStack` / `ViewerLayer` / `CropLayer` 这几个小组合 ⇒ 页面栈参数不变 ⇒ 格子不重组。
 */
@Stable
private class AlbumNavState(
    val backStack: NavBackStack<NavKey>,
    val pageSwitch: PageSwitchState,
    private val scope: CoroutineScope,
) {
    /** 平台转场的 96dp 横移（px）。组合层写入，绘制期读取（普通字段，不触发重组）。 */
    var slidePx: Float = 0f

    /** 关闭动画的整幅 wipe 宽度 = 整屏宽（px）。 */
    var windowWidthPx: Float = 0f

    /**
     * 查看页关闭方式（用户 2026-10-25 令：设置页可切，好对比"网格盖上"和"原路退出"）。
     * 普通字段 ⇒ 切换设置不会让任何组合失效；关闭那一刻读一次即可。
     */
    var viewerCloseStyle: ViewerCloseStyle = ViewerCloseStyle.Wipe

    /** 换页节拍（设置页可切 240 / 300 / 220），点击那一刻读。 */
    var switchEnterDelayMillis: Long = 240L

    /** 栈底返回 = 退到后台（9.9 `moveTaskToBack(false)`）。 */
    var moveToBack: (() -> Unit)? = null

    var viewerTarget: ViewerTarget? by mutableStateOf(null)
        private set
    var cropTarget: CropTarget? by mutableStateOf(null)
        private set
    var viewerClosing: Boolean by mutableStateOf(false)
        private set
    var cropClosing: Boolean by mutableStateOf(false)
        private set

    /** 打开动画进行中（吞输入：96dp 的缝里不许再点开一张） */
    var viewerOpening: Boolean by mutableStateOf(false)
        private set
    var cropOpening: Boolean by mutableStateOf(false)
        private set

    /** 关闭开始时上报的"刚才看的那张"（媒体 id）；宿主网格据此居中，900ms 后清空。 */
    var centerOnId: Long? by mutableStateOf(null)

    val viewerProgress = Animatable(0f)
    val cropProgress = Animatable(0f)
    val viewerFade = Animatable(1f)
    val cropFade = Animatable(1f)

    /**
     * 「首页是不是最上层」的**延迟读取版本**（给 `TimelineScreen` 的 `active`）。
     *
     * 一定要传 lambda、不能传 Boolean：传值的话，覆盖层一开一关就要把整个时间线（含 51 个格子）
     * 重组一遍 —— 那正是上一条注释里 130ms 的来源。传 lambda 之后，只有真正读它的小组合
     * （`BannerAutoRotate` / `HomeCapsule`）在自己的作用域里被标脏。
     */
    val homeActive: () -> Boolean = { viewerTarget == null && backStack.lastOrNull() is Timeline }

    // ───────── 覆盖层动作（全部是普通方法，组合层只传引用，不读状态） ─────────

    fun openGridItem(index: Int, thumbPx: Int, item: MediaItem, bucketId: String?) {
        openViewer(
            ViewerTarget(
                index = index,
                thumbPx = thumbPx,
                bucketId = bucketId,
                id = item.id,
                uri = item.uri.toString(),
                aspect = item.aspectRatio(),
            ),
        )
    }

    fun openViewer(target: ViewerTarget) {
        // 已经打开 / 正在关闭：忽略重复点击（覆盖层没有导航栈做这件事，自己守一道）
        if (viewerTarget != null) return
        viewerTarget = target
        viewerClosing = false
        viewerOpening = true
        scope.launch {
            viewerProgress.snapTo(0f)
            viewerFade.snapTo(0f)
            launch {
                viewerFade.animateTo(
                    1f,
                    tween(
                        durationMillis = PLATFORM_FADE_IN_MILLIS,
                        delayMillis = PLATFORM_FADE_IN_DELAY_MILLIS,
                        easing = LinearEasing,
                    ),
                )
            }
            viewerProgress.animateTo(1f, tween(PLATFORM_SLIDE_MILLIS, easing = PlatformSlideEasing))
            viewerOpening = false
        }
    }

    fun closeViewer() {
        if (viewerTarget == null || viewerClosing) return
        viewerClosing = true
        scope.launch {
            viewerProgress.animateTo(0f, tween(PLATFORM_SLIDE_MILLIS, easing = PlatformSlideEasing))
            viewerTarget = null
            cropTarget = null
            viewerClosing = false
        }
    }

    fun openCrop(target: CropTarget) {
        if (viewerTarget == null || cropTarget != null) return
        cropTarget = target
        cropClosing = false
        cropOpening = true
        scope.launch {
            cropProgress.snapTo(0f)
            cropFade.snapTo(0f)
            launch {
                cropFade.animateTo(
                    1f,
                    tween(
                        durationMillis = PLATFORM_FADE_IN_MILLIS,
                        delayMillis = PLATFORM_FADE_IN_DELAY_MILLIS,
                        easing = LinearEasing,
                    ),
                )
            }
            cropProgress.animateTo(1f, tween(PLATFORM_SLIDE_MILLIS, easing = PlatformSlideEasing))
            cropOpening = false
        }
    }

    fun closeCrop() {
        if (cropTarget == null || cropClosing) return
        cropClosing = true
        scope.launch {
            cropProgress.animateTo(0f, tween(PLATFORM_SLIDE_MILLIS, easing = PlatformSlideEasing))
            cropTarget = null
            cropClosing = false
        }
    }

    // ───────── 页面栈动作 ─────────

    fun openFolders() = navigate(PageSwitchDirection.INTO) { backStack.add(FolderList) }

    fun openFolder(bucketId: String) = navigate(PageSwitchDirection.INTO) { backStack.add(FolderGrid(bucketId)) }

    fun openSettings() {
        // 设置页走**平台默认 Activity 动画**（见 PlatformTransition.kt），
        // 所以不启动 9.9 的逐项 z 动画：先把换页状态收回 IDLE，再压栈。
        pageSwitch.settle()
        backStack.add(Settings)
    }

    /** 统一的返回动作：覆盖层优先（裁剪 → 查看器）→ 页面栈 → 栈底退后台。 */
    fun back() {
        // 换页过渡中吞掉返回（9.9 `MainView.java:406-425`）
        if (pageSwitch.isSwitching) return
        if (cropTarget != null) {
            closeCrop()
            return
        }
        if (viewerTarget != null) {
            closeViewer()
            return
        }
        when {
            backStack.size == 1 -> moveToBack?.invoke()
            backStack.lastOrNull() is Settings -> {
                // 设置页返回 = 平台默认关闭动画（父页从 −96dp 滑回），不走 9.9 的逐项 z 动画
                pageSwitch.settle()
                backStack.removeLastOrNull()
            }
            backStack.size > 1 -> navigate(PageSwitchDirection.OUT) { backStack.removeLastOrNull() }
        }
    }

    /** 下钻/返回：先算好 from/to，再改返回栈，最后同时启动两段动画。 */
    fun navigate(direction: PageSwitchDirection, change: () -> Unit) {
        if (pageSwitch.isSwitching) return
        val from = PageIdOf(backStack.lastOrNull())
        change()
        val to = PageIdOf(backStack.lastOrNull())
        pageSwitch.start(
            scope,
            from,
            to,
            direction,
            switchEnterDelayMillis,
            enterMillis = PAGE_SWITCH_MILLIS,
            enterAlphaPower = 3f,
        )
    }
}

/**
 * Nav3 1.2.0 的 `transitionSpec` 拿到的是 **Scene**（不是 NavKey），路由要从 `Scene.key` 取。
 * 不这么取的话 `targetState is Viewer` 恒为 false（`Scene` 与 `Viewer` 无继承关系，上一版就踩了这个坑）。
 */
private fun Scene<*>.routeKey(): Any? = key

/** NavKey → 换页动画里的页面身份。 */
private fun PageIdOf(key: Any?): PageId? = when (key) {
    is Timeline -> PageId.TIMELINE
    is FolderList -> PageId.FOLDER_LIST
    is FolderGrid -> PageId.FOLDER_GRID
    is Settings -> PageId.SETTINGS
    else -> null
}

/**
 * 返回（pop）这一段的过渡。
 *
 * ⚠️ 2026-10-24 r35（用户报「C1 返回时有重影」「B3 图片查看页返回动画也有问题」）：
 * Nav3 1.2.0 的 `NavDisplay` 除了 `popTransitionSpec` 还有一个 **`predictivePopTransitionSpec`**，
 * 我们一直没传 ⇒ 走它的默认值（`scaleOut(targetScale = 0.7)`）⇒ 手势返回会变成"页面缩成一张卡"。
 * 预测性返回已在 r46 整体删除（manifest 关 `enableOnBackInvokedCallback`），这条路径不再可达；
 * 这里保留 `popContent` 只服务普通返回（设置页）。
 */
private fun <T : Any> popContent(scope: AnimatedContentTransitionScope<Scene<T>>, slidePx: Int): ContentTransform =
    ContentTransform(
        targetContentEnter = run {
            when (scope.initialState.key) {
                // 关闭：父页**整幅从右侧滑入**，把关闭页整体"推"出画面。
                // 为什么不是 AOSP 的"父页 −96dp + 关闭页右滑淡出"：在 Nav3 的 `AnimatedContent` 里
                // **目标页（父页）画在上层**，所以关闭页的位移根本看不见，只能看到父页以某种透明度
                // 压在关闭页上 = 用户说的"重影"。改成父页整幅 wipe ⇒ 任一瞬间屏幕只由一条边界分成
                // "父页 / 关闭页"两块，两页都是不透明，**不可能出现叠影**。
                is Settings -> platformCloseEnter(slidePx)
                is FolderList, is FolderGrid -> EnterTransition.None
                else -> fadeIn(tween(CROP_FADE_MILLIS))
            }
        },
        initialContentExit = run {
            when (scope.initialState.key) {
                // 关闭页：**原地不动、最后 1ms 直接移除**（被父页 wipe 盖住，本来也看不见）
                is Settings -> platformCloseExit()
                // 返回：当前页留 300ms 演"逐项出"（新页晚 PAGE_SWITCH_ENTER_DELAY 起跑）
                is FolderList, is FolderGrid -> fadeOut(tween(durationMillis = 1, delayMillis = PAGE_SWITCH_MILLIS))
                else -> fadeOut(tween(CROP_FADE_MILLIS))
            }
        },
        // 显式 null ⇒ 不做尺寸动画（默认的 `SizeTransform()` 会让整页像卡片一样缩放）
        sizeTransform = null,
    )

/**
 * 每一页自己铺一层**不透明**底色（`surface`）。
 *
 * 用户 2026-10-24 报「C1 返回时有重影」的真因就在这里：r26b 为了"两页别各白一次"把底色收进
 * `AlbumApp` 最外层的一个 Box，各页自己不再铺 ⇒ 页面里**没画东西的地方是透明的**。
 * 于是两页同时在场景里时（换页 / 平台转场），上层页面的空白处会把下层的照片透出来。
 */
@Composable
private fun PageSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}

@Composable
fun AlbumApp() {
    val backStack = rememberNavBackStack(Timeline)
    val pageSwitch = remember { PageSwitchState() }
    // 首页网格状态提到这里持有：Nav3 在压栈/返回时会重建页面组合，
    // 若状态跟着页面走，`rememberSaveable` 恢复出来的滚动量会把"刚居中好的位置"覆盖掉。
    val timelineGridState = rememberFractionalGridState()
    // banner 的位图缓存 + 淡入进度也提到这里（r45：放在页面里时，打开查看器首页离开组合 ⇒ 缓存被丢 ⇒
    // 返回时先空白几帧再淡入）。
    val bannerAssets = rememberBannerAssets()
    val overlayScope = rememberCoroutineScope()

    // ⚠️ 这个持有者**只有这一处**创建；下面的组合体不许读它的状态（见 `AlbumNavState` 的注释）。
    val nav = remember { AlbumNavState(backStack, pageSwitch, overlayScope) }

    // 以下都是"普通字段写入"（不是快照状态）⇒ 不会让任何组合失效。
    nav.slidePx = with(LocalDensity.current) { PLATFORM_SLIDE.toPx() }
    nav.windowWidthPx = LocalWindowInfo.current.containerSize.width.toFloat()
    val activity = LocalActivity.current
    nav.moveToBack = { activity?.moveTaskToBack(false) }

    // 换页节拍（设置页可切换：240 当前 / 300 严格串行 / 220 更快）
    val settingsContext = LocalContext.current
    val appSettings = remember(settingsContext) {
        (settingsContext.applicationContext as AlbumApplication).container.settings
    }
    val pageSwitchTempo by appSettings.pageSwitchTempo.collectAsStateWithLifecycle()
    nav.switchEnterDelayMillis = pageSwitchTempo.enterDelayMillis
    val viewerCloseStyle by appSettings.viewerCloseStyle.collectAsStateWithLifecycle()
    nav.viewerCloseStyle = viewerCloseStyle

    // 覆盖层本身不铺底色（它自己内部铺），这里只保证最外层恒有不透明底。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ① 底层 = 页面栈。覆盖层完全盖住时**跳过绘制**（`alpha = 0` 仍然会画，白费 GPU），
        //    但组合一直保留 —— "首页不重建"就靠这一点。
        BottomStack(nav = nav, gridState = timelineGridState, bannerAssets = bannerAssets)
        // ② 查看器覆盖层
        ViewerLayer(nav)
        // ③ 裁剪覆盖层（压在查看器之上）
        CropLayer(nav)
        // ④ 打开动画期间吞输入
        InputBlocker(nav)
        // ⑤ 关闭后"居中到刚才看的那张"的请求过期
        CenterRequestExpiry(nav)
        // ⑥ 系统栏图标明暗（按当前最上层页面底色）
        SystemBars(nav, LocalDarkTheme.current)
    }
}

/**
 * 底层页面栈的外壳。
 *
 * 只有它会读覆盖层状态（`zIndex` / 位移 / 是否跳过绘制）；里面的 [PageStack] 参数不变
 * ⇒ 打开/关闭查看器时**页面栈不会重组**（这是性能 A 的全部意义）。
 */
@Composable
private fun BottomStack(
    nav: AlbumNavState,
    gridState: FractionalGridState,
    bannerAssets: BannerAssets,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 关闭时父页要盖在关闭页上面（§3：父页整幅 wipe）⇒ 把底层提到上层。
            // 「查看页原路退场」这一档里父页不盖上来 ⇒ 保持在下层。
            .zIndex(if (nav.viewerClosing && nav.viewerCloseStyle == ViewerCloseStyle.Wipe) 2f else 0f)
            .graphicsLayer {
                // ⚠️ 这三行全在**绘制期**读状态 ⇒ 动画期间不会触发重组，只重绘。
                val p = nav.viewerProgress.value
                translationX = when {
                    nav.viewerTarget == null -> 0f
                    // 关闭：父页从右侧整幅推进来。p=1 时在 +W（屏外）→ p→0 时到 0（盖满）。
                    // （r46 写成 (1−p)×W 是**反的**：p=1 时父页直接落 0 把关闭页盖死，
                    //   随后父页往右退、关闭页重新露出来，最后才消失 —— 用户 2026-10-24 报
                    //   「退出动画很奇怪，感觉页面放完退出动画后还没消失」。）
                    nav.viewerClosing ->
                        // wipe = 父页从 +W 推进来；原路退出 = 父页不动（网格原地露出来）
                        if (nav.viewerCloseStyle == ViewerCloseStyle.Wipe) p * nav.windowWidthPx else 0f
                    // 打开：父页左移 96dp（平台 activity_open_exit）
                    else -> -p * nav.slidePx
                }
            }
            .drawWithContent {
                val covered = nav.viewerTarget != null &&
                    !nav.viewerClosing &&
                    nav.viewerProgress.value >= 0.999f
                if (!covered) drawContent()
            },
    ) {
        PageStack(nav, gridState, bannerAssets)
    }
}

/** 页面栈（Nav3）。参数全是稳定值 ⇒ 覆盖层动画期间不会被重组。 */
@Composable
private fun PageStack(
    nav: AlbumNavState,
    gridState: FractionalGridState,
    bannerAssets: BannerAssets,
) {
    CompositionLocalProvider(LocalPageSwitch provides nav.pageSwitch) {
        NavDisplay(
            backStack = nav.backStack,
            onBack = { nav.back() },
            // ⚠️ **必须显式给 null**：Nav3 1.2.0 的 `NavDisplay` 会用**自己这个参数**重新构造
            // `ContentTransform`，我们在 `transitionSpec` 里返回的 `sizeTransform = null` 会被它**丢掉**。
            // 后果（用户 2026-10-24 报 C1「返回时有重影」）：换页时容器按两页尺寸做尺寸动画 ⇒
            // 上层未离场那一页被压窄/裁切，下层内容从被裁掉的区域透出来 = 两层同时可见。
            sizeTransform = null,
            // 传列表就会整体替换默认装饰器 ⇒ 必须把这两个补回来（否则滚动位置/VM 状态不保存）。
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            transitionSpec = {
                val target = targetState.routeKey()
                when {
                    // 设置页 = 平台默认 Activity 动画（查看器/裁剪已改成覆盖层，不走这里）
                    target is Settings ->
                        pageSwitchContent(platformOpenEnter(nav.slidePx.toInt()), platformOpenExit(nav.slidePx.toInt()))

                    // 进文件夹：新页在场景里但内容 α=0（PageSwitch 还没起跑），旧页留 300ms 演"逐项出"
                    target is FolderList || target is FolderGrid ->
                        pageSwitchContent(EnterTransition.None, pageSwitchHide)

                    else -> fadeIn(tween(CROP_FADE_MILLIS)) togetherWith fadeOut(tween(CROP_FADE_MILLIS))
                }
            },
            popTransitionSpec = { popContent(this, nav.slidePx.toInt()) },
            entryProvider = entryProvider {
                entry<Timeline> { _ ->
                    PageSurface {
                        PermissionsGate { onSelectMore ->
                            TimelineScreen(
                                state = gridState,
                                bannerAssets = bannerAssets,
                                onSelectMore = onSelectMore,
                                // 传 lambda 而不是 Boolean：见 `AlbumNavState.homeActive` 的注释
                                active = nav.homeActive,
                                centerOnMediaId = nav.centerOnId,
                                onOpenFolders = { nav.openFolders() },
                                onOpenSettings = { nav.openSettings() },
                                onOpen = { index, thumbPx, item -> nav.openGridItem(index, thumbPx, item, null) },
                            )
                        }
                    }
                }
                entry<FolderList> { _ ->
                    PageSurface {
                        FolderListScreen(onOpenFolder = { bucketId -> nav.openFolder(bucketId) })
                    }
                }
                entry<FolderGrid> { key ->
                    PageSurface {
                        FolderGridScreen(
                            bucketId = key.bucketId,
                            centerOnMediaId = nav.centerOnId,
                            onOpen = { index, thumbPx, item ->
                                nav.openGridItem(index, thumbPx, item, key.bucketId)
                            },
                        )
                    }
                }
                entry<Settings> { _ ->
                    PageSurface { SettingsScreen(onOpenAbout = { nav.backStack.add(About) }) }
                }
                entry<About> { _ ->
                    PageSurface { AboutScreen() }
                }
                // ⚠️ 这里**不再有** `entry<Viewer>` / `entry<Crop>`：两者改成了覆盖层。
            },
        )

        // 导航栏背后的压暗层（9.9 `navbar_background.xml` 的 9-patch，审计 E11）：
        // 图标恒白 + 背后压暗，照片滑到导航栏下面时也看得清。
        NavBarScrim()

        // 过渡态吞输入（9.9 的 `isClickable=false`）：540ms 内盖一层只消费事件的透明层。
        if (nav.pageSwitch.isSwitching) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun NavBarScrim() {
    val navBarPx = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    if (navBarPx > 0) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { navBarPx.toDp() })
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                    ),
                ),
        )
    }
}

/**
 * ⚠️ `sizeTransform = null` 必须显式给：`AnimatedContent` 默认带一个 `SizeTransform()`，
 * 换页时它会把场景**按旧尺寸/新尺寸做尺寸动画** ⇒ 设置页那种"整页卡片从四周长出来/缩回去"。
 * 两页都是全屏，不需要任何尺寸动画。
 */
private fun pageSwitchContent(enter: EnterTransition, exit: ExitTransition): ContentTransform =
    ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        sizeTransform = null,
    )

@Composable
private fun ViewerLayer(nav: AlbumNavState) {
    val target = nav.viewerTarget ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(
                when {
                    nav.cropClosing -> 2f      // 裁剪关闭：查看器当"父页"盖上去
                    // 查看器关闭：wipe 档让底层盖上来；原路退出档关闭页自己还要在上面滑走
                    nav.viewerClosing ->
                        if (nav.viewerCloseStyle == ViewerCloseStyle.Wipe) 0f else 1f
                    else -> 1f
                },
            )
            .graphicsLayer {
                // 全部在绘制期读状态（动画期间不重组）
                translationX = when {
                    nav.cropTarget == null -> when {
                        // 原路退出：关闭页从 0 滑到 +W（p 是 1→0），网格原地露出
                        nav.viewerClosing && nav.viewerCloseStyle == ViewerCloseStyle.SlideBack ->
                            (1f - nav.viewerProgress.value) * nav.windowWidthPx
                        // wipe：关闭页原地不动，等父页盖满
                        nav.viewerClosing -> 0f
                        else -> (1f - nav.viewerProgress.value) * nav.slidePx
                    }
                    // 裁剪关闭：查看器（父页）从右侧整幅推进来：p=1 在 +W → p→0 到 0
                    nav.cropClosing -> nav.cropProgress.value * nav.windowWidthPx
                    // 裁剪打开：查看器当父页左移 96dp
                    else -> -nav.cropProgress.value * nav.slidePx
                }
                alpha = if (nav.cropTarget != null || nav.viewerClosing) 1f else nav.viewerFade.value
            },
    ) {
        ViewerScreen(
            index = target.index,
            thumbPx = target.thumbPx,
            bucketId = target.bucketId,
            // 首帧（数据库窗口还没查回来时）用的种子：id + uri + 宽高比
            seedId = target.id,
            seedUri = target.uri,
            seedAspect = target.aspect,
            onExit = { nav.closeViewer() },
            onCrop = { index -> nav.openCrop(CropTarget(index, target.bucketId)) },
            // 关闭前先把"刚才看的那张"告诉宿主网格（关闭动画期间在背后居中，返回时已就位）
            onClosed = { id -> nav.centerOnId = id },
        )
    }
}

@Composable
private fun CropLayer(nav: AlbumNavState) {
    val target = nav.cropTarget ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (nav.cropClosing) 0f else 3f)
            .graphicsLayer {
                translationX = if (nav.cropClosing) 0f else (1f - nav.cropProgress.value) * nav.slidePx
                alpha = if (nav.cropClosing) 1f else nav.cropFade.value
            },
    ) {
        CropHost(
            index = target.index,
            bucketId = target.bucketId,
            onDone = { nav.closeCrop() },
            onCancel = { nav.closeCrop() },
        )
        // 裁剪页**没有自己的 BackHandler**（以前靠 Nav3 pop）⇒ 必须在这里登记一层。
        // 位置在查看器之后 ⇒ LIFO 下它先收到返回（r39 教训：NavDisplay 组合更晚会抢走返回）。
        BackHandler { nav.closeCrop() }
    }
}

/** 打开动画期间吞输入：96dp 的缝里不许再点开一张（覆盖层没有导航栈来挡这件事）。 */
@Composable
private fun InputBlocker(nav: AlbumNavState) {
    if (!nav.viewerOpening && !nav.cropOpening) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(4f)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/**
 * 「居中到刚才看的那张」的请求不能永久留着：返回时网格页会重新组合
 * （`rememberSaveable` 把滚动量恢复成旧值），新实例组合后还要再应用一次居中。
 */
@Composable
private fun CenterRequestExpiry(nav: AlbumNavState) {
    val id = nav.centerOnId
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        delay(900)
        nav.centerOnId = null
    }
}

/**
 * 系统栏图标明暗要按**当前页面底色**决定（`docs/research/DARK_MODE_AUDIT.md` M3 + 审计 E10/E11）：
 * - 覆盖层（查看器 / 裁剪）现在**都跟随深浅模式**（r47 起裁剪也改 `surface` 底）⇒ 只有深色模式才用亮图标；
 * - **首页恒亮图标**：顶部压着 `#7F000000` 渐变 + banner 照片（9.9 `MainActivity.java:1960-1970`）；
 * - 导航栏：原版**从不**设 LIGHT_NAVIGATION_BAR（图标恒白，`BarUtils.java:177-207`）⇒ 这里恒白。
 */
@Composable
private fun SystemBars(nav: AlbumNavState, darkTheme: Boolean) {
    val activity = LocalActivity.current
    val rootView = LocalView.current
    val overlayOpen = nav.viewerTarget != null || nav.cropTarget != null
    val onHome = !overlayOpen && nav.backStack.lastOrNull() is Timeline
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, rootView)
        val lightIcons = darkTheme || overlayOpen || onHome
        controller.isAppearanceLightStatusBars = !lightIcons
        controller.isAppearanceLightNavigationBars = false
    }
}

/**
 * 裁剪页的数据闸门：NavKey 只带下标（`MediaItem` 含 `Uri`，不适合塞进返回栈），
 * 这里按同一个窗口把那一项取回来再交给 `CropScreen`。
 */
@Composable
private fun CropHost(
    index: Int,
    bucketId: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AlbumApplication).container
    val item by produceState<MediaItem?>(initialValue = null, index, bucketId) {
        value = container.repository
            .window(index, before = 0, after = 0, bucketId = bucketId, sortByName = bucketId != null)
            .items
            .firstOrNull()
    }
    val loaded = item
    if (loaded == null) {
        // 还没取到：先留一张空底（不能在这里调 onCancel —— 组合期副作用会把页面瞬间弹掉）；
        // 直接退出的时机交给下面"确实找不到"的分支。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        )
        return
    }
    CropScreen(item = loaded, onDone = onDone, onCancel = onCancel)
}
