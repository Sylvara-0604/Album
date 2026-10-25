package com.example.album.ui.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.album.AlbumApplication
import com.example.album.R
import com.example.album.data.BannerInterval
import com.example.album.data.MediaItem
import com.example.album.ui.common.PageId
import com.example.album.ui.common.SelectionState
import com.example.album.ui.common.rememberCreateFolderAction
import com.example.album.ui.common.rememberSelectionActions
import com.example.album.ui.components.Banner
import com.example.album.ui.components.BottomCapsule
import com.example.album.ui.components.EmptyState
import com.example.album.ui.components.MediaGrid
import com.example.album.ui.components.BannerAssets
import com.example.album.ui.components.rememberBannerAssets
import com.example.album.ui.components.rememberBannerMetrics
import com.example.album.ui.components.rememberBannerVisible
import com.example.album.ui.components.FractionalGridState
import com.example.album.ui.components.rememberFractionalGridState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 时间线（栈底）。
 *
 * **没有顶栏、没有抽屉**（用户 2026-10-17 令）：banner 与网格都从屏幕最顶上开始（沉浸式），
 * 唯一导航入口是底部的浮动胶囊（文件夹 / 设置，只在首页出现）。
 *
 * **没有下拉刷新**（用户 2026-10-17 令：去除首页下拉刷新）：顶部往下拉只做 banner 的
 * `OVERSCROLL_FACTOR = 0.3` 放大与 spring 回位，不触发任何刷新。
 *
 * @param onSelectMore 非空 = 当前是 Android 14 的"部分授权"，右上角给一个「选择更多」小胶囊
 * @param onOpen 点某一格：绝对下标 + 缩略图像素边长 + **那一项本身** + 它在窗口里的矩形
 *   （查看器第一帧要立刻画出预览：id/uri/宽高比必须在点击时就带过去，不能等数据库查询回来）
 */
@Composable
fun TimelineScreen(
    onSelectMore: (() -> Unit)?,
    onOpenFolders: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (Int, Int, MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 首页是不是"当前这一页"（压栈到设置/文件夹、或查看器覆盖层打开时为 false ⇒ 胶囊按 Material 动画收起）。
     *
     * ⚠️ 2026-10-24 r47：**必须是 `() -> Boolean` 延迟读取，不能是 Boolean**。
     * 传值的话，"打开查看器"这一下就把整个时间线（含 51 个格子）标脏 ⇒ 单帧 130ms 全是重组
     * （`docs/PERF.md` v0.5.15 的 53 个 scope）。现在只有 [BannerAutoRotate] 与 [HomeCapsule]
     * 这两个小组合各自读它，重组范围就限定在它们自己身上。
     */
    active: () -> Boolean = { true },
    /** 从查看器返回时要"居中到刚才看的那张"（9.9 `ScrollResumeInfo`，审计 E15）；null = 不处理 */
    centerOnMediaId: Long? = null,
    /** 网格状态由 `AlbumNav` 持有（页面重建不丢滚动量；见那里的注释） */
    state: FractionalGridState = rememberFractionalGridState(),
    /**
     * banner 的位图缓存 + 淡入进度，**由宿主（`AlbumNav`）持有**。
     *
     * ⚠️ 2026-10-24 修复（用户报「从视频/图片查看页返回之后 banner 会闪一下」）：它以前是页面里的
     * `remember` ⇒ 压栈打开查看器时首页离开组合，这份缓存连同 `settledVersion` 一起被丢掉；
     * 返回时首页重建 ⇒ 位图为空 + 淡入进度从 0 起 ⇒ **banner 先空白几帧再淡入**，就是那一闪。
     * 提到宿主后：位图还在、`settledVersion == version` ⇒ 首帧直接满不透明画出来，不重播淡入。
     */
    bannerAssets: BannerAssets = rememberBannerAssets(),
) {
    val viewModel = rememberTimelineViewModel()
    val pagingItems = viewModel.pagedMedia.collectAsLazyPagingItems()
    val bannerState by viewModel.banner.collectAsStateWithLifecycle()
    val columns = viewModel.columns

    val gridState = state
    val bannerMetrics = rememberBannerMetrics()
    // banner 换图间隔（设置页可选：默认 5s/15s、5s、10s、1min）
    val context = LocalContext.current
    val settings = remember(context) { (context.applicationContext as AlbumApplication).container.settings }
    val bannerInterval by settings.bannerInterval.collectAsStateWithLifecycle()

    // 长按选中（9.9 MULTIPLE）：状态放在屏幕层，选择顶栏与网格读同一份
    val selection = remember { SelectionState() }
    val shareOrDelete = rememberSelectionActions(selection)
    // 多选态胶囊的「+」：弹命名框 → 系统写权限确认 → 移到 /sdcard/Pictures/<名字>（零新权限）
    // 写完库用 ViewModel 的统一失效入口：分页源 invalidate + banner 池作废（比 pagingItems.refresh() 多覆盖 banner）
    val createFolder = rememberCreateFolderAction(selection) { viewModel.invalidateAfterWrite() }

    // 胶囊随滚动收起/出现：**只按方向**判断（内容往下滚 = 收起；往回滚 = 出现），
    // 到列表顶部一定出现。与 c001apk-kmp `TrackListScroll` 同一套判据，只是数据源换成自绘网格的滚动量。
    // ⚠️ 捏合期间不判（`pinching`）：那时滚动量由锚点每帧反算，手指的几像素抖动会不停翻转方向
    // ⇒ 胶囊跟着抖/闪（2026-10-24 用户报「缩放页面抖动」的第二个来源）。
    var capsuleHidden by remember { mutableStateOf(false) }
    val capsuleHideThresholdPx = with(LocalDensity.current) { CAPSULE_HIDE_THRESHOLD.toPx() }
    LaunchedEffect(gridState) {
        var last = -1f
        snapshotFlow { gridState.scrollOffsetPx to gridState.pinching }.collect { (offset, pinching) ->
            if (pinching || offset == last) {
                last = offset
                return@collect
            }
            if (last >= 0f) {
                capsuleHidden = offset > last && offset > capsuleHideThresholdPx
            }
            last = offset
        }
    }

    // 没有顶栏：内容 (banner) 从 y=0 起（网格是一整段连续流，没有日期头要避开状态栏）
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // 回到首页就换一张 banner（冷启动 = 首张；之后 = 强制换，都走交叉淡化）
    // 观察者常驻（见 `TimelineViewModel.init`）：这里只负责"前台/后台"这一个状态，
    // 后台期间库变更只记账、回前台统一失效一次（用户 2026-10-24 报 E1 连刷两次）。
    LifecycleResumeEffect(Unit) {
        viewModel.onScreenResumed()
        onPauseOrDispose { viewModel.onScreenPaused() }
    }

    BackHandler(enabled = selection.active) { selection.clear() }

    val bannerVisible = rememberBannerVisible(gridState, bannerMetrics.drawHeightPx.toFloat())
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // 定时轮换：app 不在前台时整个循环挂起（repeatOnLifecycle），不会在后台偷偷解码。
    // 用户 2026-10-24 令：banner 只在 **停留在首页** 时按时轮换；切页面再回来不再强制换图。
    // ⚠️ `active` 要进 key：压栈到查看器/文件夹/设置时这轮循环要停，否则在查看器里待 5 秒
    // banner 就换了一张，返回时首页要多播一次 1000ms 交叉淡化（看着像"闪一下"）。
    // ⇒ 读 `active` 这一步交给 `BannerAutoRotate` 内部（本函数不读，避免整层重组）。
    BannerAutoRotate(
        viewModel = viewModel,
        bannerVisible = bannerVisible,
        bannerInterval = bannerInterval,
        lifecycle = lifecycle,
        active = active,
    )

    // 页面**不铺底色**：全 App 共用一层 `surface`（见 `AlbumNav`）⇒ 换页时背景恒定，
    // 新旧两页的内容才能在同一层背景上交叉淡化（否则两页各白一次 = "白闪"）。
    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            val refresh = pagingItems.loadState.refresh
            when {
                // 首次加载：留白，不闪"还没有照片"
                pagingItems.itemCount == 0 && refresh is LoadState.Loading -> Unit

                pagingItems.itemCount == 0 && refresh is LoadState.Error -> LoadFailed(
                    onRetry = pagingItems::refresh,
                    contentTop = statusBarTop,
                )

                // 只有 Paging 明确"加载完了而且真的没有数据"才算空库。
                // 之前这里只看 itemCount == 0 ⇒ 从文件夹列表返回、Paging 重建那一瞬间会闪出
                // "还没有照片"（2026-10-17 用户录屏抓到的就是这个）。
                pagingItems.itemCount == 0 -> if (
                    (refresh as? LoadState.NotLoading)?.endOfPaginationReached == true
                ) {
                    EmptyState(modifier = Modifier.padding(top = statusBarTop))
                }

                else -> MediaGrid(
                    pagingItems = pagingItems,
                    columns = columns,
                    pageId = PageId.TIMELINE,
                    onColumnsChange = viewModel::changeColumns,
                    onOpen = onOpen,
                    state = gridState,
                    centerOnMediaId = centerOnMediaId,
                    selection = selection,
                    banner = {
                        Banner(
                            state = bannerState,
                            metrics = bannerMetrics,
                            scrollOffsetPx = { gridState.scrollOffsetPx },
                            onCrossfadeFinished = {
                                viewModel.onBannerCrossfadeFinished(bannerState.version)
                            },
                            assets = bannerAssets,
                        )
                    },
                )
            }
        }

        // Android 14「仅选中的照片」：置顶栏被删掉后，这个入口收成右上角一个小胶囊（只有该状态才出现）
        if (onSelectMore != null) {
            TextButton(
                onClick = onSelectMore,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = statusBarTop + 8.dp, end = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            ) {
                Text(text = stringResource(R.string.action_select_more))
            }
        }

        // 原版首页"状态栏下的阴影"= 9.9 `SystemUiVisibilityManager.createGradientColor()` 的那段渐变：
        // `bar_layout`（状态栏 + 一个动作栏高）上 #7F000000 → 80% 处 #19000000 → 透明（300ms 淡入）。
        // 它是**固定覆盖层**，内容从它下面穿过去。
        HomeTopScrim(statusBarTop = statusBarTop, modifier = Modifier.align(Alignment.TopCenter))

        // 选择模式的「× + 已选 N 项 + 分享/删除」**放在底部胶囊里**（用户 2026-10-17 令）：
        // 不再从顶部弹出一条横栏，改为同一颗药丸横向拉长、退出时 spring 收回。

        // 底部浮动胶囊：悬浮在内容之上（不给列表留白）。平时是文件夹/设置，多选态横向拉长。
        //
        // 用户 2026-10-24 令（第 9 条）：
        // ① **首页内下滑收起、上滑出现**（动画照 c001apk-kmp 的 `AppPillBar`：`slideIn/OutVertically` +
        //    `fadeIn/Out`，这里换成 M3 Expressive 的 motion scheme 规格）；
        // ② 查看器 / 设置 / 文件夹页面**没有胶囊** —— 从首页离开时它按同一套 Material 动画收起（`active=false`）；
        // ③ 多选态下不收起（正在操作它）。
        // ⇒ 「读 active」这件事交给 `HomeCapsule` 内部（本函数不读，避免整层重组）。
        HomeCapsule(
            active = active,
            capsuleHidden = capsuleHidden,
            selection = selection,
            onOpenFolders = onOpenFolders,
            onOpenSettings = onOpenSettings,
            onShare = shareOrDelete.share,
            onDelete = shareOrDelete.delete,
            onCreateFolder = createFolder.start,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * banner 定时轮换（单独成一个组合：`active` 的变化只重组这一小块，不会波及 51 个格子）。
 * 判定用 `derivedStateOf` 包一层 ⇒ 就算 `active` 的 lambda 每次读到同一个值，也不会触发重组。
 */
@Composable
private fun BannerAutoRotate(
    viewModel: TimelineViewModel,
    bannerVisible: Boolean,
    bannerInterval: BannerInterval,
    lifecycle: Lifecycle,
    active: () -> Boolean,
) {
    val activeNow by remember(active) { derivedStateOf { active() } }
    LaunchedEffect(bannerVisible, lifecycle, bannerInterval, activeNow) {
        if (!activeNow) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(if (bannerVisible) bannerInterval.visibleMillis else bannerInterval.hiddenMillis)
                viewModel.advanceBanner()
            }
        }
    }
}

/** 底部胶囊（单独成一个组合：`active` / 滚动方向的显隐只重组这一小块）。 */
@Composable
private fun HomeCapsule(
    active: () -> Boolean,
    capsuleHidden: Boolean,
    selection: SelectionState,
    onOpenFolders: () -> Unit,
    onOpenSettings: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeNow by remember(active) { derivedStateOf { active() } }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = activeNow && (!capsuleHidden || selection.active),
        enter = slideInVertically(animationSpec = motion.defaultSpatialSpec()) { it } +
            fadeIn(animationSpec = motion.defaultEffectsSpec()),
        exit = slideOutVertically(animationSpec = motion.defaultSpatialSpec()) { it } +
            fadeOut(animationSpec = motion.defaultEffectsSpec()),
        modifier = modifier,
    ) {
        BottomCapsule(
            onOpenFolders = onOpenFolders,
            onOpenSettings = onOpenSettings,
            selection = selection,
            onShare = onShare,
            onDelete = onDelete,
            onCreateFolder = onCreateFolder,
        )
    }
}

/**
 * 首页顶部渐变（用户 2026-10-17：「原版首页状态栏下有阴影」）。
 *
 * 取值全部来自 9.9 `SystemUiVisibilityManager`：
 * - 渐变：`createGradientColor()` → `#7F000000`（0%）→ `#19000000`（80%）→ `#00000000`（100%）；
 * - 范围：`bar_layout` = 状态栏高 + **一个动作栏高**（`?actionBarSize`，系统真值，不写死）。
 *
 * 只画不接手势：内容照常从它下面穿过。
 */
@Composable
private fun HomeTopScrim(statusBarTop: Dp, modifier: Modifier = Modifier) {
    val actionBarHeight = rememberActionBarSize()
    // 启动时渐变 300ms 淡入（9.9 `SystemUiVisibilityManager` 的 300ms alpha），
    // 高度 = 状态栏 + 动作栏 + 20dp 余量（`action_bar_shadow_margin`，审计 E20）
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(300)) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarTop + actionBarHeight + 20.dp)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color(0x7F000000),
                        0.8f to Color(0x19000000),
                        1f to Color(0x00000000),
                    ),
                    alpha = fade.value,
                )
            },
    )
}

/**
 * 胶囊"下滑收起"的位移阈值：小于它（刚起手抖一下）不收起。
 *
 * ⚠️ 2026-10-24 r48：原来写 `8f`（**像素**）⇒ 只对 2.75 密度这台设备合适，1x 屏上等于 8dp（太粘）、
 * 4x 屏上等于 2dp（一碰就收）。改成 dp，任何密度下都是同一个手指行程。
 * 本机换算：3dp ≈ 8.25px（原值 8px，差 0.25px，肉眼无差）。
 */
private val CAPSULE_HIDE_THRESHOLD = 3.dp
/** 系统真值 `?actionBarSize`（9.9 的 `bar_layout` = 状态栏 + 一个动作栏高）。 */
@Composable
private fun rememberActionBarSize(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        val px = try {
            attrs.getDimension(0, with(density) { 56.dp.toPx() })
        } finally {
            attrs.recycle()
        }
        with(density) { px.toDp() }
    }
}

@Composable
private fun LoadFailed(onRetry: () -> Unit, contentTop: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentTop)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.grid_load_failed),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun rememberTimelineViewModel(): TimelineViewModel {
    val container = (LocalContext.current.applicationContext as AlbumApplication).container
    val factory = remember(container) {
        viewModelFactory {
            initializer { TimelineViewModel(container.repository, container.gridColumns) }
        }
    }
    return viewModel(factory = factory)
}
