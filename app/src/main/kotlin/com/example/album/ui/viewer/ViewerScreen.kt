package com.example.album.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.album.AlbumApplication
import com.example.album.R
import com.example.album.data.MediaItem
import com.example.album.data.MediaKind
import com.example.album.data.MediaWindow
import com.example.album.data.thumbnailRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 上下滑退出门槛：位移 ≥ 15% 高（9.9 同值） */
private const val DISMISS_THRESHOLD = 0.15f

/** chrome 显隐时长（9.9 `AnimationHelper.DURATION` = 300ms） */
private const val CHROME_MILLIS = 300

/** 视频页"缩略图 → 播放画面"的交叉淡出时长（用户 2026-10-24 报「点进视频 UI 加载生硬」） */
private const val VIDEO_PREVIEW_FADE_MILLIS = 220

/** 等播放层首帧的上限：超过就照样淡出（视频解不出来时不能一直盖着缩略图） */
private const val FIRST_FRAME_TIMEOUT_MILLIS = 1500L

/**
 * 查看器。
 *
 * 用户 2026-10-24 令：**进出动画直接抄 Glimpse** —— Glimpse 的查看器是普通 `startActivity`
 * （`ViewActivity.kt`，无任何自定义动画）⇒ 实际就是 framework 默认的 Activity 切换动画。
 * 这里由 Nav3 的 enter/exit 复刻同一套（见 `ui/common/PlatformTransition.kt`：
 * 96dp 横移 450ms + 83ms 淡入/淡出），**不再有**"从格位矩形放大到满屏 / 原路缩回"那一套。
 *
 * 同批（用户令）删除：「放到最大（1 列）后继续张 ⇒ 黑幕 + 松手进查看」整条机制，
 * 所以打开查看器只有"点格子"这一条路。
 *
 * 数据一次取"以被点下标为中心"的窗口（±100），翻页不重查。
 * **首帧不等解码**：窗口查询还没回来时先用点击带过来的 id/uri/宽高比画网格那张缩略图（同缓存键）。
 */
@Composable
fun ViewerScreen(
    index: Int,
    thumbPx: Int,
    onExit: () -> Unit,
    onCrop: (Int) -> Unit,
    bucketId: String? = null,
    /** 首帧种子（点击时带过来的）：窗口查询还没回来时先用它画预览，避免一进来是纯黑 */
    seedId: Long = -1L,
    seedUri: String = "",
    seedAspect: Float = 0f,
    /**
     * 用户**主动关闭**查看器（返回键 / 下滑关闭）时回调当前**已落定**那一张的**媒体 id**。
     * 只在用户动作里触发：配置变化 / 重组销毁 / 数据重查都不会调（避免误报）。
     *
     * ⚠️ 必须给 id 而不是页号：页号是"窗口内下标"，网格要的是扁平槽位下标（含 banner/日期头），
     * 换算过一次就错（r37 的 E15 就是这么失效的）。
     */
    onClosed: (mediaId: Long) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as AlbumApplication).container.repository }
    val scope = rememberCoroutineScope()

    val window by produceState<MediaWindow?>(initialValue = null, index, bucketId) {
        value = repository.window(index, bucketId = bucketId, sortByName = bucketId != null)
    }
    val data = window
    if (data == null) {
        // 数据还没回来：先用点击携带的种子画同一张缩略图（ContentScale.Fit = 正式页的等比铺满）
        BootPreview(seedId = seedId, seedUri = seedUri, thumbPx = thumbPx)
        return
    }

    // 优先按 **id** 落页：`index` 是"点击那一刻的分页下标"，任何一次刷新/插入都会让它指向另一张
    // （网格拿的是 `pagingItems` 的快照下标，查看器重新查窗口 ⇒ 两套下标可能错位）。
    // 用点击携带的 id 在窗口里找回来，找不到才退回下标。
    // 2026-10-24 用户报「点某些照片打开的是别的图」——这条兜底把"下标漂移"这一类原因整体堵掉。
    val byId = if (seedId > 0L) data.items.indexOfFirst { it.id == seedId } else -1
    val initialPage = if (byId >= 0) byId else
        (index - data.base).coerceIn(0, (data.items.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = initialPage) { data.items.size }

    var chromeVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var detailsItem by remember { mutableStateOf<MediaItem?>(null) }

    var dragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // 用户主动关闭（返回键 / 下滑）时把**已落定**的页下标交给调用方。
    // `currentPage` 是 settled page（拖动中不跳），符合"正在拖动时用 settled page 也行"。
    // 退出动画期间本页还挂在组合里（再次返回/滑动会重复进来）⇒ 只上报一次。
    var closedReported by remember { mutableStateOf(false) }
    val closeViewer: () -> Unit = {
        if (!closedReported) {
            closedReported = true
            data.items.getOrNull(pagerState.currentPage)?.let {
                onClosed(it.id)
            }
        }
        onExit()
    }
    // 返回键：NavDisplay 自己 pop 时拿不到 page，所以在查看器里先登记一层。
    // 详情面板是 Dialog 窗口，它自己的返回由 ModalBottomSheet 处理，这里让开（enabled=false）。
    BackHandler(enabled = detailsItem == null, onBack = closeViewer)

    // ⚠️ 2026-10-24 r44：视频画面改用 **TextureView**（见 `VideoPage` 的 `surfaceType`），
    // 它和普通 Compose 内容一样参与位移/alpha，所以以前"450ms 转场期间只画缩略图"的闸门整段删掉。
    // 用户录屏取证：那个闸门结束时会硬切到播放层，播放层出第一帧之前**整页黑约 380ms**
    // （842×1920@20.6fps，亮度 216 → 32 → 188，f41–f48），这就是"闪了一下 / UI 跳出"。

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 底色跟着深浅模式走（用户 2026-10-24：「图片查看页底色恒黑，应该是深/浅两套，
            // 浅色底要取色」）：深色下 `surface` 本身就是近黑，浅色下就是动态取色出来的浅底。
            // 以前写死 `Color.Black` ⇒ 浅色模式里整页纯黑，和系统栏/导航条也不协调。
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragY
                    // 跟手时轻微缩小、变淡（9.9 是"白化率"；这里用 alpha 表达同一意图）
                    val progress = (abs(dragY) / (size.height * 0.6f)).coerceIn(0f, 1f)
                    scaleX = 1f - 0.08f * progress
                    scaleY = 1f - 0.08f * progress
                    alpha = 1f - 0.5f * progress
                }
                .pointerInput(pagerState.currentPage) {
                    detectVerticalDragGestures(
                        onDragStart = { if (!zoomed) dragging = true },
                        onDragEnd = {
                            if (!dragging) return@detectVerticalDragGestures
                            dragging = false
                            val threshold = size.height * DISMISS_THRESHOLD
                            if (abs(dragY) > threshold) {
                                // 下滑关闭也算"用户主动关闭" ⇒ 回调 page
                                closeViewer()
                            } else {
                                scope.launch {
                                    animate(dragY, 0f, animationSpec = spring()) { value, _ -> dragY = value }
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch {
                                animate(dragY, 0f, animationSpec = spring()) { value, _ -> dragY = value }
                            }
                        },
                    ) { change, dragAmount ->
                        if (!dragging) return@detectVerticalDragGestures
                        change.consume()
                        dragY += dragAmount
                    }
                },
        ) {
            // 落定用 spring（学 Glimpse/ReFra：`snapAnimationSpec = spring(StiffnessMedium)`、阈值 0.3）：
            // 位置抖动少 ⇒ `targetPage` 不会在拖动中来回翻，也跟着少触发"全尺寸挂/摘"的切换
            val pagerFling = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(stiffness = Spring.StiffnessMedium),
                snapPositionalThreshold = 0.3f,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 放大时不要翻页（手势归缩放/平移）
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { page -> data.items[page].id },
                flingBehavior = pagerFling,
            ) { page ->
                val item = data.items[page]
                val onTap: () -> Unit = { chromeVisible = !chromeVisible }
                when (item.kind) {
                    MediaKind.Video -> {
                        // **只有落定的当前页才建 ExoPlayer**：`beyondViewportPageCount = 1` 会预组合相邻页，
                        // 以前邻居也各建一个 player（2–3 套解码器同时挂着，PSS 实测明显偏高）。
                        // Glimpse 的做法是可见性切换 + 复用（`MediaViewerAdapter.kt:78-95`），这里用
                        // "非当前页只画缩略图"达到同一效果：滑到别的页时上一页立刻掉 player。
                        val isCurrent = page == pagerState.currentPage
                        // 播放层有没有画出**第一帧**：在这之前一直让缩略图盖在上面
                        // （播放层出第一帧之前是黑的 —— 用户 2026-10-24 录屏里那 380ms 黑就是它）
                        var firstFrame by remember(item.id) { mutableStateOf(false) }
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isCurrent) {
                                VideoPage(
                                    item = item,
                                    onTap = onTap,
                                    modifier = Modifier.fillMaxSize(),
                                    // 视频自己的控制条跟外层 chrome 一起显隐，避免"控制条还在、返回键没了"
                                    chromeVisible = chromeVisible,
                                    onFirstFrameRendered = { firstFrame = true },
                                )
                            }
                            // 缩略图盖层：转场 / 未落定 / 等首帧时都在；首帧到了再**交叉淡出**（不是硬切）。
                            // 这样既没有黑帧，也不会"UI 短时间后整块跳出来"（控制条随页面一起滑入）。
                            val previewFade = remember(item.id) { Animatable(1f) }
                            var previewShown by remember(item.id) { mutableStateOf(true) }
                            LaunchedEffect(item.id, isCurrent) {
                                if (!isCurrent) {
                                    previewFade.snapTo(1f)
                                    previewShown = true
                                    return@LaunchedEffect
                                }
                                // 等首帧，最多等 1.5s（视频解不出来时别让缩略图永久盖住）
                                var waited = 0
                                while (!firstFrame && waited < FIRST_FRAME_TIMEOUT_MILLIS) {
                                    delay(50)
                                    waited += 50
                                }
                                // ⚠️ 2026-10-25（用户录屏「两条相邻视频切换会闪黑」）：
                                // 播放器的 `onRenderedFirstFrame` 只保证"这一帧渲进了输出 surface"，
                                // **不保证它已经被合成上屏**。页面还在滑动/回弹时 TextureView 还没上屏，
                                // 整块是不透明的黑 —— 此时就开始淡出缩略图，会露出约 300ms 纯黑
                                // （842×1920 录屏逐帧：亮度 216 → 16（87% 像素纯黑）→ 188，f36–f53）。
                                // 所以淡出前必须再等两件事：① 翻页真正落定；② 让出一帧给合成。
                                while (pagerState.isScrollInProgress) {
                                    delay(16)
                                }
                                withFrameNanos { }
                                previewFade.animateTo(0f, tween(VIDEO_PREVIEW_FADE_MILLIS))
                                previewShown = false
                            }
                            if (previewShown) {
                                VideoTransitionPreview(
                                    item = item,
                                    thumbPx = thumbPx,
                                    // α 只在 draw 阶段读：不要为每帧透明度过一次重组
                                    modifier = Modifier.graphicsLayer { alpha = previewFade.value },
                                )
                            }
                        }
                    }

                    MediaKind.Image -> ZoomableImage(
                        item = item,
                        thumbPx = thumbPx,
                        // 只给"目标页"解码全尺寸：翻页途中还是预览，落定后才替换
                        fullSizeRequested = page == pagerState.targetPage,
                        onTap = onTap,
                        onZoomedChange = { zoomed = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            AnimatedChrome(
                data = data,
                pagerState = pagerState,
                chromeVisible = chromeVisible,
                onCrop = onCrop,
                onDetails = { detailsItem = it },
            )
        }

        detailsItem?.let { target ->
            // 只读四项（尺寸 / 拍摄时间 / 文件大小 / 路径）
            DetailsSheet(item = target, onDismiss = { detailsItem = null })
        }
    }
}

/** 数据闸门期间的第一帧：网格那张缩略图（与网格共用 Coil 内存缓存键 ⇒ 命中即画）。 */
@Composable
private fun BootPreview(seedId: Long, seedUri: String, thumbPx: Int) {
    val context = LocalContext.current
    val request = remember(seedId, seedUri, thumbPx) {
        if (seedUri.isEmpty() || thumbPx <= 0) {
            null
        } else {
            thumbnailRequest(context, seedId, seedUri.toUri(), thumbPx)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 与正式查看器同底色（深浅两套），否则"数据回来"那一帧会从黑跳到浅
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 转场期间视频页的替代画面：网格那张缩略图（SurfaceView 不能参与转场变换）。 */
@Composable
private fun VideoTransitionPreview(item: MediaItem, thumbPx: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(item.id, thumbPx) { thumbnailRequest(context, item, thumbPx) }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        // 与 `ContentFrame` 的默认一致（Fit）：交叉淡出时画幅不跳
        contentScale = ContentScale.Fit,
    )
}

/** 顶栏返回 + 底部工具条（显隐与之前一致）。 */
@Composable
private fun AnimatedChrome(
    data: MediaWindow,
    pagerState: PagerState,
    chromeVisible: Boolean,
    onCrop: (Int) -> Unit,
    onDetails: (MediaItem) -> Unit,
) {
    // 进页面时**淡入**，不要"一进来就全都在"（用户 2026-10-24 报「点进视频 UI 加载生硬」）。
    // AnimatedVisibility 的 `visible` 初值就是 true 时不会播进场动画 ⇒ 先 false，下一帧再置 true。
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    AnimatedVisibility(
        visible = appeared && chromeVisible,
        enter = fadeIn(tween(CHROME_MILLIS)),
        exit = fadeOut(tween(CHROME_MILLIS)),
    ) {
        val item = data.items.getOrNull(pagerState.currentPage)
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部**没有返回按钮**（用户 2026-10-17 令：全部界面不要那种"灰底圆角矩形 + 返回箭头"的提示）；
            // 退出走系统返回（手势/返回键），查看器本身也不画任何顶栏。

            // 底部浮动工具条（已拍板）：图片 = 裁剪 + 详情；视频 = 详情。
            // 视频页底部还有播放器自己的控制条（约 56dp），所以工具条要抬到它上面，别叠在一起。
            val actionsBottomPadding = if (item?.kind == MediaKind.Video) 76.dp else 16.dp
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = actionsBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (item?.kind == MediaKind.Image) {
                    ViewerAction(
                        icon = {
                            Icon(
                                Icons.Filled.Crop,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                        label = stringResource(R.string.viewer_action_crop),
                        onClick = {
                            val absolute = data.base + pagerState.currentPage
                            onCrop(absolute)
                        },
                    )
                }
                ViewerAction(
                    icon = {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    },
                    label = stringResource(R.string.viewer_action_details),
                    onClick = { item?.let(onDetails) },
                )
            }
        }
    }
}

/**
 * 底部工具条的一个按钮：图标 + 一行小字。
 *
 * 用户 2026-10-24 令：「裁剪」「详情」按钮改成**取色胶囊**（视频查看页的那个也一样）。
 * 所以底色走主题容器角色 `secondaryContainer`（跟着动态取色/深浅模式走）、形状是真正的胶囊
 * （`CircleShape`，半径恒为短边一半），不用以前的 `scrim@0.6 + shapes.large` 半透明圆角块。
 * 文字/图标用配对的 `onSecondaryContainer` ⇒ 深浅两套底色上都够对比度，不再假设"后面一定是黑底"。
 */
@Composable
private fun ViewerAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClick) {
            icon()
            Text(
                text = label,
                modifier = Modifier.padding(start = 6.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
