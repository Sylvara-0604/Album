package com.example.album.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import coil3.SingletonImageLoader
import coil3.BitmapImage
import com.example.album.R
import com.example.album.data.GridColumnPreference
import com.example.album.data.MediaItem
import com.example.album.data.ThumbnailPlaceholders
import com.example.album.data.aspectRatio
import com.example.album.data.thumbnailRequest
import com.example.album.ui.common.CellBounds
import com.example.album.ui.common.CellBoundsHolder
import com.example.album.ui.common.ItemDepthKind
import com.example.album.ui.common.LocalPageSwitch
import com.example.album.ui.common.PageId
import com.example.album.ui.common.PageItemState
import com.example.album.ui.common.PageRole
import com.example.album.ui.common.SelectionState
import com.example.album.ui.common.pageItemState
import com.example.album.ui.common.pageScaleFromZ
import com.example.album.ui.common.rememberPageRole
import com.example.album.util.Formatters
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** 9.9 `pinch_grid_padding` = 3dp（图间缝 = 2 × 该值 × 列数系数） */
private val BASE_PADDING = 3.dp

/** 网格底部留白 */
private val BOTTOM_PADDING = 12.dp

/** 页尾（加载更多 / 重试）固定高度：自绘布局要求块高可解析 */
private val FOOTER_HEIGHT = 72.dp

/** 预取前视窗口：滚动到下标 i 时，把 i+1..i+PREFETCH_AHEAD 的缩略图先请求好 */
private const val PREFETCH_AHEAD = 12

/** 首屏入场：9.9 `INTRO` = 100ms，各项 z 从 −1…−5 随机飞入（easeOut） */
private const val INTRO_MILLIS = 100

/** 入场只发生在"首屏那一批格子"，过了这个时间再进组合的格子（滚动加载出来的）不播 */
private const val INTRO_WINDOW_MILLIS = 260L

/**
 * 「入场动画已经播过了」的进程级标记（用户 2026-10-24 报 E2「回到首页时整屏闪一下再出图」、
 * C1「返回有重影」、以及「缩放停止时页面整体刷新」）。
 *
 * 根因：r34 之前 `introActive` 是 `remember { true }`，而**每次回到首页都会重新组合整个
 * TimelineScreen**（Nav3 在转场结束后会销毁旧场景）⇒ 每一格都从 α=0、z=−(1…5) 重新飞入：
 * - 从设置页返回时设置页还没淡出 ⇒ 两层叠在一起 = 用户说的"重影"；
 * - 从查看器返回时整屏格子先空白再逐格冒出 = "闪一下再出图"。
 *
 * 9.9 的 reason 3/5（z −1..−5 飞入 + `α=f³`）**只在冷启动那一次**播；换页走的是 reason 10–13
 * （由 `PageSwitchState` 驱动）。所以这里用进程级标记把"首屏入场"钉成只播一次。
 */
private object GridIntro {
    var played = false
}

// ---------- 9.9 按下反馈（`GridSpringAnimation` / `GridItemRippleAnimation`，r24 1:1 复刻）----------

/** 9.9 `GridSpringAnimation`：spring k=100 / b=10（mass=1 ⇒ ζ=0.5），与 `Spring.java` 同一个 ODE */
private val PressSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 100f)

/** 按下瞬间 `Spring.updateVelocity(2.0f)` ⇒ 才会缩过头再回稳（不是普通 spring 的自然过冲） */
private const val PRESS_INITIAL_VELOCITY = 2f

/** `DEFAULT_PRESSED_SCALE_DOWN_FACTOR = 0.09`（普通格缩 9%） */
private const val CELL_PULL = 0.09f

/** `isRoadSign()`（2×2 大格）用 0.045 */
private const val ROAD_SIGN_PULL = 0.045f

/** 9.9 `GroupLayout.BOUNCE_Z = −0.25f`：超过最大列数时整页后推的距离（透视后 0.8 倍） */
private const val BOUNCE_Z_MAGNITUDE = 0.25f

/**
 * 换页的逐项动画**不在这里**：交给 `ui/common/PageSwitch.kt`（9.9 reason 10/11/12/13，
 * 出 300ms + 进 300ms 串行、每项 z 随机、以屏幕中心为投影点）。
 * 这里只剩"冷启动/刷新"那一档（9.9 reason 3/5：100ms、z −(1…5) 随机飞入）。
 */

private const val BANNER_KEY = "banner"

/** 首段 2×2 大格那一个槽位 */
private const val FOOTER_KEY = "grid-footer"
private const val CONTENT_BANNER = "content:banner"
private const val CONTENT_DIVIDER = "content:divider"
private const val CONTENT_MEDIA = "content:media"
private const val CONTENT_FOOTER = "content:footer"

/** 9.9 `pinch_grid_divider_text_size = 19sp` / 上下各 15dp ⇒ 段头占位 = 19sp×1.3 + 30dp ≈ 54.7dp */
private val DIVIDER_TEXT_SIZE = 19.sp
private val DIVIDER_TEXT_MARGIN = 15.dp

/** 9.9 `pinch_grid_divider_text_margin_start = 17dp`（与列表行标题同一个左内缩） */
private val DIVIDER_TEXT_INSET = 17.dp

/** 网格项：同时记住它在 Paging 里的全局下标（点击打开查看器、触发预取都用它）。 */
internal data class GridEntry(val index: Int, val item: MediaItem)

/**
 * 网格里的一个槽位（= 一个懒加载 item）。预取、滚动定位都按这份顺序算下标。
 *
 * 网格是一整段连续流：banner → 全部图片 → 页尾。**没有"首段"这个独立槽位**（用户 2026-10-24 令：
 * 原版对首段没有任何特殊处理）——2×2 大格只是"组内 local == 0 的普通项"，
 * 位置由 [GridGeometry.groupRectAt] 的同一条公式给出。
 */
internal sealed interface Slot {
    val key: String
    val contentType: String

    data object Banner : Slot {
        override val key: String get() = BANNER_KEY
        override val contentType: String get() = CONTENT_BANNER
    }

    /**
     * 日期段头（9.9 `DividerItem`，**2026-10-24 用户令按原版加回来**）。
     *
     * 标题已经按 9.9 `DividerManager` 的规则算好：单月 = "2019年5月"；并段 = "2019年3月 - 2019年5月"
     * （**older - newer**，与原版 `getTitle()` 的拼接顺序一致）。
     */
    data class Divider(val title: String) : Slot {
        override val key: String get() = "divider:$title"
        override val contentType: String get() = CONTENT_DIVIDER
    }

    /**
     * 一张图/一段视频。`local` = 它在**连续流组内**的下标（0 = 2×2 大格那一项）。
     *
     * ⚠️ `local` 必须随数据带下来：捏合时组内 item 会换位（4 列时在首段外的第 5 项，
     * 5 列时进首段），"谁是大格"只看 local，不能再看列数。
     */
    data class Media(val entry: GridEntry, val local: Int) : Slot {
        override val key: String get() = "media:${entry.item.id}"
        override val contentType: String get() = CONTENT_MEDIA
    }

    data object Footer : Slot {
        override val key: String get() = FOOTER_KEY
        override val contentType: String get() = CONTENT_FOOTER
    }
}

/** 一个块要占的 item 范围（从槽位表里聚合出来）。 */
private class BlockSpec(
    val kind: GridBlockKind,
    val firstItem: Int,
    val itemCount: Int,
    /** 1 列版式用：这一段每一项的宽高比（宽/高），顺序与段内下标一致 */
    val aspects: FloatArray? = null,
)

/**
 * 时间线 / 文件夹网格共用的那一块。
 *
 * - Paging 的 `LazyPagingItems` 直接按原顺序铺成**一整段连续流**（已按 `date_taken` 倒序），
 *   没有按月的头行 / 分组间距 / 每段首格 2×2；
 * - 单元可点（进查看器）+ 按下 spring 缩 9% + 平台 ripple；
 * - **双指换列 = 9.9 的真机制**：列数取小数、逐帧插值重排（[FractionalGrid]），松手 spring 收敛整数列；
 * - banner 是第 0 项、跨满整行；**从屏幕最顶上开始画**；
 * - 顶部越界交给 banner（只放大 banner），底部走平台的 Google 标准 stretch 回弹。
 *
 * @param contentPaddingTop 内容顶部留白：时间线传 0（banner 顶到状态栏下），文件夹网格传状态栏高
 * @param pageId 本页在换页动画里的身份（决定"这一轮是出还是进"、以及方向）
 * @param onOpen 点某一格：绝对下标 + 缩略图像素边长 + **那一项本身** + 它在窗口里的矩形
 *   （查看器第一帧要立刻画预览：它需要 id/uri/宽高比，不能等数据库查询回来）
 */
@Composable
fun MediaGrid(
    pagingItems: LazyPagingItems<MediaItem>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onOpen: (Int, Int, MediaItem) -> Unit,
    banner: (@Composable () -> Unit)?,
    pageId: PageId,
    modifier: Modifier = Modifier,
    contentPaddingTop: Dp = 0.dp,
    enterMillis: Int = INTRO_MILLIS,
    selection: SelectionState? = null,
    state: FractionalGridState = rememberFractionalGridState(),
    /**
     * 从查看器返回时要"居中到刚才看的那张"（9.9 `ScrollResumeInfo`，审计 E15）：
     * 传**媒体 id**（不是下标）。为什么是 id：查看器给的是"窗口内页号"，网格要的是**扁平槽位下标**
     * （banner / 日期头都占位），两套下标换算过一次就错（r37 之前就是这么错的，E15 一直没生效）。
     */
    centerOnMediaId: Long? = null,
) {
    // 冷启动/刷新那一档（9.9 reason 3/5）：100ms，各项 z −(1…5) 随机飞入，**整个进程只播一次**。
    // 换页（reason 10/12）不再走这里 —— 由 `PageSwitchState` 统一驱动，两段是串行的。
    // 从设置/查看器/文件夹返回时页面是**重新组合**的，若还播这里就会"整屏先空白再逐格冒出"
    // （用户 2026-10-24 报 E2 与 C1 重影）⇒ 见 [GridIntro]。
    var introActive by remember { mutableStateOf(!GridIntro.played) }
    LaunchedEffect(Unit) {
        if (!introActive) return@LaunchedEffect
        GridIntro.played = true
        delay(INTRO_WINDOW_MILLIS)
        introActive = false
    }

    val pinch = remember { PinchColumnsState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val headId = pagingItems.peek(0)?.id
    // 条目顺序 = Paging 的原顺序（已按 date_taken 倒序），不再按月聚合
    val entries = remember(pagingItems.itemCount, headId) {
        val out = ArrayList<GridEntry>(pagingItems.itemCount)
        val seen = HashSet<Long>(pagingItems.itemCount)
        for (index in 0 until pagingItems.itemCount) {
            val item = pagingItems.peek(index) ?: continue
            // 偏移分页（LIMIT/OFFSET）在库里有插入/删除时会重复返回同一行 ⇒
            // 重复 id 会让懒布局的 key 撞车（实测崩过："Key media:72 was already used"），这里先按 id 去重
            if (!seen.add(item.id)) continue
            out += GridEntry(index, item)
        }
        out
    }

    val append = pagingItems.loadState.append
    val showFooter = append is LoadState.Loading || append is LoadState.Error

    // ⚠️ 槽位表仍按**已提交的整数列**构建（r28 曾改成"按当前显示列数取整"，结果捏合时
    // 每帧跨整数边界都在重建槽位 ⇒ 整屏反复闪烁，用户 2026-10-18 当场报「整个界面一直闪」）。
    // 原版是一份份预建好的布局切换，不是每帧重建列表；这里没有那份基础设施，就不做这种重排。
    val slots = remember(entries, banner, showFooter) {
        buildSlots(entries, banner != null, showFooter)
    }
    val specs = remember(slots) { blockSpecs(slots) }

    // 查看器关闭 ⇒ 把刚才那张居中（审计 E15）。
    // ⚠️ 为什么是"按住一段时间"而不是"设一次就完事"：从查看器返回时网格页会**重新组合**，
    // `rememberSaveable` 把滚动量恢复成**打开前**的值（实测：设置 8775 后 11ms 被恢复成 488），
    // 所以要在请求存活期内每帧重新钉住目标位置。请求由 `AlbumNav` 900ms 后清掉。
    LaunchedEffect(centerOnMediaId, slots) {
        val mediaId = centerOnMediaId ?: return@LaunchedEffect
        val slotIndex = slots.indexOfFirst { it is Slot.Media && it.entry.item.id == mediaId }
        if (slotIndex < 0) return@LaunchedEffect
        // 覆盖关闭动画 + 页面重新组合/恢复的时间窗：**60 帧 @90Hz ≈ 667ms**。
        // ⚠️ 2026-10-24 r48：原来写 `repeat(60)`（数帧数），换 120Hz 设备窗口会缩到 500ms、
        // 60Hz 又会变成 1s ⇒ 改成按**时间**算，任何刷新率下都是同一个窗口。
        val windowNanos = (60_000_000_000L / 90L)
        var startNanos = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (startNanos < 0L) startNanos = now
            state.centerOnItem(slotIndex)
            if (now - startNanos >= windowNanos) break
        }
    }

    val bannerMetrics = rememberBannerMetrics()
    val bannerHeightPx = if (banner != null) bannerMetrics.slotHeightPx.toFloat() else 0f

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val basePaddingPx = with(density) { BASE_PADDING.toPx() }
        // 视口高也进几何：1 列版式里"超高则等比夹取"的上限就是它（9.9 `mGLHeightMinusMargins`）
        val geometry = remember(widthPx, basePaddingPx, heightPx) {
            GridGeometry(widthPx.toFloat(), basePaddingPx, heightPx.toFloat())
        }

        val footerHeightPx = with(density) { FOOTER_HEIGHT.toPx() }
        val contentTopPx = with(density) { contentPaddingTop.toPx() }
        // 9.9 `pinch_grid_divider_text_size = 19sp` × 1.3 + 上下各 15dp
        val dividerHeightPx = with(density) {
            (DIVIDER_TEXT_SIZE.toDp() * 1.3f + DIVIDER_TEXT_MARGIN * 2).toPx()
        }
        // 底部要避开导航栏（9.9 底界 = 1dp + 导航栏高，`LayoutSettings.java:133`）：
        // 只加 12dp 的话，最后一行会被导航栏压住 12–36dp（审计 E9）
        val navBarInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val contentBottomPx = with(density) { BOTTOM_PADDING.toPx() } + navBarInsetPx
        // 缩略图请求尺寸按**提交后的整数列**算：换列手势中不要逐帧换解码尺寸
        // 取样尺寸按**档位**量化（对齐 9.9 `convertColumnsToQuality` 的 1/3/5/10 四档思路）：
        // 直接按提交列数算的话，每次松手提交都会换一套解码尺寸 ⇒ Coil 换缓存键 ⇒ 整屏先变灰再出图
        // （用户 2026-10-24：「图片还是闪，但只在缩放松手后」）。量化后 2↔3、4↔5、6↔7 都不再换请求。
        val sampleColumns = when {
            columns <= 1 -> 1
            columns <= 3 -> 3
            columns <= 5 -> 5
            else -> 7
        }
        val cellPx = geometry.cell(sampleColumns.toFloat()).toInt().coerceAtLeast(1)
        // 1 列版式里格子高 = 宽 / 比例（超高夹到视口高），取样框要按较长边，否则竖图会被解糊
        val aspectLayout = sampleColumns == 1
        val requestPx: (MediaItem) -> Int = remember(geometry, cellPx, aspectLayout) {
            if (aspectLayout) {
                { item: MediaItem -> geometry.linearSamplePx(item.aspectRatio()) }
            } else {
                { _: MediaItem -> cellPx }
            }
        }

        // 列数是"每帧都可能变"的：读在 measure 里 ⇒ 手势中只 remeasure，不重组
        // 布局用列数夹在 [1, 最大列数]：越过最大列数的那一段由「整页 z 后推」表达（9.9 bounce 布局）
        val columnsNow: () -> Float = remember(pinch) { { pinch.layoutColumns() } }
        // 外部列数变化（读偏好 / 恢复状态）时把显示值对齐；手势与回弹期间不动它
        LaunchedEffect(columns, pinch.pinching, pinch.settling) {
            if (!pinch.pinching && !pinch.settling) pinch.setColumns(columns)
        }
        val modelBuilder: (Float) -> GridModel = remember(
            specs,
            geometry,
            bannerHeightPx,
            footerHeightPx,
            dividerHeightPx,
            contentTopPx,
            contentBottomPx,
        ) {
            { fractional ->
                buildModel(
                    specs = specs,
                    geometry = geometry,
                    columns = fractional,
                    contentTopPx = contentTopPx,
                    bannerHeightPx = bannerHeightPx,
                    footerHeightPx = footerHeightPx,
                    dividerHeightPx = dividerHeightPx,
                    contentBottomPx = contentBottomPx,
                )
            }
        }

        // 顶部下拉交给 banner 放大（2026-10-24 用户令按原版加回）；底部仍是平台 stretch。
        val platformOverscroll: OverscrollEffect? = rememberOverscrollEffect()

        // ⚠️ 交给 `pinchToColumns` 的回调必须是**稳定实例**：那种函数把 `pointerInput` 的 key 定成
        // `pinch`（见 `GridPinch.kt` 的注释），一旦这里每重组新建 lambda 也不会重启手势，但为了
        // "最新值"正确，下面用 `rememberUpdatedState` 取当前回调、再 remember 出稳定 lambda。
        val latestColumnsChange by rememberUpdatedState(onColumnsChange)
        val latestOpen by rememberUpdatedState(onOpen)
        // 手势回调只在这一份 `pointerInput` 里被调用，重启次数极少 ⇒ 里面的 `slots` 可能已经是旧的
        // （数据刷新/追加后槽位表会变），所以锚点/开图要读"当前"的槽位表。
        val latestSlots by rememberUpdatedState(slots)
        val commitColumns: (Int) -> Unit = remember(pinch) {
            // 回调发生在 snap 弹簧跑完时：先清锚点（滚动量恢复自由），再提交列数
            { target -> state.clearPinchAnchor(); latestColumnsChange(target) }
        }
        val openCell: (Int, Int, MediaItem) -> Unit = remember { { index, px, item -> latestOpen(index, px, item) } }

        val gesture = Modifier.pinchToColumns(
            pinch = pinch,
            onColumnsChange = commitColumns,
            scope = scope,
            // 指下那一项 = **锚点**（捏合期滚动量由它反算，缩放围绕手指进行）+ **焦点项**
            // （列数 < 3.1 时跟着手指旋转、横向跟手、z 微抬）。
            onStart = { centroid ->
                state.beginPinch(centroid.x, centroid.y) { index ->
                    (latestSlots.getOrNull(index) as? Slot.Media)?.entry?.item?.id ?: -1L
                }
            },
            onFollowY = { dy -> state.followPinchY(dy) },
            onFollowX = { dx -> state.followPinchX(dx) },
            onRotate = { degrees -> state.applyFocusRotation(degrees) },
            // 单指横向快滑 = 列数 ±1（9.9 `onFling`：速度向右 ⇒ 列数 −1 = 格子变大）。
            // 锚点取视口中间那一项（原版 `followItem(null)`），弹簧收敛完再提交列数。
            onHorizontalFling = { vx ->
                state.beginFlingAnchor()
                pinch.stepColumns(scope, delta = if (vx > 0f) -1 else 1) { target ->
                    commitColumns(target)
                }
            },
            onEnd = { state.finishFocus(scope) },
        )

        // 9.9 `PinchAnimation` 是 `setLoop(true)` 的动画路径：平滑**每帧**推进，而不是"每个指针事件推一步"。
        // 差别就在松手那一瞬 —— 事件驱动时手指一停 `display` 立刻冻结（用户 2026-10-24：
        // 「原版缩放时移开手指，不会突然顿一下，而是顺滑停止」）。
        // ⚠️ 每帧步长按**真实 dt** 归一（见 `perFrameAlpha`）：90Hz 下与旧的逐帧写法逐帧一致，
        // 120/60Hz 设备上实时速度也保持一致。
        LaunchedEffect(pinch.pinching) {
            if (!pinch.pinching) return@LaunchedEffect
            var lastNanos = 0L
            while (true) {
                withFrameNanos { now ->
                    val dt = if (lastNanos == 0L) {
                        1f / PINCH_REFERENCE_HZ
                    } else {
                        ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                    }
                    lastNanos = now
                    pinch.advanceFrame(dt)
                }
            }
        }

        // 捏合/收尾期间把标记交给网格状态：首页胶囊"按滚动方向收起/出现"要避开这段
        // （锚点每帧反算滚动量，手指抖动会让方向不停翻转 ⇒ 胶囊跟着抖）。
        LaunchedEffect(pinch.pinching, pinch.settling) {
            state.pinching = pinch.pinching || pinch.settling
        }

        // 查看器开合：**只做图片本身的线性缩放 / 原路返回**（用户 2026-10-17 令）：
        // 网格不参与任何推近/显影/变黑，所以这里不再有整层变换。

        // 超过最大列数时的"整页后推"：9.9 的 bounce 布局给**所有** item 同一个 offsetZ
        // （`GroupLayout` 的 bounce 布局 `setOffsetZ(-0.25)`）⇒ 透视相机下等价于"整页以屏心等比缩到 0.8"。
        // 以前我们是逐项加 z 再用 `bounds` 反算 translation 补偿 —— `bounds` 是普通字段，
        // 布局/滚动时不会失效 ⇒ 首次生效那一帧会**跳一下**（用户 2026-10-24：「7 列拉到最顶捏合，界面跳一下」）。
        // 改成容器级一次缩放：没有逐项反馈、没有跳，观感与 9.9 等价。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = pageScaleFromZ(-BOUNCE_Z_MAGNITUDE * pinch.bounce)
                    scaleX = s
                    scaleY = s
                },
        ) {
        FractionalGrid(
            slots = slots,
            state = state,
            columns = columnsNow,
            buildModel = modelBuilder,
            overscrollEffect = platformOverscroll,
            bannerOverlapPx = bannerMetrics.overlapPx,
            modifier = Modifier
                .fillMaxSize()
                .then(gesture),
            content = { slot ->
                when (slot) {
                    // banner 也是原版里的一个 item（reason 12 固定 0.25 深度），换页时跟其它项一起动；
                    // 冷启动也参与逐项入场（9.9 reason 3 给它随机 z，审计 E8）
                    is Slot.Banner -> PageAnimatedItem(
                        pageId = pageId,
                        kind = ItemDepthKind.BANNER,
                        depthKey = BANNER_KEY,
                        playIntro = introActive,
                        introMillis = enterMillis,
                    ) {
                        // 按下反馈（9.9 banner 也是 UiItem，触点 ripple 与网格同一套，审计 E16）。
                        // 用户 2026-10-24 拍板（D2）：**只补按下反馈，不接"点开全屏"**。
                        val bannerInteraction = remember { MutableInteractionSource() }
                        val bannerPressed by bannerInteraction.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = bannerInteraction,
                                    indication = null,
                                ) { /* D2：点击不打开（有意差异） */ }
                                .pressRipple(pressed = bannerPressed),
                        ) { banner?.invoke() }
                    }

                    // 日期段头（2026-10-24 用户令按原版加回）。
                    // ⚠️ 必须和媒体项走同一套逐项换页动画（用户 2026-10-24 报「日期条比其他内容先出现」）：
                    // 不包 `PageAnimatedItem` 的话它不是 GL item、α 恒 1 ⇒ 换页时它先出现/后消失。
                    is Slot.Divider -> PageAnimatedItem(
                        pageId = pageId,
                        kind = ItemDepthKind.NORMAL,
                        depthKey = "divider:${slot.title}",
                        playIntro = introActive,
                        introMillis = enterMillis,
                    ) { DateDividerHeader(title = slot.title) }

                    is Slot.Media -> {
                        // 访问 Paging 的那一格才会推进预取/下一页（peek 不会）。
                        // ⚠️ 2026-10-24 **崩溃修复**：`pagingItems[i]` 越界会直接抛
                        // `IndexOutOfBoundsException`（Paging 的 `PageStore.checkIndex`）。
                        // 实测路径（release + retrace）：`PageStore.checkIndex` ← `PagingDataPresenter.get`
                        // ← `LazyPagingItems.get` ← 这里。触发序列：库变化 → `invalidate()` → 快照先缩到 1 条，
                        // 而本帧的 `slots` 还是上一次的（2 条）⇒ 组合期取第 2 条炸掉（`Index: 1, Size: 1`）。
                        // `itemCount` 是快照状态，越界时退回槽位里携带的那份 item（至少和 slot 自洽，不会画错图）。
                        val live = if (slot.entry.index in 0 until pagingItems.itemCount) {
                            pagingItems[slot.entry.index] ?: slot.entry.item
                        } else {
                            slot.entry.item
                        }
                        // local == 0 = 2×2 大格（>=3 列才有；<=2 列它就是一个普通格）
                        val isRoadSign = slot.local == 0 && columns > 2
                        val px = if (isRoadSign) {
                            (2f * geometry.cell(columns.toFloat()) + geometry.gap(columns.toFloat()))
                                .roundToInt().coerceAtLeast(1)
                        } else {
                            requestPx(live)
                        }
                        MediaCell(
                            item = live,
                            grid = state,
                            pinch = pinch,
                            requestPx = px,
                            playIntro = introActive,
                            introMillis = enterMillis,
                            pageId = pageId,
                            // 9.9 的 roadSign 是 item 的属性（组内第一项），与当前列数无关
                            depthKind = if (slot.local == 0) ItemDepthKind.ROAD_SIGN else ItemDepthKind.NORMAL,
                            selection = selection,
                            // ⚠️ 参数必须**稳定**：以前这里传 `onClick = { openCell(index, px, live) }`，
                            // 每次重组都是新 lambda ⇒ `MediaCell` 永远不能 skip ⇒ 整屏 51 格全部重组
                            // （实测单次 0.95ms/格，一帧 50ms+）。改成"稳定回调 + 最新值"：
                            cellIndex = slot.entry.index,
                            cellThumbPx = px,
                            onOpenCell = openCell,
                        )
                    }

                    is Slot.Footer -> GridFooter(append = append, onRetry = pagingItems::retry)
                }
            },
        )
        }

        // 滚动时提前把后面几十张缩略图请求好（Coil 负责去重与排队）；列数变了用新尺寸重新预取
        LaunchedEffect(state, slots, cellPx, aspectLayout) {
            snapshotFlow { state.lastVisibleItemIndex }
                .distinctUntilChanged()
                .collect { lastVisible ->
                    if (lastVisible < 0) return@collect
                    val end = (lastVisible + PREFETCH_AHEAD).coerceAtMost(slots.lastIndex)
                    for (index in (lastVisible + 1)..end) {
                        val slot = slots.getOrNull(index) ?: continue
                        val items = when (slot) {
                            is Slot.Media -> listOf(slot.entry)
                            else -> emptyList()
                        }
                        items.forEach { entry ->
                            SingletonImageLoader.get(context).enqueue(
                                thumbnailRequest(context, entry.item, requestPx(entry.item)),
                            )
                        }
                    }
                }
        }
    }
}

/**
 * 槽位表：banner →（日期段头 + 该段全部媒体）… → 页尾。
 *
 * **不再吃列数**（用户 2026-10-24 令）：原版 item 是扁平的、每份整数布局对任意下标都能给出位置，
 * 槽位表若跟着列数重建，捏合每跨一个整数列就整表重建（r28 实测整屏闪 + 提交帧跳位）。
 *
 * 2026-10-24 用户令「日期头 / 按月分组 按原版重新加回来」⇒ 每个日期段前插一个 `Slot.Divider`，
 * 段内第一项（`local == 0`）在 ≥3 列时排成 2×2 大格（9.9 `isRoadSign(i) = isDivider(i−1)`）。
 */
private fun buildSlots(
    entries: List<GridEntry>,
    hasBanner: Boolean,
    showFooter: Boolean,
): List<Slot> = buildList {
    if (hasBanner) add(Slot.Banner)
    var cursor = 0
    for (group in dateGroups(entries)) {
        if (group.count <= 0) continue
        add(Slot.Divider(group.title))
        for (local in 0 until group.count) {
            add(Slot.Media(entries[cursor + local], local))
        }
        cursor += group.count
    }
    if (showFooter) add(Slot.Footer)
}

/** 一个日期段：在扁平 `entries` 里的起点/条数 + 已算好的标题。 */
internal data class DateGroup(val startIndex: Int, val count: Int, val title: String)

/**
 * 按月分组（9.9 `DividerManager.addDivider`，`divider/DividerManager.java:62-120`）：
 *
 * - 条目已按时间倒序 ⇒ 逐条看它的月份；跨月时看**刚结束那个月**的条数：
 *   `≥ MIN_ITEMS_PER_MONTH(12)` 就在此处断开成一段；`< 12` 就并进下一段（并段标题 = `older - newer`）。
 * - 最后一个月的段头即使不足 12 条也保留（9.9 收尾时 `addDivider(..., true)` 会补上）。
 */
internal fun dateGroups(entries: List<GridEntry>): List<DateGroup> {
    if (entries.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern(
        if (Locale.getDefault().language == "zh") "yyyy年M月" else "MMM yyyy",
        Locale.getDefault(),
    )
    fun monthOf(millis: Long): YearMonth = Instant.ofEpochMilli(millis).atZone(zone).let {
        YearMonth.of(it.year, it.monthValue)
    }

    val out = ArrayList<DateGroup>()
    var groupStart = 0
    var newestMonth = monthOf(entries[0].item.dateTakenMillis)
    var currentMonth = newestMonth
    var monthCount = 0
    for (i in entries.indices) {
        val m = monthOf(entries[i].item.dateTakenMillis)
        if (m != currentMonth) {
            // 刚结束的 currentMonth 有 monthCount 条：够 12 就断开，不够就并段（不产出）
            if (monthCount >= MIN_ITEMS_PER_MONTH) {
                val title = if (currentMonth == newestMonth) {
                    formatter.format(currentMonth)
                } else {
                    // 9.9 `getTitle()`：older - newer
                    "${formatter.format(currentMonth)} - ${formatter.format(newestMonth)}"
                }
                out += DateGroup(groupStart, i - groupStart, title)
                groupStart = i
                newestMonth = m
            }
            currentMonth = m
            monthCount = 0
        }
        monthCount++
    }
    val lastTitle = if (currentMonth == newestMonth) {
        formatter.format(currentMonth)
    } else {
        "${formatter.format(currentMonth)} - ${formatter.format(newestMonth)}"
    }
    out += DateGroup(groupStart, entries.size - groupStart, lastTitle)
    return out
}

/** 9.9 `DividerManager.MIN_NUMBER_OF_ITEMS_PER_MONTH = 12` */
internal const val MIN_ITEMS_PER_MONTH = 12

/** 槽位表 → 块表：每段日期头各一块，段内普通格合并成一段可插值的 "Run"。 */
private fun blockSpecs(slots: List<Slot>): List<BlockSpec> {
    val out = ArrayList<BlockSpec>(8)
    var i = 0
    while (i < slots.size) {
        when (slots[i]) {
            is Slot.Banner -> {
                out += BlockSpec(GridBlockKind.Banner, i, 1)
                i++
            }

            is Slot.Divider -> {
                out += BlockSpec(GridBlockKind.Divider, i, 1)
                i++
            }

            is Slot.Footer -> {
                out += BlockSpec(GridBlockKind.Footer, i, 1)
                i++
            }

            is Slot.Media -> {
                var j = i
                while (j < slots.size && slots[j] is Slot.Media) j++
                // 1 列要按原比例排版：把这一段的宽高比一起带上（顺序 = 段内下标）
                val aspects = FloatArray(j - i) { offset ->
                    (slots[i + offset] as Slot.Media).entry.item.aspectRatio()
                }
                out += BlockSpec(GridBlockKind.Run, i, j - i, aspects)
                i = j
            }
        }
    }
    return out
}

/**
 * 块表 + 小数列数 → 完整几何。块高全部可解析：banner / 日期头 / 页尾是常量，
 * 格子段 = 插值后的段高。块之间的留白按 9.9 的组高规则：
 *
 * - banner → 下一块：一道图间缝（`gap`，用户 2026-10-17「留白等于图片之间的间隔」）；
 * - 日期头 → 它那一段：**零**（原版 item 的 y 从 `dividerCellH` 之后算起）；
 * - 一段格子 → 下一块：`gap/2`（原版 `groupH = rows·cell + dividerCellH`，尾部余量正好是半个 gap = p）。
 */
private fun buildModel(
    specs: List<BlockSpec>,
    geometry: GridGeometry,
    columns: Float,
    contentTopPx: Float,
    bannerHeightPx: Float,
    footerHeightPx: Float,
    dividerHeightPx: Float,
    contentBottomPx: Float,
): GridModel {
    val gap = geometry.gap(columns)
    // 1 列版式：列数 < 2 时在"1 列原比例"与"2 列正方形"之间逐项插值（9.9 InterpolateLayout）
    val linearT = (columns - 1f).coerceIn(0f, 1f)
    var y = contentTopPx
    val blocks = ArrayList<GridBlock>(specs.size)
    specs.forEachIndexed { index, spec ->
        val aspects = spec.aspects
        val linear = if (spec.kind == GridBlockKind.Run && columns < 2f && aspects != null) {
            geometry.linearLayout(aspects)
        } else {
            null
        }
        val height = when (spec.kind) {
            GridBlockKind.Banner -> bannerHeightPx
            GridBlockKind.Divider -> dividerHeightPx
            GridBlockKind.Run -> if (linear != null) {
                val twoColumns = geometry.runHeight(spec.itemCount, 2f)
                linear.height + (twoColumns - linear.height) * linearT
            } else {
                geometry.runHeight(spec.itemCount, columns)
            }

            GridBlockKind.Footer -> footerHeightPx
        }
        blocks += GridBlock(
            kind = spec.kind,
            firstItem = spec.firstItem,
            itemCount = spec.itemCount,
            y = y,
            height = height,
            linear = linear,
        )
        y += height
        val spacing = when (spec.kind) {
            GridBlockKind.Banner -> gap
            GridBlockKind.Run -> gap / 2f
            GridBlockKind.Divider -> 0f
            GridBlockKind.Footer -> 0f
        }
        if (index != specs.lastIndex) y += spacing
    }
    return GridModel(
        blocks = blocks,
        geometry = geometry,
        columns = columns,
        contentHeightPx = y + contentBottomPx,
    )
}

/** 网格单元格：正方形缩略图 + 按下反馈 +（视频）右下角时长 + 换页时的逐项 z 动画。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MediaCell(
    item: MediaItem,
    /** 网格状态：捏合时焦点项的旋转/z 抬升从这里读（9.9 `PinchFocusAnimation`） */
    grid: FractionalGridState,
    /** 捏合状态：两端过冲量（1 列继续放大 / 最大列数整页后推）从这里读 */
    pinch: PinchColumnsState,
    /** 缩略图取样边长（px；正方形取样框，图片等比塞入）：1 列版式传"较长边" */
    requestPx: Int,
    playIntro: Boolean,
    selection: SelectionState?,
    /** 这一格在所属列表里的绝对下标（点击时传给查看器） */
    cellIndex: Int,
    /** 这一格缩略图的像素边长（点击时传给查看器当首帧种子） */
    cellThumbPx: Int,
    /** **稳定**回调：开图（必须是 `remember` 出来的实例，否则整屏格子无法 skip） */
    onOpenCell: (Int, Int, MediaItem) -> Unit,
    pageId: PageId,
    modifier: Modifier = Modifier,
    introMillis: Int = INTRO_MILLIS,
    depthKind: ItemDepthKind = ItemDepthKind.NORMAL,
) {
    val context = LocalContext.current
    // **同步占位 = 这一格自己上一帧真正画出来的那张**（2026-10-24 按用户录屏逐帧取证后改）。
    // 用户报「缩放松手后闪一下」，录屏（842×1920@42.9fps）逐帧量到：提交列数那一帧整格/整屏变成
    // **纯色 `surfaceVariant`（实测 RGB 均值 226、行内 std 4.97、无图缝）**，下一帧又复原 —— 也就是
    // 换了 model（换档 ⇒ Coil 内存键变）后落回 Loading，而 placeholder 拿不到。
    //
    // 为什么拿不到：以前唯一来源是全局 `ThumbnailPlaceholders`（12MB LRU、按字节算容量）——
    // 1–2 列的大格子（640 档 ≈ 1.6MB/张）连一屏 8 张都装不下；7 列一屏 100 多张（≈0.1MB/张）
    // 也贴着上限 ⇒ 提交那一刻大批/全部格子被淘汰，直接露出格子底色（`surfaceVariant`）。
    // 每格自己留一份 painter 就没有预算问题：这一格既然画过图，就一定有得用。
    // （全局那份只留作"重新进入页面、本格还没画过"时的兜底。）
    //
    // 2026-10-24 方案 B：**不再用 `AsyncImage`**（= `rememberAsyncImagePainter` + `Image`，
    // 每格一套请求状态机 + 几个协程 + 一串 `rememberUpdatedState`）。实测单格组合 ≈1.8ms
    // （recompose 0.95 + applyChanges 0.75），40 格就是一次 97ms 的单帧（perfetto，见 `docs/PERF.md`）。
    // 现在只用 Coil 的官方底层接口 `ImageLoader.enqueue(request)` + 一个 listener：
    // 缓存/去重/取消仍由 Coil 管线负责（`memoryCacheKey` 与查看器首帧共用同一个键，没变）。
    var current by remember(item.id) { mutableStateOf<Painter?>(null) }
    val cachedThumb = remember(item.id) { ThumbnailPlaceholders.get(item.id) }
    val placeholderPainter = remember(cachedThumb) {
        cachedThumb?.let { BitmapPainter(it.asImageBitmap()) }
    }
    val loader = remember { SingletonImageLoader.get(context) }
    val request = remember(item.id, requestPx) {
        thumbnailRequest(context, item, requestPx).newBuilder()
            .listener(
                onSuccess = { _, result ->
                    val bitmap = (result.image as? BitmapImage)?.bitmap
                    if (bitmap != null) current = BitmapPainter(bitmap.asImageBitmap())
                },
            )
            .build()
    }
    DisposableEffect(request) {
        val disposable = loader.enqueue(request)
        onDispose { disposable.dispose() }
    }
    // 已到货那张 > 全局兜底（冷进入、本格还没画过）。换档期间 `current` 还是旧档那张 ⇒ 不露灰底。
    val shown = current ?: placeholderPainter
    val pageSwitch = LocalPageSwitch.current

    // 9.9 `AlbumAnimator.sRandom.nextFloat()`：**每项一个随机 z**，这就是"分格"的来源。
    // ⚠️ 原版**每次换页重抽**（`startAnimation` 里 nextFloat），不是每项一辈子一个值：
    // 用 `generation` 当 key，每次换页换一条轨迹（2026-10-24 修正：以前按 item.id 固化，
    // 反复换页每轮都一模一样，容易被读成"动画没变/卡住"）。
    val depthRandom = remember(item.id, pageSwitch?.generation) { Random.nextFloat() }
    val intro = remember(item.id) { Animatable(if (playIntro) 1f else 0f) }
    LaunchedEffect(item.id, playIntro, introMillis) {
        if (playIntro) {
            intro.animateTo(0f, tween(introMillis, easing = EaseOut))
        } else {
            // 入场窗口关了但这一格还在播 ⇒ 必须收敛到 0（alpha 由 intro 决定）：
            // 只让 effect 空转会把 Animatable 停在中间值，整屏格子看起来"发白"（2026-10-17 实测踩到）
            intro.snapTo(0f)
        }
    }

    val bounds = remember { CellBoundsHolder() }
    val role = rememberPageRole(pageId)
    val windowSize = LocalWindowInfo.current.containerSize

    // 捏合焦点项（9.9 `PinchFocusAnimation`）：这一格是不是"指下那一项"
    // （只有媒体格参与；banner 在原版里不是 GridItem，也就没有这套焦点动画）
    val focusing = grid.focusItem >= 0 && grid.focusMediaId == item.id

    // 长按选中：右下/左上角出现橙圆勾（9.9 `Badge.SELECTED`）
    val selected = selection != null && selection.isSelected(item.id)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 点击/长按的**稳定** lambda（`remember` 里只读 `rememberUpdatedState`，身份不变 ⇒ 本格可被 skip）
    val latestItem by rememberUpdatedState(item)
    val latestIndex by rememberUpdatedState(cellIndex)
    val latestThumbPx by rememberUpdatedState(cellThumbPx)
    val latestSelection by rememberUpdatedState(selection)
    val openClick = remember {
        {
            // 滑行中按下 ⇒ 这一次点击不算（9.9 `cancelActiveMovements()`，审计 E4）
            if (!grid.consumeSuppressedTap()) {
                val sel = latestSelection
                // 选择模式下点按 = 选中/取消，否则才是打开查看器（9.9 SelectionMode.MULTIPLE）
                if (sel?.active == true) sel.toggle(latestItem.id)
                else onOpenCell(latestIndex, latestThumbPx, latestItem)
            }
        }
    }
    val longClick = remember {
        {
            latestSelection?.begin(latestItem.id)
            Unit
        }
    }

    // 9.9 `GridSpringAnimation`：spring k=100 / b=10（ζ=0.5），attachment 0.09（2×2 大格 0.045），
    // 按下瞬间 `updateVelocity(2.0)` ⇒ 0.91 途中过冲到最低 0.838 再回稳；松手保留速度弹回 1.0。
    //
    // 2026-10-24 用户令（审计 D1）：**多选态下被选中的格子常驻缩 9%**，取消选中才回弹。
    // 源码依据：`GridSpringAnimation` 只注册在 `createSelectedAnimations` 里（`GridItem.java:243-247`），
    // 而 `UiItem.updateItemAnimations` 只把 `setSelected` 派给它（`UiItem.java:465-477`）⇒ 选中期间
    // attachment 一直停在 0.09；浏览态（未选中的按下）原版只有 ripple、没有缩小。
    val pull = remember { Animatable(0f) }
    val shrinkDepth = if (depthKind == ItemDepthKind.ROAD_SIGN) ROAD_SIGN_PULL else CELL_PULL
    val shrinkTarget = if (selected) shrinkDepth else 0f
    LaunchedEffect(shrinkTarget) {
        if (shrinkTarget > 0f) {
            pull.animateTo(shrinkTarget, PressSpring, initialVelocity = PRESS_INITIAL_VELOCITY)
        } else {
            pull.animateTo(0f, PressSpring)
        }
    }
    val pressScale = 1f - pull.value

    val placeholder = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                // 点击时要用它把"格位矩形"传给查看器（转场起点）；
                // 换页动画要用它算"以屏心为投影点"的径向位移
                val rect = coordinates.boundsInWindow()
                bounds.value = CellBounds(
                    left = rect.left.roundToInt(),
                    top = rect.top.roundToInt(),
                    width = rect.width.roundToInt(),
                    height = rect.height.roundToInt(),
                )
            }
            .graphicsLayer {
                // **照抄 9.9 `UiItemAnimationPath.updatePath`**：每项只沿 z 平移，
                // 透视相机（近平面距离 = 1）下等效 `scale = 1/(1−Δz)`：
                //   Δz = 0 ⇒ 1.0（到位）；Δz = −1 ⇒ 0.5（远处）；Δz = +0.7 ⇒ 3.33（朝镜头飞过去）。
                // alpha 也不是线性：`f³`（进）/ `(1−f)³`（出）。
                val itemState = if (pageSwitch != null && role != PageRole.IDLE) {
                    pageItemState(
                        role = role,
                        direction = pageSwitch.direction,
                        enterProgress = pageSwitch.enter.value,
                        exitProgress = pageSwitch.exit.value,
                        depth = depthRandom,
                        kind = depthKind,
                        alphaPower = pageSwitch.enterAlphaPower,
                    )
                } else {
                    // 冷启动/刷新（reason 3/5）：z −(1…5) 随机飞入 → 0
                    val appear = intro.value
                    val f = 1f - appear
                    PageItemState(z = -(depthRandom * 4f + 1f) * appear, alpha = f * f * f)
                }
                // 两端过冲：1 列继续放大时焦点项再贴近镜头（9.9 `MIN_Z_OFFSET + (towardsCenter−1)`）；
                // 超过最大列数时整页后推（9.9 bounce 布局 `BOUNCE_Z = −0.25`）
                val overZoomZ = if (focusing) (pinch.overZoom - 1f) else 0f
                val baseZ = itemState.z + (if (focusing) grid.focusZ + overZoomZ else 0f)
                val zScale = pageScaleFromZ(baseZ)
                scaleX = pressScale * zScale
                scaleY = pressScale * zScale
                // 焦点项跟着两根手指一起转（9.9 `PinchFocusAnimation.updatePath`：
                // `pathTransform.rotate(mRotateDegrees, 0, 0, 1)`，绕格子自己的中心）
                rotationZ = if (focusing) grid.focusRotation else 0f
                // 透视投影的投影中心是**屏幕中心** ⇒ 等价于"以屏心等比缩放"，
                // 也就是在原地缩放之外再补一个径向位移 `(1−s)·(C−O)`（离屏心越远位移越大）。
                val box = bounds.value
                if (box.width > 0 && windowSize.width > 0) {
                    val centerX = box.left + box.width / 2f
                    val centerY = box.top + box.height / 2f
                    translationX = (1f - zScale) * (windowSize.width / 2f - centerX)
                    translationY = (1f - zScale) * (windowSize.height / 2f - centerY)
                }
                alpha = itemState.alpha
            }
            .background(placeholder)
            .combinedClickable(
                interactionSource = interaction,
                // 涟漪由我们按 9.9 的 shader 自己画（加法叠灰 + 椭圆），所以关掉 Material 的 indication
                indication = null,
                onClick = openClick,
                onLongClick = longClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 原版的涟漪是画在**图片那块 quad 里**的（顶点/片元着色器只覆盖纹理矩形），
                // 所以默认被格位裁住；Compose 的 draw 默认**不裁剪**，椭圆半径 = 进度×对角线、圆心又在触点，
                // 画到格子边上时就会糊到相邻格子上（用户 2026-10-18 报「波纹扩散超过图片格子」）⇒ 显式裁到格内。
                .clipToBounds()
                // 9.9 的按下反馈（整图加法叠灰 0.2 + 触点椭圆内再叠 0.2）由共享修饰符实现；
                // 进入多选（本格被选中）时剩余部分压到 700ms 内收完（9.9 `fastFinishAnimation`）
                .pressRipple(pressed = pressed, fastFinish = selected),
        ) {
            // 换档重载期间画"这一格上一张"（宁可糊一帧，也不许露出灰底）：`shown` 就是它
            if (shown != null) {
                Image(
                    painter = shown,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // 视频时长角标（本项目已拍板：**只在右下角显示时长、不叠中央播放图标**）。
        // r24 用户令：**胶囊形 + 跟随取色 + 文字到边缘上下左右一致**。
        //  - 取色走 M3 的容器角色（`secondaryContainer / onSecondaryContainer`）：这两个角色跟着动态取色/
        //    深浅主题走，是"压在图上的一小块标签"该用的配对；r24 用的 `inverseSurface` 在浅色主题下就是
        //    中性近黑，看起来"根本没取色"（用户 2026-10-18 报）。
        //  - `RoundedCornerShape(percent = 50)` = 胶囊（半径恒为短边一半），但**前提是文字盒子别被撑高**：
        //    Text 只传 `fontSize` 时仍继承 `LocalTextStyle`（bodyLarge）的 `lineHeight = 24sp`，
        //    盒子高度会变成 24sp ⇒ 短边变成高、50% 半径算出来是个**圆**（用户 2026-10-18 报「变成竖向的了」）。
        //    这里显式给 `lineHeight` 并关掉换行，四边内边距才能真的相等。
        val durationLabel = if (item.durationMillis > 0L) Formatters.duration(item.durationMillis) else ""
        if (durationLabel.isNotEmpty()) {
            Text(
                text = durationLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    letterSpacing = 0.sp,
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f))
                    .padding(3.dp),
            )
        }
        if (selection != null) {
            // 9.9：选中勾在**左上角**（1.2×1.2 的 quad，实测 #FF9800 圆 + #211300 勾）
            SelectionBadge(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
            )
        }
    }
}

/**
 * banner 的换页表现：它也是原版的一个 item（`BannerUiItem`），只是 z 固定 ——
 * `reason 12`（返回方向）用 `BANNER_MOVE_REDUCTION = 0.25`，其它方向同样取随机深度。
 */
@Composable
private fun PageAnimatedItem(
    pageId: PageId,
    kind: ItemDepthKind,
    depthKey: String,
    playIntro: Boolean = false,
    introMillis: Int = INTRO_MILLIS,
    content: @Composable () -> Unit,
) {
    val pageSwitch = LocalPageSwitch.current
    val role = rememberPageRole(pageId)
    // 同上：banner 的深度也每次换页重抽（返回方向固定 0.25 的规则见 pageItemState）
    val depth = remember(depthKey, pageSwitch?.generation) { Random.nextFloat() }
    // 冷启动入场（9.9 reason 3 也给 banner 随机 z + α=f³，审计 E8）
    val intro = remember { Animatable(if (playIntro) 1f else 0f) }
    LaunchedEffect(playIntro, introMillis) {
        if (playIntro) intro.animateTo(0f, tween(introMillis, easing = EaseOut)) else intro.snapTo(0f)
    }
    val windowSize = LocalWindowInfo.current.containerSize
    val bounds = remember { CellBoundsHolder() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInWindow()
                bounds.value = CellBounds(
                    left = rect.left.roundToInt(),
                    top = rect.top.roundToInt(),
                    width = rect.width.roundToInt(),
                    height = rect.height.roundToInt(),
                )
            }
            .graphicsLayer {
                if (pageSwitch != null && role != PageRole.IDLE) {
                    val state = pageItemState(
                        role = role,
                        direction = pageSwitch.direction,
                        enterProgress = pageSwitch.enter.value,
                        exitProgress = pageSwitch.exit.value,
                        depth = depth,
                        kind = kind,
                        alphaPower = pageSwitch.enterAlphaPower,
                    )
                    val zScale = pageScaleFromZ(state.z)
                    scaleX = zScale
                    scaleY = zScale
                    val box = bounds.value
                    if (box.width > 0 && windowSize.width > 0) {
                        val centerX = box.left + box.width / 2f
                        val centerY = box.top + box.height / 2f
                        translationX = (1f - zScale) * (windowSize.width / 2f - centerX)
                        translationY = (1f - zScale) * (windowSize.height / 2f - centerY)
                    }
                    alpha = state.alpha
                } else {
                    val appear = intro.value
                    val f = 1f - appear
                    val z = -(depth * 4f + 1f) * appear
                    val zScale = pageScaleFromZ(z)
                    scaleX = zScale
                    scaleY = zScale
                    val box = bounds.value
                    if (box.width > 0 && windowSize.width > 0) {
                        val centerX = box.left + box.width / 2f
                        val centerY = box.top + box.height / 2f
                        translationX = (1f - zScale) * (windowSize.width / 2f - centerX)
                        translationY = (1f - zScale) * (windowSize.height / 2f - centerY)
                    }
                    alpha = f * f * f
                }
            },
    ) {
        content()
    }
}

/**
 * 日期段头（9.9 `DividerItem`，2026-10-24 用户令按原版加回）：
 * 19sp、上下各 15dp、左内缩 17dp（`pinch_grid_divider_text_margin_*`），
 * 整块高度由几何给出（≈54.7dp），只画文字、没有底色。
 */
@Composable
private fun DateDividerHeader(title: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = DIVIDER_TEXT_SIZE,
                lineHeight = DIVIDER_TEXT_SIZE * 1.3f,
                letterSpacing = 0.sp,
            ),
            modifier = Modifier.padding(start = DIVIDER_TEXT_INSET, top = DIVIDER_TEXT_MARGIN),
        )
    }
}

/** 列表末尾的加载/错误状态（只在这两种情况下出现，正常结尾不留空白）。 */
@Composable
private fun GridFooter(append: LoadState, onRetry: () -> Unit) {    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (append) {
            is LoadState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )

            is LoadState.Error -> TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }

            else -> Unit
        }
    }
}

/** 供 UI 侧判断 banner 是否还在可视区（计时用），避免在组合体里读滚动状态。 */
@Composable
fun rememberBannerVisible(state: FractionalGridState, bannerHeightPx: Float): Boolean {
    val visible by remember(state, bannerHeightPx) {
        derivedStateOf { state.scrollOffsetPx < bannerHeightPx }
    }
    return visible
}
