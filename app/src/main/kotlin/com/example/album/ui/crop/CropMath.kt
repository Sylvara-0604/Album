package com.example.album.ui.crop

/**
 * 裁剪框：坐标一律是"图片显示矩形内的像素"——原点在图片显示矩形的左上角，不是屏幕/窗口坐标。
 *
 * 故意不用 `androidx.compose.ui.geometry.Rect`：这个文件必须是**纯 Kotlin**，
 * 才能让 [CropMath] 的换算在纯 JVM 单测里跑（不启 Robolectric、不碰任何 Android 类）。
 * 显示层只需要读 4 个 Float 画线，换算层只需要这 4 个 Float 做四则运算。
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** 宽高都 > 0 才算量过。视口尺寸还没测量到、或预览位图还没解出来时用 [Zero]。 */
    val isValid: Boolean get() = width > 0f && height > 0f

    companion object {
        val Zero = CropRect(0f, 0f, 0f, 0f)
    }
}

/** 8 个可拖点：4 角 + 4 边。拖角时**对角**是锚点，拖边时**对边**是锚点。 */
enum class Handle { TopLeft, Top, TopRight, Right, BottomRight, Bottom, BottomLeft, Left }

/** 初始裁剪框相对图片显示区每边的内缩比例（留一圈看得见图片本身，方便判断裁到哪儿）。 */
private const val INITIAL_INSET_RATIO = 0.05f

/**
 * 裁剪框的自由拖拽换算（蓝图 P5：**不设任何固定比例**）。
 *
 * 所有函数都是纯函数：入参是"当前框 + 手势增量 + 图片显示区 + 最小尺寸"，出参是新框。
 * 三条不变量由 [CropMath] 统一保证，UI 层不必再判：
 * 1. 框永远在 `bounds` 内（越界钳制）；
 * 2. 宽高不小于 `minSizePx`（屏幕侧的 48dp 换算成像素后传进来；`bounds` 本身比它小时以 `bounds` 为准）；
 * 3. 拖角/拖边时**只动被拖的那条边**，锚点边保持原值。
 *
 * 单位统一为像素：调用方用 `density` 把 48.dp 换成 `minSizePx`，本类不做 dp 换算（保持纯逻辑）。
 */
object CropMath {

    /**
     * 拖手柄改框：8 个手柄各自只移动自己的边，对边/对角因此天然固定。
     * 每条被拖的边先按 `minSizePx` 夹（防止拖成 0），再按 `bounds` 夹（防止拖出图片显示区）。
     */
    fun resize(
        current: CropRect,
        handle: Handle,
        deltaX: Float,
        deltaY: Float,
        bounds: CropRect,
        minSizePx: Float,
    ): CropRect {
        if (!bounds.isValid) return current
        val minWidth = effectiveMin(bounds.width, minSizePx)
        val minHeight = effectiveMin(bounds.height, minSizePx)
        // 先把传入的框归一（可能在转屏瞬间比 bounds 还大），再叠手势增量
        val base = clamp(current, bounds, minSizePx)
        var left = base.left
        var top = base.top
        var right = base.right
        var bottom = base.bottom
        when (handle) {
            Handle.TopLeft -> {
                left = (left + deltaX).coerceIn(bounds.left, right - minWidth)
                top = (top + deltaY).coerceIn(bounds.top, bottom - minHeight)
            }

            Handle.Top -> top = (top + deltaY).coerceIn(bounds.top, bottom - minHeight)

            Handle.TopRight -> {
                right = (right + deltaX).coerceIn(left + minWidth, bounds.right)
                top = (top + deltaY).coerceIn(bounds.top, bottom - minHeight)
            }

            Handle.Right -> right = (right + deltaX).coerceIn(left + minWidth, bounds.right)

            Handle.BottomRight -> {
                right = (right + deltaX).coerceIn(left + minWidth, bounds.right)
                bottom = (bottom + deltaY).coerceIn(top + minHeight, bounds.bottom)
            }

            Handle.Bottom -> bottom = (bottom + deltaY).coerceIn(top + minHeight, bounds.bottom)

            Handle.BottomLeft -> {
                left = (left + deltaX).coerceIn(bounds.left, right - minWidth)
                bottom = (bottom + deltaY).coerceIn(top + minHeight, bounds.bottom)
            }

            Handle.Left -> left = (left + deltaX).coerceIn(bounds.left, right - minWidth)
        }
        return CropRect(left, top, right, bottom)
    }

    /**
     * 框内拖动 = 整框平移：**只改位置不改尺寸**。
     * 到边就贴边停住（而不是弹回原位），手感与查看器里平移图片一致。
     */
    fun move(current: CropRect, deltaX: Float, deltaY: Float, bounds: CropRect): CropRect {
        if (!bounds.isValid) return current
        // 尺寸先夹进 bounds：视口比框还小的退化情况下，不能让 right/top 算出空区间
        val width = current.width.coerceIn(0f, bounds.width)
        val height = current.height.coerceIn(0f, bounds.height)
        val left = (current.left + deltaX).coerceIn(bounds.left, bounds.right - width)
        val top = (current.top + deltaY).coerceIn(bounds.top, bounds.bottom - height)
        return CropRect(left, top, left + width, top + height)
    }

    /**
     * 双指缩放整框：以框中心为基准**等比**放大/缩小，倍率被"最小尺寸"和"图片显示区"两头夹住。
     * 宽高用同一个倍率 ⇒ 用户自己拖出来的比例在缩放时保持不变。
     */
    fun scale(current: CropRect, zoom: Float, bounds: CropRect, minSizePx: Float): CropRect {
        if (!bounds.isValid || !current.isValid || zoom <= 0f) return current
        val minWidth = effectiveMin(bounds.width, minSizePx)
        val minHeight = effectiveMin(bounds.height, minSizePx)
        val minFactor = maxOf(minWidth / current.width, minHeight / current.height)
        val maxFactor = minOf(bounds.width / current.width, bounds.height / current.height)
        val factor = zoom.coerceIn(minFactor, maxFactor.coerceAtLeast(minFactor))
        val width = current.width * factor
        val height = current.height * factor
        val centerX = (current.left + current.right) / 2f
        val centerY = (current.top + current.bottom) / 2f
        val left = (centerX - width / 2f).coerceIn(bounds.left, bounds.right - width)
        val top = (centerY - height / 2f).coerceIn(bounds.top, bounds.bottom - height)
        return CropRect(left, top, left + width, top + height)
    }

    /**
     * 归一：尺寸至少 [minSizePx]、且整体放进 [bounds]。
     *
     * 做法是"保尺寸、微调位置"，而不是"压扁到 bounds 里"——转屏后框的比例是用户的选择，
     * 宁可贴着边走，也不要把它拉变形。
     */
    fun clamp(current: CropRect, bounds: CropRect, minSizePx: Float): CropRect {
        if (!bounds.isValid) return CropRect.Zero
        val minWidth = effectiveMin(bounds.width, minSizePx)
        val minHeight = effectiveMin(bounds.height, minSizePx)
        val width = current.width.coerceIn(minWidth, bounds.width)
        val height = current.height.coerceIn(minHeight, bounds.height)
        val left = current.left.coerceIn(bounds.left, bounds.right - width)
        val top = current.top.coerceIn(bounds.top, bounds.bottom - height)
        return CropRect(left, top, left + width, top + height)
    }

    /** 进页面的初始框：图片显示区四周各内缩 [INITIAL_INSET_RATIO]。 */
    fun initial(bounds: CropRect): CropRect {
        if (!bounds.isValid) return CropRect.Zero
        val insetX = bounds.width * INITIAL_INSET_RATIO
        val insetY = bounds.height * INITIAL_INSET_RATIO
        return CropRect(bounds.left + insetX, bounds.top + insetY, bounds.right - insetX, bounds.bottom - insetY)
    }

    /** 点是否落在框内（框内按下 = 整框平移；手柄的判定在 UI 层按 48dp 热区另算）。 */
    fun contains(rect: CropRect, x: Float, y: Float): Boolean =
        rect.isValid && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom

    /** 最小边长的实际取值：图片显示区本身比 48dp 还小时，以显示区为止（否则会得到空区间）。 */
    private fun effectiveMin(boundsSize: Float, minSizePx: Float): Float =
        if (minSizePx.isNaN()) 0f else minSizePx.coerceIn(0f, boundsSize)
}
