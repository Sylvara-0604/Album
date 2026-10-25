package com.example.album.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.example.album.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * banner 当前展示的那一张。
 *
 * 用**原图 uri**（不是系统缩略图）：banner 是 1080dp 宽的大图，系统缩略图的档位远不够，
 * 放大后明显发糊 —— 9.9 的 banner 也是直接拿原图渲染。Coil 会按 `size` 采样解码，不会解全尺寸 ARGB。
 *
 * [dateMillis] 给左下角那行说明文字用（9.9 `BannerInfoTextTranslator`：默认就是拍摄日期，今天则显示「今天」）。
 */
@Immutable
data class BannerSlide(val id: Long, val uri: Uri, val dateMillis: Long = 0L)

/**
 * banner 的展示状态。
 *
 * `previous` 只在交叉淡化期间存在（淡完由 UI 回调清掉），`next` 是**下一张预取的目标** ——
 * 提前解码好，换图时不会有空帧。
 */
@Immutable
data class BannerState(
    val current: BannerSlide? = null,
    val previous: BannerSlide? = null,
    val next: BannerSlide? = null,
    val version: Int = 0,
)

/**
 * banner 的高度（9.9 `LayoutSettings`：屏幕短边 × 0.5625）。
 *
 * 自绘网格要求"块高可解析"，所以网格与 banner 必须用**同一份**数值：
 * `drawHeightPx` = 图片实际画多高；`slotHeightPx` = 它在网格里占多高（少 2dp，被下面的内容压住）。
 */
@Immutable
data class BannerMetrics(
    val drawHeightPx: Int,
    val slotHeightPx: Int,
    val overlapPx: Int,
)

/** 9.9 `grid_banner_overlap_size = -2dp`：网格压住 banner 底边 2dp */
private val GRID_OVERLAP = 2.dp

/** 9.9 `banner_extra_width = 1dp` */
private val EXTRA_WIDTH = 1.dp

/** 9.9 `UiDrawableFactory.PARALLAX_FACTOR_VS_GRID = -0.6`：内容反向平移 60% 滚动量 */
private const val PARALLAX_FACTOR = 0.6f

/** 9.9 `UiDrawableFactory.PARALLAX_INFO_FACTOR_VS_GRID = -0.75` */
private const val PARALLAX_TEXT_FACTOR = 0.7f

/** 9.9 `LayoutSettings`：banner 高 = 屏幕短边 × 0.5625（16:9） */
private const val BANNER_HEIGHT_RATIO = 0.5625f

/** banner 的度量值（网格与 banner 共用，保证块高与图片实际高度一致）。 */
@Composable
fun rememberBannerMetrics(): BannerMetrics {
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(windowSize, density) {
        val fullPx = (min(windowSize.width, windowSize.height) * BANNER_HEIGHT_RATIO).toInt()
        val overlap = with(density) { GRID_OVERLAP.roundToPx() }
        BannerMetrics(
            drawHeightPx = fullPx,
            slotHeightPx = (fullPx - overlap).coerceAtLeast(0),
            overlapPx = overlap,
        )
    }
}

/**
 * ⚠️ **banner 的下拉效果已整块删除**（用户 2026-10-17 令：「删掉 banner 下拉一切效果，改成 Android 标准回弹」）。
 *
 * 之前这里有 `BannerOverscrollState`（9.9 式 `scale = 1 + 0.3×overscroll`、`0.5·√stretch`、spring 1312 回位），
 * 现在顶部的越界**完全交给平台的 `rememberOverscrollEffect()`**（stretch），与底部一致：
 * banner 不缩放、不跟手、不自己弹回；网格也不再为 banner 拦顶部越界。
 */

/** 换图交叉淡化时长：9.9 `BannerTransition` 的 fadeIn/fadeOut = 1000ms */
private const val CROSSFADE_MILLIS = 1000

/** 只有新图、没有旧图可淡出时：9.9 `setupSingleTransition` 的 fadeIn = 100ms */
private const val SINGLE_FADE_IN_MILLIS = 100

/**
 * 换图空帧探针（诊断用，**确认无背景透出帧后置回 false**）。
 *
 * 打开后 `logcat -s BannerProbe` 能看到每次换图里"合成覆盖率 < 1"的帧 —— 只要没有这种帧，
 * 就不可能出现底色/白闪。
 */
private const val DEBUG_BANNER_FRAMES = true

private const val TAG_PROBE = "BannerProbe"

/**
 * 首页顶部那块随机大图（蓝图 §6.2.1）。
 *
 * **按 9.9 `BannerUiItem` + `BannerTransition` 的真机制重写**（2026-10-17 用户令）：
 * - 图层 = **图片 + 左下角一行说明文字**（9.9 `createBannerDrawable` 里 IMAGE + TEXT 两层）；
 * - 静态**没有**底部渐隐：9.9 的 `FadeBottomImageEffect` 用
 *   `fadeStart = (topToBannerBottom − overlap×ratio)/topToBannerBottom`、
 *   `grid_banner_fade_end = 1.0`、`grid_banner_fade_start_ratio_banner_overlap = 0.0`
 *   ⇒ `smoothstep(1,1,x)` 只在最后一行像素生效 = 不可见；
 * - 视差**分层**：图片 `-0.6`（净 0.4×）、文字 `-0.7`（净 0.3×）；
 * - 滚动淡出：`alpha = 1 − scrollPos / topToGrid`（`topToGrid` = banner 高）；
 * - 换图：老图 1000ms 淡出 + 新图 1000ms 淡入；只有新图时 100ms 淡入。
 *
 * **换图"闪一下"的根治（2026-10-17 r19）**：原先是两张 `AsyncImage` 叠着做交叉淡化，有两处漏洞 ——
 * ① 旧图从"当前层"换成"上一张层"时 Coil painter 是**新建**的，会有一帧画不出来，底色（`surface`）
 * 透出来就是白闪；② 新图解码完的那一帧 `progress` 还是上一轮留下的 `1f` ⇒ 新图先**满不透明闪一帧**，
 * 再被 `snapTo(0)` 拉回去重新淡入。现在改成 9.9 的做法：**位图自己留在手里**（按 id 缓存 current/
 * previous/next 三张），交叉淡化直接在 draw 阶段叠画两张位图、旧图**恒不透明**（`t·new + (1−t)·old`，
 * 数学上不可能透出底色），且 `progress` 每个 `version` 重新从 0 起。
 *
 * 两者都只在 draw 阶段读滚动/动画状态，不触发重组。
 *
 * @param scrollOffsetPx 网格的滚动量（px）—— 自绘网格的滚动状态，draw 阶段读
 */
/**
 * banner 的"手头素材"：**必须活到页面销毁为止，不能放在 banner 自己的组合里**。
 *
 * 用户 2026-10-18 报「首页下滑再上滑 banner 会闪一下」：banner 是自绘网格里的一个 item，
 * 滚出视口就被 LazyLayout 回收 ⇒ 里面的 `remember` 全丢：位图缓存空 ⇒ 重新解码（这段什么都没有）
 * + 淡入进度回到 0 ⇒ 先白后淡入 = 闪一下。把缓存与"已显示过的 version"提到页面级，
 * 再滚回来时直接满不透明画出已有位图，不会重播淡入。
 */
@Stable
class BannerAssets {
    /** 已解好的位图，按 slide id 存 */
    val bitmaps = mutableStateMapOf<Long, ImageBitmap>()

    /** 已经完整显示过的 version；相等 ⇒ 重新进入时不再从 0 淡入 */
    var settledVersion by mutableIntStateOf(-1)
}

@Composable
fun rememberBannerAssets(): BannerAssets = remember { BannerAssets() }

@Composable
fun Banner(
    state: BannerState,
    metrics: BannerMetrics,
    scrollOffsetPx: () -> Float,
    onCrossfadeFinished: () -> Unit,
    assets: BannerAssets,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val bannerWidth = with(density) { windowSize.width.toDp() } + EXTRA_WIDTH
    val requestSize = remember(windowSize.width, metrics.drawHeightPx) {
        Size(windowSize.width, metrics.drawHeightPx)
    }

    val context = LocalContext.current

    val currentSlide = state.current
    val previousSlide = state.previous
    val nextSlide = state.next

    // 已解好的位图：按 slide id 存。**这是"不闪"的关键** ——
    // 旧图从 current 变成 previous 时，位图还在手里，不会像 AsyncImage 那样重建 painter 而空帧。
    val bitmaps = assets.bitmaps
    LaunchedEffect(currentSlide?.id, previousSlide?.id, nextSlide?.id, requestSize) {
        // 顺序有讲究：先当前（要立刻显示）、再上一张（一般已在）、最后预取下一张
        for (slide in listOfNotNull(currentSlide, previousSlide, nextSlide)) {
            if (bitmaps.containsKey(slide.id)) continue
            decodeBannerBitmap(context, slide, requestSize)?.let { bitmaps[slide.id] = it }
        }
        // 只留还在用的：淡化结束后旧图由 state 侧清掉，这里跟着回收
        val keep = listOfNotNull(currentSlide, previousSlide, nextSlide).map { it.id }.toSet()
        bitmaps.keys.retainAll(keep)
    }

    val currentBitmap = currentSlide?.let { bitmaps[it.id] }
    val previousBitmap = previousSlide?.let { bitmaps[it.id] }

    // 每个 version 一份进度：新图进来时**必然从 0 起**，不会出现"上一轮停在 1"导致的满不透明闪帧
    val progress = remember(state.version) {
        Animatable(if (assets.settledVersion == state.version && bitmaps[state.current?.id] != null) 1f else 0f)
    }

    LaunchedEffect(state.version, currentBitmap) {
        if (currentBitmap == null) return@LaunchedEffect
        // 这一版已经播完过（item 被回收后又滚回来）⇒ 直接满不透明，不再淡入
        if (progress.value >= 1f) {
            onCrossfadeFinished()
            return@LaunchedEffect
        }
        progress.animateTo(
            targetValue = 1f,
            // 9.9 的 FadeInEffect/FadeOutEffect 是 ValueAnimator 默认插值 = accelerate-decelerate ⇒ Compose 取 EaseInOut
            animationSpec = tween(
                durationMillis = if (previousSlide == null) SINGLE_FADE_IN_MILLIS else CROSSFADE_MILLIS,
                easing = EaseInOut,
            ),
        )
        assets.settledVersion = state.version
        onCrossfadeFinished()
    }

    val overlapPx = metrics.overlapPx
    val bannerHeightPx = metrics.slotHeightPx.toFloat()
    val drawHeightDp = with(density) { metrics.drawHeightPx.toDp() }
    // 9.9 的淡出：滚过 banner 自己的高度就从 1 淡到 0（列表越过 banner 后直接按整高算）
    val scrollAlpha: () -> Float = { (1f - scrollOffsetPx() / bannerHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(drawHeightDp)
            // 高度就是整幅 banner：**不再用「上报高度 − 2dp」的花招**（2026-10-17 实测它会让内容被
            // 居中对齐挤下 3px，屏幕最顶上留一条白色横条）。2dp 压边改由**几何**保证：网格里 banner 块的
            // 高度 = slotHeightPx（少 2dp），但 item 的实测高度给足 `+ overlap`，所以它照样画满整高，
            // 多出的 2dp 落在下一行区域里、被不透明的缩略图盖住。
            // **绝对不能画出 banner 自己的矩形**（2026-10-17 用户报「banner 图片没有局限在 banner 位置，
            // 铺到了下方图片列表上半部分」）：9.9 的视差是 shader 里**在同一个 quad 内**移动取样坐标
            // （`ParallaxEffect` 只改 `u_PositionOnScreen`，几何不动，且被 `getParallaxLimit()` 夹住）；
            // 我们之前在 graphicsLayer 里把整层往下 translate 0.6×滚动量，等于把图片拖进下面的网格里。
            // 现在改成：内容在**固定矩形内**做取样位移（drawBannerBitmap 的 shiftY），并在这里统一裁剪。
            .clipToBounds(),
    ) {
        // 图片层（**不再有下拉放大**：用户 2026-10-17 令删除 banner 下拉一切效果、改走平台标准回弹）
        Box(
            modifier = Modifier
                .requiredWidth(bannerWidth)
                // 上下各多画 1dp 并上移 1dp：banner 贴着屏幕最上沿，不加这一截会在最顶端留
                // 一条 1–2px 的背景色细线（图片边缘的抗锯齿缝，实测 3px @2.75x）
                .height(drawHeightDp + 2.dp)
                .offset(x = -EXTRA_WIDTH / 2f, y = (-1).dp)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            // 两张位图直接在 draw 阶段叠画：旧图**恒不透明**（背景永远透不出来），新图按 progress 淡入。
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val layerAlpha = scrollAlpha()
                        if (layerAlpha <= 0f) return@drawBehind
                        // 视差：图片内容比列表慢 0.4×（9.9 `PARALLAX_FACTOR_VS_GRID = -0.6` ⇒ 相对位移 0.6×），
                        // 在**固定矩形内**移动取样窗口（原版 shader 的 `u_PositionOnScreen`），不会画出 banner。
                        val parallaxPx = PARALLAX_FACTOR * scrollOffsetPx()
                        previousBitmap?.let { drawBannerBitmap(it, alpha = layerAlpha, shiftY = parallaxPx) }
                        val fade = progress.value
                        currentBitmap?.let { drawBannerBitmap(it, alpha = layerAlpha * fade, shiftY = parallaxPx) }
                        if (DEBUG_BANNER_FRAMES) {
                            // 覆盖率 = 这一帧有没有不透明底：旧图在就是 1，否则等于新图的淡入进度
                            val coverage = if (previousBitmap != null) 1f else fade
                            if (coverage < 0.999f) {
                                Log.w(
                                    TAG_PROBE,
                                    "v=${state.version} coverage=${"%.3f".format(coverage)} " +
                                        "prev=${previousBitmap != null} cur=${currentBitmap != null} " +
                                        "fade=${"%.3f".format(fade)} alpha=${"%.3f".format(layerAlpha)}",
                                )
                            }
                        }
                    },
            )
        }

        // 左下角说明文字（9.9 `banner_text_layout` + `Album.TextAppearance.Banner.Primary`）：
        // 14sp / 1 行 ellipsis / 白字 + 阴影 (1,1,6) / 水平 padding 17dp / 距底 12dp / 右侧让 100dp；
        // 视差比图片更"远"（-0.7 ⇒ 净 0.3×），且跟着图片一起随滚动淡出。
        // **单独一层**：原版下拉放大时不碰文字（`setOverscroll` 只作用 IMAGE 层），文字不会跟着变大。
        // 9.9 连 TEXT 层也一起交叉淡化（`fadeOutBannerDrawable` 同时处理 IMAGE 与 TEXT）⇒ 旧字淡出、新字淡入。
        val today = stringResource(R.string.banner_caption_today)
        val currentCaption = remember(currentSlide?.id, currentSlide?.dateMillis, today) {
            currentSlide?.let { bannerCaption(it.dateMillis, today) }.orEmpty()
        }
        val previousCaption = remember(previousSlide?.id, previousSlide?.dateMillis, today) {
            previousSlide?.let { bannerCaption(it.dateMillis, today) }.orEmpty()
        }
        BannerCaption(
            text = previousCaption,
            parallaxPx = { PARALLAX_TEXT_FACTOR * scrollOffsetPx() },
            alpha = { (1f - progress.value) * scrollAlpha() },
            modifier = Modifier.align(Alignment.BottomStart),
        )
        BannerCaption(
            text = currentCaption,
            parallaxPx = { PARALLAX_TEXT_FACTOR * scrollOffsetPx() },
            alpha = { progress.value * scrollAlpha() },
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/** banner 左下角那行字（两层：旧字淡出 / 新字淡入）。 */
@Composable
private fun BannerCaption(
    text: String,
    parallaxPx: () -> Float,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) return
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium.copy(
            shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(1f, 1f), blurRadius = 6f),
        ),
        modifier = modifier
            .padding(start = 17.dp, end = 100.dp, bottom = 12.dp)
            .graphicsLayer {
                translationY = parallaxPx()
                this.alpha = alpha().coerceIn(0f, 1f)
            },
    )
}

/** 解码一张 banner 位图；失败返回 null（不影响当前这张）。 */
private suspend fun decodeBannerBitmap(context: Context, slide: BannerSlide, size: Size): ImageBitmap? {
    val request = ImageRequest.Builder(context)
        .data(slide.uri)
        .size(size)
        // 位图自己留着（换图时两张同时在手）⇒ 不用再占一份 Coil 的内存缓存
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        // 交叉淡化要在同一块画布上做带 alpha 的叠画 ⇒ 软件位图（硬件位图不支持 Paint alpha 绘制）
        .allowHardware(false)
        .build()
    val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
    return (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
}

/** `ContentScale.Crop` 的等价满幅矩形：等比放大到盖满目标框并居中。 */
internal fun bannerCoverRect(imageWidth: Int, imageHeight: Int, dstWidth: Float, dstHeight: Float): CoverRect {
    if (imageWidth <= 0 || imageHeight <= 0 || dstWidth <= 0f || dstHeight <= 0f) {
        return CoverRect(0f, 0f, 0f, 0f)
    }
    val scale = max(dstWidth / imageWidth, dstHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return CoverRect(left = (dstWidth - width) / 2f, top = (dstHeight - height) / 2f, width = width, height = height)
}

/** [bannerCoverRect] 的结果（相对目标框左上角，单位 px）。 */
internal data class CoverRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * 把位图按 `Cover` 画满 banner，并做**视差取样位移**（等价 9.9 `ParallaxEffect` 的 `u_PositionOnScreen`）。
 *
 * @param shiftY 想要的内容位移（px，正 = 内容往下走 = 比列表慢）。方向与 9.9 一致：列表上滚时
 *   banner 内容滞后，观感"比网格慢"。**位移被夹在取样余量里**（9.9 用 `getParallaxLimit() =
 *   1 − bottomTextureCoordinate` 做同样的事）：`Cover` 之后位图上下都有溢出（4:3 图放进 16:9 框
 *   ≈ 各 1/6 框高），内容最多只能移这么多，否则 banner 里会露出底色 —— 夹住后图片始终盖满，
 *   且**永远不会画到 banner 矩形之外**（外层还有 clipToBounds 兜底）。
 */
private fun DrawScope.drawBannerBitmap(image: ImageBitmap, alpha: Float, shiftY: Float = 0f) {
    if (alpha <= 0.001f) return
    val rect = bannerCoverRect(image.width, image.height, size.width, size.height)
    if (rect.width <= 0f) return
    // Cover 矩形在框外还有多少余量（px）：上溢 / 下溢
    val overflowTop = (-rect.top).coerceAtLeast(0f)
    val overflowBottom = (rect.top + rect.height - size.height).coerceAtLeast(0f)
    val shift = shiftY.coerceIn(-overflowBottom, overflowTop)
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(rect.left.roundToInt(), (rect.top + shift).roundToInt()),
        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f),
        filterQuality = FilterQuality.Low,
    )
}

/**
 * banner 左下角那行字：9.9 `BannerInfoTextTranslator.getBannerInfoText` 的默认分支 ——
 * `DateFormat.getDateInstance(MEDIUM)` 的日期，若是今天则显示「今天」。
 */
private fun bannerCaption(dateMillis: Long, todayLabel: String): String {
    if (dateMillis <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
    if (date == LocalDate.now(zone)) return todayLabel
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(date)
}
