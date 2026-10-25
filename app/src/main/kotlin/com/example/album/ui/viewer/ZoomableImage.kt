package com.example.album.ui.viewer

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.toSize
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import com.example.album.data.MediaItem
import com.example.album.data.thumbnailRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 小图双击目标倍数（原版 `ImageZoomMode.getMaxZoom()`：小图 = 2.0） */
private const val SMALL_IMAGE_DOUBLE_TAP_SCALE = 2f

/** 双击缩放时长（原版 300ms 定长曲线，无弹簧） */
private const val DOUBLE_TAP_MILLIS = 300

/**
 * 双指超 maxZoom 的软顶阻尼（E18）：越界部分只吃 30%。
 * 硬顶 12×maxZoom 时肉眼约 4.3×maxZoom（1 + 11 × 0.3）。
 */
private const val OVERSCROLL_DAMPING = 0.3f

/** 双指缩放的硬顶 = 12 × maxZoom（E18：不再往上） */
private const val OVERSCROLL_HARD_FACTOR = 12f

/** 松手从软顶回弹到 maxZoom 的时长（E18：500ms 定长 tween，原版无弹簧） */
private const val SNAP_BACK_MILLIS = 500

/** 平移惯性总时长（E18：约 1.0s 衰减） */
private const val FLING_MILLIS = 1000

/** 起滑速度阈值（px/s）：太小的抖动不做惯性 */
private const val FLING_MIN_VELOCITY = 80f

/** 预览缩略图 → 全尺寸的交叉淡化：短到看不出"跳一下"，也不让两张纹理同屏太久 */
private const val PREVIEW_FADE_MILLIS = 120

/**
 * 查看器一张图的缩放/平移状态机（E5 / E6 / E18 重做的唯一真源）。
 *
 * 坐标约定：手势 `pointerInput` 放在 `graphicsLayer` **外层**（见 [ZoomableImage]），
 * 因此 `calculatePan/calculateCentroid` 给的已经是**屏幕像素**，不会被 scale 反变换。
 * - [scale]：相对"图片按比例铺进屏幕"的倍数，绘制用；
 * - [offset]：图片中心相对屏幕中心的屏幕像素位移（正值 = 图片向右/向下走）；
 * - [rawScale]：手势累计的原始倍数，可以越过 maxZoom（软顶），但硬顶 12×maxZoom。
 */
private class ZoomState {
    var rawScale by mutableFloatStateOf(1f)
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var animationJob: Job? = null

    fun cancelAnimation() {
        animationJob?.cancel()
        animationJob = null
    }

    /**
     * 平移边界按**图片矩形**算，不按容器（E6 / `ImageZoomMode.java:256-274,319-342`）：
     * 显示矩形 = fit 矩形 × scale；只有图片比视口大的那个方向才允许平移，
     * 这样横图竖屏放大后不会把整张图拖出屏只剩黑。
     *
     * 另外：回到 ≤1.01 时 offset 归零（E18 的收尾条件）。
     */
    fun clampOffset(viewport: Size, fitted: Size) {
        if (scale <= 1.01f) {
            offset = Offset.Zero
            return
        }
        val maxX = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
        offset = Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY),
        )
    }

    /**
     * 双指缩放 + 平移。以两指中点为锚（E5 / `ImageZoomMode.java:404-405`），
     * 按 `newScale / oldScale` 比例修正 offset，指下内容才不会漂走：
     *
     * `offset' = k·offset + (1−k)·(centroid − 屏幕中心) + pan`
     *
     * 软顶用 [dampedScale]，因此这里的 k 用**显示倍数**算，而不是 rawScale。
     */
    fun pinch(
        zoomChange: Float,
        centroid: Offset,
        pan: Offset,
        maxZoom: Float,
        viewport: Size,
        fitted: Size,
    ) {
        val oldDisplayed = scale
        // 两指重合时 calculateZoom 可能给出 0/∞，直接丢掉这一帧，避免把 NaN 写进状态
        val safeZoom = if (zoomChange.isFinite() && zoomChange > 0f) zoomChange else 1f
        rawScale = (rawScale * safeZoom).coerceIn(1f, maxZoom * OVERSCROLL_HARD_FACTOR)
        val newDisplayed = dampedScale(rawScale, maxZoom)
        val k = if (oldDisplayed > 0f) newDisplayed / oldDisplayed else 1f
        val anchor = centroid - viewport.center
        scale = newDisplayed
        offset = offset * k + anchor * (1f - k) + pan
        clampOffset(viewport, fitted)
    }

    /** 放大状态下的单指平移（scale ≤ 1 时没有可平移的余量，直接忽略） */
    fun panBy(delta: Offset, viewport: Size, fitted: Size) {
        if (scale <= 1.01f) return
        offset += delta
        clampOffset(viewport, fitted)
    }
}

/** 图片按比例铺进屏幕后的矩形（与下方 `aspectRatio` 的测量结果一致） */
private fun fittedSize(viewport: Size, imageAspect: Float): Size {
    if (imageAspect <= 0f || viewport.width <= 0f || viewport.height <= 0f) return viewport
    val screenAspect = viewport.width / viewport.height
    return if (imageAspect >= screenAspect) {
        // 图片比屏幕"更宽/相当"：宽铺满，高按比例
        Size(viewport.width, viewport.width / imageAspect)
    } else {
        // 图片比屏幕"更竖"：高铺满，宽按比例（超长图时 fit 宽 < 屏宽）
        Size(viewport.height * imageAspect, viewport.height)
    }
}

/**
 * 双击 / 双击上限 = 原版 `getMaxZoom()`（E18）：
 * 大图按 **1:1 像素比**（scale = 图片像素宽 / fit 后显示宽），小图按 2.0。
 * 用 fit 后的显示宽（而不是直接拿屏宽）才对超长竖图也是真正的 1:1。
 */
private fun maxZoomFor(imageWidthPx: Int, fitted: Size): Float {
    if (imageWidthPx <= 0 || fitted.width <= 0f) return SMALL_IMAGE_DOUBLE_TAP_SCALE
    val oneToOne = imageWidthPx / fitted.width
    return if (oneToOne > 1f) oneToOne else SMALL_IMAGE_DOUBLE_TAP_SCALE
}

/** 软顶：超过 maxZoom 的部分按 [OVERSCROLL_DAMPING] 吃阻尼（单调递增，不会缩放倒挂） */
private fun dampedScale(rawScale: Float, maxZoom: Float): Float =
    if (rawScale <= maxZoom) {
        rawScale.coerceAtLeast(1f)
    } else {
        maxZoom + (rawScale - maxZoom) * OVERSCROLL_DAMPING
    }

/**
 * 可缩放的整页图片：**先出图再看清**，同时是「共享元素」的一端。
 *
 * 结构（从外到内）：
 * 1. 外层 `BoxWithConstraints(fillMaxSize)`：手势 + 缩放/平移变换；
 * 2. 中间那层 Box 的尺寸 = **图片按比例铺进屏幕后的矩形**（`aspectRatio`）。
 *    关键几何：这个矩形的宽高比 = 图片宽高比，所以里面的 `ContentScale.Crop` 与 `Fit` 等价 ——
 *    7.0 式转场在"格位矩形 → 这个矩形"之间插值时，不会出现"裁切 ↔ 留黑"的跳变；
 * 3. 内容两层：贴底是网格那张缩略图（与网格同一 Coil 内存缓存键 ⇒ 命中即画，不等解码），
 *    上层是全尺寸（只在调用方认为"这是目标页"时请求），到货 120ms 淡入，铺满后撤掉预览层。
 *
 * 手势（2026-10-24 E5/E6/E18 按原版重做，全部是定长 tween、**没有弹簧**）：
 * - 双指缩放以两指中点为锚（`ImageZoomMode.java:404-405`），平移边界按图片显示矩形
 *   （`:256-274,319-342`），不再用容器宽高；
 * - 双击锚到点击点、300ms；大图到 1:1 像素比、小图到 2.0；
 * - 双指软顶到约 4.3×maxZoom（越界部分 0.3 阻尼、硬顶 12×maxZoom），松手 500ms 回弹；
 * - 放大状态松手有约 1.0s 的平移惯性，逐帧 clamp 到边界。
 *
 * ⚠️ `pointerInput` 必须在 `graphicsLayer` **外层**：Compose 会把指针坐标反变换进
 * `graphicsLayer` 内部，那样 scale 越大 pan 越慢、锚点也会算错。放外层后坐标恒为屏幕像素。
 *
 * 全尺寸图**不进内存缓存**（用户令：回到 0.3.0 策略，不还原 9.9 的 prev/current/next 三张常驻）；
 * 一旦这一页不再是目标页，整层从组合里移除，位图随之释放。
 *
 * 进入/返回动画不在这里：查看器把整个页面按 7.0 的路径矩形摆放（`ViewerScreen.viewerRect`），
 * 这里只负责"缩略图先出图 + 全尺寸后到替换"和双击/双指缩放。
 */
@Composable
fun ZoomableImage(
    item: MediaItem,
    thumbPx: Int,
    fullSizeRequested: Boolean,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val containerSize = LocalWindowInfo.current.containerSize
    val scope = rememberCoroutineScope()

    // 所有手势 / 动画都只改这一份状态（E5/E6/E18）
    val zoom = remember(item.id) { ZoomState() }

    // 全尺寸到货前一直画预览；到货后淡入
    var fullLoaded by remember(item.id) { mutableStateOf(false) }
    val fullAlpha = animateFloatAsState(
        targetValue = if (fullLoaded) 1f else 0f,
        animationSpec = tween(PREVIEW_FADE_MILLIS),
    )

    // ⚠️ 预览层**常驻**（2026-10-24 r34，用户报「滑动切图闪一下」）：
    // 以前全尺寸到货后把预览层摘掉，而全尺寸层是按 `fullSizeRequested`（page == targetPage）条件挂载的
    // ⇒ 滑动中 targetPage 一翻，旧页的全尺寸层被摘、预览层又还没回挂 ⇒ 那 1–2 帧露出纯黑底
    // （黑块在半个屏幕上，人眼极敏感）。现在预览层永远在最底下当底板：上层怎么变都不会露黑。
    // 代价只有一次额外的全屏纹理采样（缩略图本来就在内存缓存里）。

    LaunchedEffect(item.id) {
        snapshotFlow { zoom.scale > 1.01f }
            .distinctUntilChanged()
            .collect(onZoomedChange)
    }

    // 预览层：网格那张缩略图，同一个内存缓存键 ⇒ 命中即画
    val previewRequest = remember(item.id, thumbPx) {
        thumbnailRequest(context, item, thumbPx)
    }

    // 全尺寸层：屏幕尺寸采样解码（不对全尺寸图分配 ARGB）；禁内存缓存
    val fullRequest = remember(item.id, containerSize) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .size(CoilSize(containerSize.width, containerSize.height))
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
    }

    // 位图状态（方案 B 同款：官方 `ImageLoader.enqueue` + 一个 listener，不用 `AsyncImage`）
    val loader = remember { SingletonImageLoader.get(context) }
    var previewBitmap by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(previewRequest) {
        val d = loader.enqueue(
            previewRequest.newBuilder()
                .listener(onSuccess = { _, result ->
                    val b = (result.image as? BitmapImage)?.bitmap
                    if (b != null) previewBitmap = b.asImageBitmap()
                })
                .build(),
        )
        onDispose { d.dispose() }
    }
    var fullBitmap by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(fullRequest, fullSizeRequested) {
        if (!fullSizeRequested) {
            onDispose { }
        } else {
            val d = loader.enqueue(
                fullRequest.newBuilder()
                    .listener(onSuccess = { _, result ->
                        val b = (result.image as? BitmapImage)?.bitmap
                        if (b != null) {
                            fullBitmap = b.asImageBitmap()
                            // α 粘滞：到过货就一直画（不要因一次 Loading 淡回 0）
                            fullLoaded = true
                        }
                    })
                    .build(),
            )
            onDispose { d.dispose() }
        }
    }

    val imageAspect = if (item.widthPx > 0 && item.heightPx > 0) {
        item.widthPx.toFloat() / item.heightPx.toFloat()
    } else {
        0f
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 双击：锚到点击点缩放；目标 = 原版 getMaxZoom（大图 1:1、小图 2.0），300ms tween
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        val viewport = size.toSize()
                        val fitted = fittedSize(viewport, imageAspect)
                        val maxZoom = maxZoomFor(item.widthPx, fitted)
                        zoom.cancelAnimation()
                        val startScale = zoom.scale
                        val startOffset = zoom.offset
                        // 已放大 ⇒ 双击退回 fit；否则放到 maxZoom（E18 的 getMaxZoom）
                        val targetScale = if (startScale > 1.01f) 1f else maxZoom
                        zoom.animationJob = scope.launch {
                            if (targetScale <= 1.01f) {
                                // 缩回 fit：offset 同步淡回 0（≤1.01 归零）
                                animate(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = tween(DOUBLE_TAP_MILLIS, easing = FastOutSlowInEasing),
                                ) { progress, _ ->
                                    zoom.scale = startScale + (1f - startScale) * progress
                                    zoom.rawScale = zoom.scale
                                    zoom.offset = Offset(
                                        startOffset.x * (1f - progress),
                                        startOffset.y * (1f - progress),
                                    )
                                    zoom.clampOffset(viewport, fitted)
                                }
                                zoom.scale = 1f
                                zoom.rawScale = 1f
                                zoom.offset = Offset.Zero
                            } else {
                                // 锚点 = 点击位置相对屏幕中心的位移；逐帧按 k 保持指下内容不动
                                val anchor = tap - viewport.center
                                animate(
                                    initialValue = startScale,
                                    targetValue = targetScale,
                                    animationSpec = tween(DOUBLE_TAP_MILLIS, easing = FastOutSlowInEasing),
                                ) { value, _ ->
                                    val k = value / startScale
                                    zoom.scale = value
                                    zoom.rawScale = value
                                    zoom.offset = startOffset * k + anchor * (1f - k)
                                    zoom.clampOffset(viewport, fitted)
                                }
                                // 极端小图/1:1 比值 ≤1.01 时不做"放大 0.1%"这种无意义状态
                                if (zoom.scale <= 1.01f) {
                                    zoom.scale = 1f
                                    zoom.rawScale = 1f
                                    zoom.offset = Offset.Zero
                                }
                            }
                        }
                    },
                )
            }
            // 双指缩放 + 平移 + 松手回弹/惯性（E5/E6/E18）
            .pointerInput(item.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    zoom.cancelAnimation()
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                    var multiTouch = false
                    var anyPressed = true
                    while (anyPressed) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) multiTouch = true
                        // 手指全部抬起的那一帧没有可用的质心（Unspecified/NaN），不能进缩放/平移
                        if (pressed > 0) {
                            velocityTracker.addPosition(
                                event.changes.maxOf { it.uptimeMillis },
                                event.calculateCentroid(useCurrent = true),
                            )
                            if (multiTouch || zoom.scale > 1.01f) {
                                val viewport = size.toSize()
                                val fitted = fittedSize(viewport, imageAspect)
                                val maxZoom = maxZoomFor(item.widthPx, fitted)
                                val pan = event.calculatePan()
                                if (multiTouch) {
                                    // 锚点用**上一帧**质心（和 calculateZoom 的区间对齐），pan 再单独叠加
                                    zoom.pinch(
                                        zoomChange = event.calculateZoom(),
                                        centroid = event.calculateCentroid(useCurrent = false),
                                        pan = pan,
                                        maxZoom = maxZoom,
                                        viewport = viewport,
                                        fitted = fitted,
                                    )
                                } else {
                                    zoom.panBy(pan, viewport, fitted)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                        anyPressed = event.changes.any { it.pressed }
                    }

                    val viewport = size.toSize()
                    val fitted = fittedSize(viewport, imageAspect)
                    val maxZoom = maxZoomFor(item.widthPx, fitted)
                    if (zoom.rawScale > maxZoom) {
                        // 软顶松手：500ms tween 回弹到 maxZoom（原版定长曲线，无弹簧）
                        val from = zoom.rawScale
                        zoom.animationJob = scope.launch {
                            animate(
                                initialValue = from,
                                targetValue = maxZoom,
                                animationSpec = tween(SNAP_BACK_MILLIS, easing = FastOutSlowInEasing),
                            ) { value, _ ->
                                zoom.rawScale = value
                                zoom.scale = dampedScale(value, maxZoom)
                                zoom.clampOffset(viewport, fitted)
                            }
                            zoom.rawScale = maxZoom
                            zoom.scale = maxZoom
                            zoom.clampOffset(viewport, fitted)
                        }
                    } else if (!multiTouch && zoom.scale > 1.01f) {
                        // 放大状态下的单指平移惯性：约 1.0s，速度线性衰减到 0，逐帧 clamp（撞边即停）
                        val velocity = velocityTracker.calculateVelocity()
                        val velocityOffset = Offset(velocity.x, velocity.y)
                        if (velocityOffset.getDistance() >= FLING_MIN_VELOCITY) {
                            val start = zoom.offset
                            zoom.animationJob = scope.launch {
                                animate(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = tween(FLING_MILLIS, easing = LinearEasing),
                                ) { progress, _ ->
                                    // 累计位移 = v0·T·(p − p²/2)，p=1 时 = v0·T/2（速度恰好归零）
                                    val travel = (progress - progress * progress / 2f) * (FLING_MILLIS / 1000f)
                                    zoom.offset = start + velocityOffset * travel
                                    zoom.clampOffset(viewport, fitted)
                                }
                            }
                        }
                    }
                }
            }
            // ⚠️ 必须放在两个 pointerInput 之后（= 更内层）：手势坐标才是屏幕像素
            .graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        val screenAspect = constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat()
        val fitted = if (imageAspect > 0f) {
            Modifier.aspectRatio(imageAspect, matchHeightConstraintsFirst = imageAspect < screenAspect)
        } else {
            Modifier.fillMaxSize()
        }
        Box(modifier = fitted) {
            // 两层都用 Coil 的官方底层接口（`enqueue` + listener），不用 `AsyncImage`：
            // 后者每张图都要建一套 painter 状态机；查看器一进来 3 页 × 两层 = 6 套，
            // 实测"页面第一次组合"要 120ms 左右（perfetto，见 `docs/PERF.md`）。
            previewBitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // 与网格格子一致的 Crop：所在矩形宽高比 = 图片宽高比，所以等价于 Fit，转场不跳
                    contentScale = ContentScale.Crop,
                )
            }
            val fullBmp = fullBitmap
            if (fullSizeRequested && fullBmp != null) {
                Image(
                    bitmap = fullBmp,
                    contentDescription = null,
                    // alpha 在绘制阶段读，淡入的 120ms 不触发重组
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fullAlpha.value },
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}
