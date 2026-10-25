package com.example.album.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasurePolicy
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScope
import androidx.compose.foundation.scrollableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 9.9 `GroupLayout extends InterpolateLayout` 的等价实现。
 *
 * 9.9 的做法是**每种列数各存一份 `GridLayout`**，`setPosition(小数)` 时在相邻两份布局之间**线性插值**
 * （所以双指换列时格子连续变大变小、行会错位，而不是"整层缩放"）。
 * 这里把同一套装配公式（`pinch_grid_padding = 3dp`、`p = base × (0.75/n + 0.25)`、`cell = (W + 2p)/n − 2p`）
 * 算到小数列数上：取 `n = floor(列数)`、`t = 小数部分`，逐项在"n 列布局"与"n+1 列布局"之间插值。
 *
 * 纯函数，不依赖 Android（JVM 单测覆盖）。
 */
class GridGeometry(
    /** 网格可用宽（px） */
    val widthPx: Float,
    /** 9.9 `pinch_grid_padding` 的 px 值 */
    val basePaddingPx: Float,
    /**
     * 视口高（px）：1 列版式里单张图"超高则等比夹取"的上限。
     * 9.9 用 `mGLHeightMinusMargins`（`LayoutSettings.java:143`），手机 `pinch_grid_margin_*` 全是 0dp
     * ⇒ 就等于视口高（`dimens.xml:97-101`）。传 0 表示不夹取。
     */
    val viewportHeightPx: Float = 0f,
) {

    /** 图间缝 = 2 × 调整后的 padding */
    fun gap(columns: Float): Float = 2f * basePaddingPx * (0.75f / columns + 0.25f)

    /** 单元边长：`(W + gap)/n − gap`（与 `Arrangement.spacedBy(gap)` 的列宽完全一致） */
    fun cell(columns: Float): Float {
        val gap = gap(columns)
        return (widthPx + gap) / columns - gap
    }

    /**
     * 组内第 [local] 项在**整数 [n] 列布局**里的矩形（相对组左上角）。
     *
     * = 9.9 `GridLayout.doLayout`（`GridLayout.java:441-453`）的等价物，**首段 2×2 没有任何特殊分支**——
     * 它只是"local == 0 用大格尺寸"+"其余项按 `slot = local + (local ≤ n−2 ? 1 : 3)` 取模"两条：
     *
     * ```
     * 大格 : (0, 0, 2·tile + gap)
     * 小格 : slot = local + (local <= n-2 ? 1 : 3)
     *        (slot % n)·pitch, (slot / n)·pitch, tile
     * ```
     *
     * 为什么必须有这个"索引跃迁"（用户 2026-10-24 报「首段右侧那两行 1×1 放大移出」）：
     * 大格罩住第 0 行第 0–1 列，所以第 1 行要从第 2 列继续；跨过 `n−2` 个"第 0 行右侧小格"之后，
     * 后面的项一律 +3 个槽位。**"首段"不是一组固定的 item**，它随列数换位（local 5 在 4 列时在首段外、
     * 在 5 列时进首段）——9.9 靠这条公式对任意下标都能给出合法位置，所以换位不需要任何 if。
     *
     * `n ≤ 2`（`mHaveRoadSign = n > 2`）时没有大格，整组就是普通正方形网格。
     */
    fun groupRectAt(local: Int, n: Int): CellRect {
        val columns = n.coerceAtLeast(1)
        val tile = cell(columns.toFloat())
        val gap = gap(columns.toFloat())
        val pitch = tile + gap
        if (columns <= 2) {
            return CellRect((local % columns) * pitch, (local / columns) * pitch, tile, tile)
        }
        if (local == 0) {
            val sign = 2f * tile + gap
            return CellRect(0f, 0f, sign, sign)
        }
        val slot = local + if (local <= columns - 2) 1 else 3
        return CellRect((slot % columns) * pitch, (slot / columns) * pitch, tile, tile)
    }

    /**
     * 组内第 [local] 项在**小数 [columns]** 下的矩形：在 `floor(n)` 与 `floor(n)+1` 两套整数布局之间
     * **逐项线性插值**（9.9 `InterpolateLayout.setLayout`，`InterpolateLayout.java:49-73`）。
     *
     * 这就是"换列时同一个 item 在两份布局之间迁移"的来源（也是用户说的"重叠动画"）：
     * 同一个 item 在 4 列与 5 列里可能完全不同位置，插值让它**平移过去**，而不是原地缩放。
     */
    fun rect(index: Int, columns: Float): CellRect {
        val lower = floor(columns).toInt().coerceAtLeast(1)
        val t = (columns - lower).coerceIn(0f, 1f)
        val a = groupRectAt(index, lower)
        if (t <= 0f) return a
        val b = groupRectAt(index, lower + 1)
        return CellRect(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            width = a.width + (b.width - a.width) * t,
            height = a.height + (b.height - a.height) * t,
        )
    }

    /**
     * 1 列版式（9.9 `LinearLayout.java:209-221`）：**图宽 = 满宽，图高 = 图宽 / 比例**；
     * 高超过视口高时**等比缩到视口高**（宽跟着变窄、水平居中）；项间缝 = `2p`（n = 1 ⇒ 6dp）。
     *
     * @param aspects 这一段里每一项的宽高比（宽/高），顺序与段内下标一致
     */
    fun linearLayout(aspects: FloatArray): LinearColumnLayout =
        LinearColumnLayout(widthPx, gap(1f), viewportHeightPx, aspects)

    /**
     * 1 列版式里某一项的取样边长 = `max(宽, 高)`：
     * 系统缩略图会把图**等比塞进正方形取样框**，所以取样框边长要取较长边，1 列才不会糊。
     */
    fun linearSamplePx(aspect: Float): Int {
        val a = aspect.takeIf { it > 0f } ?: 1f
        var height = widthPx / a
        var width = widthPx
        if (viewportHeightPx > 0f && height > viewportHeightPx) {
            width = widthPx * viewportHeightPx / height
            height = viewportHeightPx
        }
        return kotlin.math.max(width, height).roundToInt().coerceAtLeast(1)
    }

    /**
     * 一段 [count] 项在给定列数下占用的总高。
     *
     * 通常 = 最后一项的底边（与插值严格自洽）；但项数很少时（例如 4 列只有 2–3 张图），
     * 2×2 大格的下缘会**低于**最后一个普通项 ⇒ 必须取两者的较大值，否则这一段高度不够、大格被裁。
     */
    fun runHeight(count: Int, columns: Float): Float {
        if (count <= 0) return 0f
        val last = rect(count - 1, columns)
        val firstBottom = rect(0, columns).bottom
        return maxOf(last.y + last.height, firstBottom)
    }

    /**
     * 一段网格里与 `[top, bottom]` 相交的下标范围。
     *
     * @param runTop 这段网格自身的起点 y（内容坐标）
     */
    fun visibleRange(
        count: Int,
        columns: Float,
        runTop: Float,
        top: Float,
        bottom: Float,
    ): IntRange {
        if (count <= 0) return IntRange.EMPTY
        var lo = 0
        var hi = count - 1
        var first = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = rect(mid, columns)
            if (runTop + r.y + r.height >= top) {
                first = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        if (first < 0) return IntRange.EMPTY
        var last = first
        while (last < count - 1) {
            val r = rect(last + 1, columns)
            // 正好从视口下沿开始的那一行不算可见（bottom 是开区间）
            if (runTop + r.y >= bottom) break
            last++
        }
        return first..last
    }
}

/** 一个格子相对所在网格段左上角的矩形（px）；正方形格子 `width == height`。 */
data class CellRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    /** 正方形格子的边长（历史命名，单测与调用点都用它） */
    val size: Float get() = width
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/**
 * 1 列版式（9.9 `LinearLayout`）：图宽 = 满宽、图高 = 宽 / 比例，超高则等比夹到视口高并水平居中。
 *
 * 只在**列数 < 2** 时被构造：1 列与 2 列之间按 9.9 `InterpolateLayout` 的规则**逐项 lerp**
 * （见 [GridModel.runRect]），所以字段按段内下标存成平行数组，查询 O(1)。
 */
class LinearColumnLayout(
    private val widthPx: Float,
    gapPx: Float,
    private val maxHeightPx: Float,
    aspects: FloatArray,
) {
    private val lefts = FloatArray(aspects.size)
    private val tops = FloatArray(aspects.size)
    private val widths = FloatArray(aspects.size)
    private val heights = FloatArray(aspects.size)

    /** 这一段的总高 = 最后一项的底边 */
    val height: Float

    init {
        var y = 0f
        for (i in aspects.indices) {
            val aspect = aspects[i].takeIf { it > 0f } ?: 1f
            // 9.9 `LinearLayout.java:203-215`（用户 2026-10-18「改回原版行为」）：
            // 常规照片「宽 = 满宽、高 = 宽 / 比例」⇒ **各图等宽**；
            // 只有"高 > 视口可用高"的极长图（约 高/宽 > 2.18:1）才**等比缩窄并居中**到一屏高。
            var height = widthPx / aspect
            var width = widthPx
            var left = 0f
            if (maxHeightPx > 0f && height > maxHeightPx) {
                width = widthPx * maxHeightPx / height
                height = maxHeightPx
                left = (widthPx - width) * 0.5f
            }
            lefts[i] = left
            tops[i] = y
            widths[i] = width
            heights[i] = height
            y += height + gapPx
        }
        height = if (aspects.isEmpty()) 0f else tops[aspects.size - 1] + heights[aspects.size - 1]
    }

    fun rect(index: Int): CellRect =
        CellRect(lefts[index], tops[index], widths[index], heights[index])
}

/** 网格里的一个块（banner / 日期头 / 一段连续格子 / 页尾）。 */
enum class GridBlockKind { Banner, Divider, Run, Footer }

/**
 * 一个块。`firstItem`/`itemCount` 是它在**扁平 item 列表**里的范围（banner/页尾各 1 项，
 * 一段连续格子是若干项）。
 */
class GridBlock(
    val kind: GridBlockKind,
    val firstItem: Int,
    val itemCount: Int,
    val y: Float,
    val height: Float,
    /**
     * 1 列版式用：这一段每一项的宽高比（宽/高），只在列数 < 2 时被构造。
     * null = 没有比例信息（测试夹具 / 非格子块）⇒ 退回正方形版式。
     */
    val linear: LinearColumnLayout? = null,
)

/**
 * 一次布局的完整几何：块的顺序 + 每块的 y/高 + 内容总高。
 * 列数（小数）、宽度、行高参数一变就整体重建（块只有 banner / 一段格子 / 页尾，重建极便宜）。
 */
class GridModel(
    val blocks: List<GridBlock>,
    val geometry: GridGeometry,
    val columns: Float,
    val contentHeightPx: Float,
) {

    /** 第一个底边越过 [offsetPx] 的块（内容坐标）。 */
    fun blockIndexAt(offsetPx: Float): Int {
        var lo = 0
        var hi = blocks.lastIndex
        var found = blocks.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val b = blocks[mid]
            if (b.y + b.height >= offsetPx) {
                found = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return found
    }

    /**
     * 某一段里第 [local] 项（段内下标）的矩形。
     *
     * - 列数 ≥ 2：正方形版式，走 9.9 `GridLayout` 的插值公式（[GridGeometry.rect]）；
     * - 列数 < 2：在 **1 列原比例版式**（[GridBlock.linear]）与 **2 列正方形版式**之间
     *   按 9.9 `InterpolateLayout` 的规则逐项 lerp —— 所以双指从 2 列缩到 1 列时，
     *   格子会从正方形连续变成"满宽 + 按图比例变高"，横向位置从列位滑到居中。
     */
    fun runRect(block: GridBlock, local: Int): CellRect {
        val linear = block.linear ?: return geometry.rect(local, columns)
        val t = (columns - 1f).coerceIn(0f, 1f)
        val a = linear.rect(local)
        if (t <= 0f) return a
        val b = geometry.groupRectAt(local, 2)
        return CellRect(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            width = a.width + (b.width - a.width) * t,
            height = a.height + (b.height - a.height) * t,
        )
    }

    /**
     * 一段网格里与 `[top, bottom]` 相交的下标范围（[block] 必须是 Run）。
     *
     * ⚠️ `local == 0` 是 2×2 大格（`bottom` 比 `local == 1` 还大），**整段的 bottom 不再单调** ⇒
     * 二分只能用在 `local >= 1` 上，大格单独先判（它永远在最上面，`y = 0`）。
     */
    fun visibleRange(block: GridBlock, top: Float, bottom: Float): IntRange {
        val count = block.itemCount
        if (count <= 0) return IntRange.EMPTY
        // 大格跨两行：它自己先判
        if (block.y + runRect(block, 0).bottom >= top) {
            var last = 0
            while (last < count - 1) {
                val r = runRect(block, last + 1)
                if (block.y + r.y >= bottom) break
                last++
            }
            return 0..last
        }
        if (count <= 1) return IntRange.EMPTY
        var lo = 1
        var hi = count - 1
        var first = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = runRect(block, mid)
            if (block.y + r.bottom >= top) {
                first = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        if (first < 0) return IntRange.EMPTY
        var last = first
        while (last < count - 1) {
            val r = runRect(block, last + 1)
            // 正好从视口下沿开始的那一行不算可见（bottom 是开区间）
            if (block.y + r.y >= bottom) break
            last++
        }
        return first..last
    }

    /** 视口里最后一个可见 item 的扁平下标（预取用），没有则 -1。 */
    fun lastVisibleItemIndex(offsetPx: Float, viewportHeightPx: Float): Int {
        val bottom = offsetPx + viewportHeightPx
        var result = -1
        for (block in blocks) {
            if (block.y > bottom) break
            if (block.y + block.height < offsetPx) continue
            result = when (block.kind) {
                GridBlockKind.Run -> {
                    val range = visibleRange(block = block, top = offsetPx, bottom = bottom)
                    if (range.isEmpty()) result else block.firstItem + range.last
                }

                GridBlockKind.Banner, GridBlockKind.Divider, GridBlockKind.Footer -> block.firstItem
            }
        }
        return result
    }

    /** 某个扁平 item 的矩形（内容坐标；手势里定位"指下的那一项"）；不在模型里返回 null。 */
    fun rectOf(itemIndex: Int): ItemRect? {
        for (block in blocks) {
            if (itemIndex < block.firstItem) break
            if (itemIndex >= block.firstItem + block.itemCount) continue
            if (block.kind == GridBlockKind.Run) {
                val r = runRect(block, itemIndex - block.firstItem)
                return ItemRect(r.x, block.y + r.y, r.width, r.height)
            }
            return ItemRect(0f, block.y, geometry.widthPx, block.height)
        }
        return null
    }

    /**
     * 命中测试：内容坐标 ([x], [y]) 落在哪个扁平 item 上（双指换列的"指下那一项"）。
     *
     * 先按 y 二分到行，再在同一行里取**水平方向最近**的那一格 ——
     * 小数列数下同一行的格子高度也略有差异，所以用"距离最近"而不是严格的包含判断。
     */
    fun itemIndexAt(x: Float, y: Float): Int {
        val index = blockIndexAt(y)
        val block = blocks.getOrNull(index) ?: return -1
        if (block.kind != GridBlockKind.Run) return block.firstItem

        // 行定位：`y`（top）对 local 是**非递减**的（大格与第 1 行的小格都是 0）
        var lo = 0
        var hi = block.itemCount - 1
        var rowStart = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = runRect(block, mid)
            if (block.y + r.y <= y) {
                rowStart = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        // 二分落在"该行的任意一项"上：退回这一行的第一项（每行最多 7 项，退几步很便宜）
        val rowTop = block.y + runRect(block, rowStart).y
        while (rowStart > 0 && block.y + runRect(block, rowStart - 1).y >= rowTop) rowStart--
        // 同一行里取水平方向最近的一格
        var best = rowStart
        var bestDistance = Float.MAX_VALUE
        var i = rowStart
        while (i < block.itemCount) {
            val r = runRect(block, i)
            if (block.y + r.y > rowTop) break
            val distance = kotlin.math.abs(r.x + r.width / 2f - x)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
            i++
        }
        // 大格（local 0）跨两行：触点落在它矩形内时也参与比较
        if (block.itemCount > 1) {
            val big = runRect(block, 0)
            val inBig = x >= big.x && x <= big.x + big.width &&
                y >= block.y + big.y && y <= block.y + big.bottom
            if (inBig) {
                val distance = kotlin.math.abs(big.x + big.width / 2f - x)
                if (distance < bestDistance) best = 0
            }
        }
        return block.firstItem + best
    }
}

/** 一个 item 的矩形（内容坐标，未减滚动量）。 */
data class ItemRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * 自绘 lazy 网格的滚动状态。
 *
 * 只存一个滚动量（px），内容总高 / 视口高由 measure 写入（普通字段：手势里读，不需要触发重组）。
 * `scrollOffsetPx`、`lastVisibleItemIndex` 是 Compose 状态：前者给 banner/视差读，
 * 后者由 measure 写、由组合读（只在真正变化时才触发重组/预取）。
 */
@Stable
class FractionalGridState(initialOffset: Float = 0f) : ScrollableState {

    var scrollOffsetPx by mutableFloatStateOf(initialOffset)
        internal set

    var contentHeightPx: Float = 0f
        internal set

    var viewportHeightPx: Float = 0f
        internal set

    /** measure 写：最后一个可见 item 的扁平下标（预取用） */
    var lastVisibleItemIndex: Int by mutableIntStateOf(-1)
        internal set

    /** 最近一次 measure 的几何（手势里做"指下是哪一项"的命中测试） */
    var model: GridModel? = null
        internal set

    // ---------- 捏合：锚点（9.9 `followItem` + `adjustFollowItem`）----------

    /**
     * 捏合时"指下那一项"的扁平下标（-1 = 没有）：捏合期间滚动量由它**反算**，
     * 所以它在屏幕上的位置不随列数变化而漂走（9.9 `ScrollContainerUiBase.java:1286-1305`）。
     *
     * ⚠️ 这些锚点/焦点字段都必须是**快照状态**：它们在 measure 里被读，手势写它们时要触发重新测量，
     * 否则"只抬手指不缩放"（span 不变）那一帧画面不会跟手。
     */
    internal var pinchAnchorItem: Int by mutableIntStateOf(-1)

    /** 锚点项的中心在屏幕上的 y（相对视口顶）；手势的质心纵向位移直接加在它上面 */
    internal var pinchAnchorCenterScreenY: Float by mutableFloatStateOf(0f)

    // ---------- 捏合：焦点项（9.9 `PinchFocusAnimation`）----------

    /** 手势里的"指下那一项"（-1 = 没有）：捏合时它会盖住邻居（9.9 给它抬了 z） */
    internal var focusItem: Int by mutableIntStateOf(-1)

    /** 焦点项的媒体 id（-1 = 焦点不在媒体格上，例如 banner ⇒ 9.9 也不给焦点动画） */
    internal var focusMediaId: Long by mutableLongStateOf(-1L)

    /** 焦点项的旋转角（度；Compose 约定顺时针为正） */
    internal var focusRotation: Float by mutableFloatStateOf(0f)

    /** 焦点项的 z 抬升（透视 `1/(1−z)`；9.9 `MIN_Z_OFFSET = 0.015`） */
    internal var focusZ: Float by mutableFloatStateOf(0f)

    /** 质心的横向累计位移（px，相对手势起点）—— measure 用它算焦点项该摆到哪 */
    internal var focusFingerDx: Float by mutableFloatStateOf(0f)

    /** 手势开始时焦点项的屏幕中心 x（9.9 `PinchFocusAnimation.mStartX`） */
    internal var focusStartCenterX: Float by mutableFloatStateOf(0f)

    private var focusDecayJob: Job? = null

    var scrolling by mutableStateOf(false)
        private set

    /**
     * 捏合（或松手后的 snap 弹簧）正在进行：捏合期间滚动量是**由锚点每帧反算**的，
     * 手指抖动会让它小幅上下摆动 ⇒ 任何"按滚动方向做判断"的地方（首页胶囊收起/出现）都必须避开这段。
     */
    internal var pinching by mutableStateOf(false)

    /**
     * "滑行中按下 ⇒ 这一次 up 不算点击"（9.9 `ScrollUiBase.cancelActiveMovements()` 一票否决点击，
     * `ScrollUiBase.java:301-323,386`）。
     *
     * ⚠️ 必须由**按下那一帧**判断（那时 fling 还在跑），不能等抬起时再看（那时已经停了）。
     * 网格的 `pointerInput`（Initial pass）负责写它，格子的 `onClick` 消费它。
     */
    internal var suppressTap by mutableStateOf(false)

    /**
     * 平台手势滚动（拖动/fling）是否在进行。
     *
     * ⚠️ 不能只看 [scrolling]：那是 `DragInteraction` 的镜像，**抬手瞬间就变 false**，
     * 而滑行（fling）恰好从抬手开始 ⇒ 以前"滑行中按下"读到的是 false，E4 等于没做
     * （用户 2026-10-24：「E4 还是能打开」）。现在 fling 期间由 [FractionalGrid] 的 fling 包装器置位。
     */
    internal var flinging by mutableStateOf(false)

    /** 按下时记录"此刻是否在滑行"；供格子点击时判断这一次要不要吞掉 */
    /** 最近一次真正发生滚动的时刻（ms）：fling 也走 [dispatchRawDelta] ⇒ 用它兜住"滑行中" */
    private var lastScrollAtMillis = 0L

    internal fun onPointerDown() {
        val sinceScroll = System.nanoTime() / 1_000_000 - lastScrollAtMillis
        suppressTap = scrolling || flinging || sinceScroll <= TAP_SUPPRESS_WINDOW_MILLIS
    }

    /** 点击卫兵：返回 true = 这一次点击来自"滑行中按下"，应当吞掉（只吞一次） */
    internal fun consumeSuppressedTap(): Boolean {
        if (!suppressTap) return false
        suppressTap = false
        return true
    }

    private val mutex = MutatorMutex()

    private val scrollScope = object : ScrollScope {
        override fun scrollBy(pixels: Float): Float = dispatchRawDelta(pixels)
    }

    val maxScrollPx: Float get() = (contentHeightPx - viewportHeightPx).coerceAtLeast(0f)

    override val isScrollInProgress: Boolean get() = scrolling

    override val canScrollForward: Boolean get() = scrollOffsetPx < maxScrollPx - 0.5f

    override val canScrollBackward: Boolean get() = scrollOffsetPx > 0.5f

    override fun dispatchRawDelta(delta: Float): Float {
        val old = scrollOffsetPx
        val next = (old + delta).coerceIn(0f, maxScrollPx)
        if (next != old) {
            scrollOffsetPx = next
            lastScrollAtMillis = System.nanoTime() / 1_000_000
        }
        return next - old
    }

    override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) {
        mutex.mutate(scrollPriority) { scrollScope.block() }
    }

    /** 手势里的"跟手滚动"：不经过 fling/overscroll，直接把指尖的位移搬到滚动量上。 */
    internal fun scrollByPixels(delta: Float) {
        if (delta == 0f) return
        scrollOffsetPx = (scrollOffsetPx + delta).coerceIn(0f, maxScrollPx)
    }

    /**
     * 把某个 item 垂直居中（查看器关闭后回到"刚才看的那张"）。
     *
     * 9.9 `ScrollResumeInfo` 只记 index，返回时 `f = −target[1]` 把该项摆到视口中间
     * （`ScrollContainerUiBase.java:1930-1996`）。这里按当前几何直接反算滚动量。
     */
    internal fun centerOnItem(itemIndex: Int): Boolean {
        val model = model ?: return false
        if (viewportHeightPx <= 0f) return false
        val rect = model.rectOf(itemIndex) ?: return false
        val target = rect.top + rect.height / 2f - viewportHeightPx / 2f
        clearPinchAnchor()
        scrollOffsetPx = target.coerceIn(0f, maxScrollPx)
        return true
    }

    internal fun setScrolling(value: Boolean) {
        scrolling = value
    }

    // ---------- 捏合：锚点 / 焦点项 ----------

    /**
     * 捏合开始：定位"指下那一项"。
     *
     * - **锚点** = 9.9 `followItem`（`ScrollContainerUiBase.java:1746-1779`）：记下它的中心在屏幕上的 y，
     *   之后每帧由它的新位置反算滚动量；
     * - **焦点项** = 9.9 `PinchFocusAnimation.reset`（`PinchGridContainerUiBase.java:503-527`）：
     *   只有"指下是媒体格"才有（原版要求 `uiBase instanceof GridItem`，banner 不参与）。
     *
     * @param mediaIdOf 扁平下标 → 媒体 id（banner/页尾返回 -1）
     */
    internal fun beginPinch(centroidX: Float, centroidY: Float, mediaIdOf: (Int) -> Long) {
        val model = model ?: return
        var index = model.itemIndexAt(centroidX, centroidY + scrollOffsetPx)
        if (index < 0) {
            // 9.9 `followItem(null)` 的回退：指缝落在空白处就锚到**视口中间那一项**
            // （`ScrollContainerUiBase.java:1749-1753`）。否则捏合时画面会围绕滚动量乱跑。
            index = model.itemIndexAt(
                model.geometry.widthPx * 0.5f,
                scrollOffsetPx + viewportHeightPx * 0.5f,
            )
        }
        val rect = if (index >= 0) model.rectOf(index) else null
        if (rect == null) {
            clearPinch()
            return
        }
        pinchAnchorItem = index
        pinchAnchorCenterScreenY = rect.top + rect.height * 0.5f - scrollOffsetPx

        focusDecayJob?.cancel()
        focusDecayJob = null
        val mediaId = mediaIdOf(index)
        if (mediaId >= 0L) {
            focusItem = index
            focusMediaId = mediaId
            focusStartCenterX = rect.left + rect.width * 0.5f
            focusFingerDx = 0f
            focusRotation = 0f
            focusZ = PINCH_FOCUS_MIN_Z
        } else {
            focusItem = -1
            focusMediaId = -1L
        }
    }

    /**
     * 单指横滑换列的锚点：取**视口中间那一项**（9.9 `onFling` 里 `followItem(null)` =
     * active 列表中间项 ⇒ 这次缩放围绕屏幕中间进行，不是围绕手指）。
     */
    internal fun beginFlingAnchor() {
        val model = model ?: return
        val index = model.itemIndexAt(0f, scrollOffsetPx + viewportHeightPx * 0.5f)
        val rect = if (index >= 0) model.rectOf(index) else null
        if (rect == null) {
            clearPinchAnchor()
            return
        }
        pinchAnchorItem = index
        pinchAnchorCenterScreenY = rect.top + rect.height * 0.5f - scrollOffsetPx
    }

    /** 质心纵向位移 ⇒ 锚点的屏幕 y 跟着手指走（9.9 `adjustFollowItem`，`ScrollContainerUiBase.java:1740-1744`） */
    internal fun followPinchY(dy: Float) {
        if (pinchAnchorItem >= 0) pinchAnchorCenterScreenY += dy
    }

    /** 质心横向累计位移 ⇒ 焦点项横向跟手（9.9 `PinchFocusAnimation.updatePath` 的 f5） */
    internal fun followPinchX(dx: Float) {
        if (focusItem >= 0) focusFingerDx = dx
    }

    internal fun applyFocusRotation(degrees: Float) {
        if (focusItem >= 0) focusRotation = degrees
    }

    /**
     * 松手：焦点项的旋转/横向位移用 300ms easeOut 归位
     * （9.9 `stopFocusItem` + `PinchFocusAnimation.updatePath` 的 `1−f` 衰减：`PinchGridContainerUiBase.java:545-562, 636-654`）。
     *
     * ⚠️ **不能同时清锚点**：9.9 的 `stopFollowItem` 发生在 snap 弹簧**跑完**时（`onEvent(1)`），
     * 所以松手后那段收敛弹簧期间，滚动量仍然由锚点反算（否则提交那一下画面会跳）。
     * 这里只做焦点归位；锚点由 [clearPinchAnchor] 在弹簧结束时清。
     */
    internal fun finishFocus(scope: CoroutineScope) {
        if (focusItem < 0) return
        val rotation0 = focusRotation
        val dx0 = focusFingerDx
        focusDecayJob?.cancel()
        focusDecayJob = scope.launch {
            // 9.9 的曲线：进度用 easeOut `1−(1−t)³` 喂进去、残差 = `1−f` ⇒ 实际衰减 = `(1−t)³`
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(PINCH_FOCUS_DECAY_MILLIS, easing = LinearEasing),
            ) { t, _ ->
                val k = (1f - t) * (1f - t) * (1f - t)
                focusRotation = rotation0 * k
                focusFingerDx = dx0 * k
            }
            focusItem = -1
            focusMediaId = -1L
            focusRotation = 0f
            focusFingerDx = 0f
            focusZ = 0f
        }
    }

    /** 清锚点（9.9 `stopFollowItem`）：snap 弹簧跑完时调用，此后滚动量恢复自由。 */
    internal fun clearPinchAnchor() {
        pinchAnchorItem = -1
    }

    private fun clearPinch() {
        pinchAnchorItem = -1
        focusItem = -1
        focusMediaId = -1L
        focusRotation = 0f
        focusFingerDx = 0f
        focusZ = 0f
    }

    companion object {
        /** 按下时距最近一次滚动 ≤ 这个窗口 ⇒ 认为"是在滑行中按下"，这一次 up 不算点击（E4） */
        private const val TAP_SUPPRESS_WINDOW_MILLIS = 180L

        val Saver: Saver<FractionalGridState, Float> = Saver(
            save = { it.scrollOffsetPx },
            restore = { FractionalGridState(it) },
        )
    }
}

@Composable
fun rememberFractionalGridState(): FractionalGridState =
    rememberSaveable(saver = FractionalGridState.Saver) { FractionalGridState() }

/** 一次 measure 里要摆的一个 item。 */
private class SlotPlacement(
    val itemIndex: Int,
    val kind: GridBlockKind,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    var placeable: Placeable? = null
}

/**
 * 自绘 lazy 网格宿主。
 *
 * 用官方低层 API `LazyLayout`（`LazyList` / `LazyGrid` 自己也是建在它上面）：
 * 只组合/测量视口内的 item，位置由 [buildModel] 解析算出 —— 这样列数可以是小数、逐帧插值重排，
 * 同时保留懒加载与 `itemProvider` 的 key 复用（共享元素、按压动画都不受影响）。
 *
 * 关键点：**列数与几何变化只触发 remeasure，不触发重组**（模型在 measure 里算），
 * 所以双指换列时不会把整屏 item 重新组合一遍。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FractionalGrid(
    slots: List<Slot>,
    state: FractionalGridState,
    columns: () -> Float,
    buildModel: (Float) -> GridModel,
    overscrollEffect: OverscrollEffect?,
    bannerOverlapPx: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Slot) -> Unit,
) {
    val contentState = rememberUpdatedState(content)
    val provider = remember(slots) {
        SlotItemProvider(slots) { slot -> contentState.value(slot) }
    }
    val policy = GridMeasurePolicy(buildModel, columns, state, bannerOverlapPx)
    val interaction = remember { MutableInteractionSource() }
    // fling 距离按用户令**回滚到平台默认**（2026-10-24「E3：滑行距离回滚到之前」，
    // 撤销 E3 那条按 9.9 friction=0.009 放长 1.45 倍的实现）。外面只包一层"正在滑行"标记给 E4 用。
    val baseFling = ScrollableDefaults.flingBehavior()
    val fling = remember(baseFling, state) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                state.flinging = true
                return try {
                    with(baseFling) { performFling(initialVelocity) }
                } finally {
                    state.flinging = false
                }
            }
        }
    }
    val scope = rememberCoroutineScope()
    val viewportWidth = LocalWindowInfo.current.containerSize.width.toFloat()

    LaunchedEffect(interaction, state) {
        interaction.interactions.collect { value ->
            when (value) {
                is DragInteraction.Start -> state.setScrolling(true)
                is DragInteraction.Stop, is DragInteraction.Cancel -> state.setScrolling(false)
                else -> Unit
            }
        }
    }

    // 两端越界都交给平台 stretch（用户 2026-10-24：「把 banner 下拉的仿原版效果删了吧，回滚到之前的拉伸回弹」）
    val effect = overscrollEffect

    LazyLayout(
        itemProvider = { provider },
        modifier = modifier
            // 滑行中按下 ⇒ 吞掉这一次点击（9.9 `cancelActiveMovements()`）
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) state.onPointerDown()
                    }
                }
            }
            .scrollableArea(
                state = state,
                orientation = Orientation.Vertical,
                overscrollEffect = effect,
                interactionSource = interaction,
                flingBehavior = fling,
            ),
        measurePolicy = policy,
    )
}

/** 扁平 item 列表 → `LazyLayoutItemProvider`（key 沿用原来的字符串，共享元素靠它配对）。 */
private class SlotItemProvider(
    private val slots: List<Slot>,
    private val content: @Composable (Slot) -> Unit,
) : LazyLayoutItemProvider {

    override val itemCount: Int get() = slots.size

    override fun getKey(index: Int): Any = slots[index].key

    override fun getContentType(index: Int): Any = slots[index].contentType

    @Composable
    override fun Item(index: Int, key: Any) {
        content(slots[index])
    }
}

/** 测量：把几何模型解析成视口内的 item 位置、只组合/测量这些 item。 */
@OptIn(ExperimentalFoundationApi::class)
private class GridMeasurePolicy(
    private val buildModel: (Float) -> GridModel,
    private val columns: () -> Float,
    private val state: FractionalGridState,
    private val bannerOverlapPx: Int,
) : LazyLayoutMeasurePolicy {

    override fun LazyLayoutMeasureScope.measure(constraints: Constraints): MeasureResult {
        val viewportWidth = constraints.maxWidth
        val viewportHeight = constraints.maxHeight
        val placements = ArrayList<SlotPlacement>(32)

        val model = buildModel(columns())
        state.model = model
        state.contentHeightPx = model.contentHeightPx
        state.viewportHeightPx = viewportHeight.toFloat()

        // 内容变短（换列、刷新、数据减少）时把滚动量夹回合法范围。
        // 放在 measure 里而不是副作用里：夹取必须与本次几何一致，否则会露出一段空白。
        var offset = state.scrollOffsetPx.coerceIn(0f, state.maxScrollPx)
        // 捏合期间：滚动量由**锚点项**反算（9.9 `invalidate(1, …)` 的
        // `f = (mTop − target[Y]) + mFollowedItemOffset`，`ScrollContainerUiBase.java:1286-1305`）。
        // 没有这一步，格子缩放时"指下那张照片"会按内容坐标漂走（用户 2026-10-24 报「缩放动画还是不如原版」）。
        if (state.pinchAnchorItem >= 0) {
            val anchor = model.rectOf(state.pinchAnchorItem)
            if (anchor != null) {
                offset = (anchor.top + anchor.height * 0.5f - state.pinchAnchorCenterScreenY)
                    .coerceIn(0f, state.maxScrollPx)
            }
        }
        if (offset != state.scrollOffsetPx) state.scrollOffsetPx = offset
        val top = offset
        val bottom = offset + viewportHeight
        for (block in model.blocks) {
            if (block.y + block.height < top) continue
            if (block.y > bottom) break
            when (block.kind) {
                GridBlockKind.Run -> {
                    val range = model.visibleRange(block = block, top = top, bottom = bottom)
                    for (i in range) {
                        val r = model.runRect(block, i)
                        placements += SlotPlacement(
                            itemIndex = block.firstItem + i,
                            kind = block.kind,
                            x = r.x.roundToInt(),
                            y = (block.y + r.y).roundToInt(),
                            width = r.width.roundToInt().coerceAtLeast(1),
                            height = r.height.roundToInt().coerceAtLeast(1),
                        )
                    }
                }

                GridBlockKind.Banner -> {
                    // banner 自己会把上报高度减掉 2dp 的重叠：给它多一点高度约束，内容才能画满
                    placements += SlotPlacement(
                        itemIndex = block.firstItem,
                        kind = block.kind,
                        x = 0,
                        y = block.y.roundToInt(),
                        width = viewportWidth,
                        height = block.height.roundToInt() + bannerOverlapPx,
                    )
                }

                else -> {
                    placements += SlotPlacement(
                        itemIndex = block.firstItem,
                        kind = block.kind,
                        x = 0,
                        y = block.y.roundToInt(),
                        width = viewportWidth,
                        height = block.height.roundToInt(),
                    )
                }
            }
        }

        // 指下的那一项最后摆：换列时它会盖住邻居（9.9 给它抬了 z）
        val focusItem = state.focusItem
        val ordered = if (focusItem >= 0) {
            placements.sortedBy { if (it.itemIndex == focusItem) 1 else 0 }
        } else {
            placements
        }

        for (placement in ordered) {
            val measurables = compose(placement.itemIndex)
            val itemConstraints = if (placement.kind == GridBlockKind.Banner) {
                Constraints(
                    minWidth = 0,
                    maxWidth = placement.width,
                    minHeight = 0,
                    maxHeight = placement.height,
                )
            } else {
                Constraints.fixed(placement.width, placement.height)
            }
            placement.placeable = measurables.firstOrNull()?.measure(itemConstraints)
        }

        state.contentHeightPx = model.contentHeightPx
        state.viewportHeightPx = viewportHeight.toFloat()
        state.lastVisibleItemIndex = model.lastVisibleItemIndex(offset, viewportHeight.toFloat())

        return layout(viewportWidth, viewportHeight) {
            val offsetInt = offset.roundToInt()
            for (placement in ordered) {
                val placeable = placement.placeable ?: continue
                // 焦点项横向跟手（9.9 `PinchFocusAnimation.updatePath` 的 `−mXCurrentDiff` 平移，
                // 边界夹在 `0.985·W` 内）。只动这一项，整页不动。
                val focusDx = if (state.focusItem >= 0 && placement.itemIndex == state.focusItem) {
                    val center = placement.x + placement.width * 0.5f
                    val target = focusTargetCenterX(
                        startCenterX = state.focusStartCenterX,
                        fingerDx = state.focusFingerDx,
                        width = placement.width.toFloat(),
                        viewportWidth = viewportWidth.toFloat(),
                    )
                    (target - center).roundToInt()
                } else {
                    0
                }
                placeable.place(placement.x + focusDx, placement.y - offsetInt)
            }
        }
    }
}
