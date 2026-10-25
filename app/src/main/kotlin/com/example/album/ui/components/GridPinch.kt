package com.example.album.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.example.album.data.GridColumnPreference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 双指换列的手势状态 —— **照 9.9 `PinchGridContainerUiBase` 的真机制**。
 *
 * 9.9 的做法（反编译 `handlePinch` / `PinchAnimation`）：
 * 1. 手势开始记 `magnifyFactor = rawColumns × 起始span`，之后逐帧 `列数 = magnifyFactor / 当前span`
 *    （= `起始列数 / 累计缩放比`），**列数是小数**，交给 `GroupLayout.setPosition(小数)` 逐帧插值重排；
 * 2. 列数夹在 `[1, 最大列数]`；
 * 3. 平滑：`当前 += (目标 − 当前) / inertia`，`inertia = clamp((1 − |目标 − 当前|) × 25, 5, 25)`
 *    —— 跟手但带一点阻尼，不会抖；
 * 4. 松手：两次移动事件间隔 > 500ms（手指停住过）用 `round`；否则**朝手势方向再走一格**
 *    （格子变大 = `floor`，格子变小 = `ceil`），再夹到 1..7，用 **spring(k=100, friction=16)** 收敛。
 *
 * ⚠️ 2026-10-24 用户令（r33）：**两端额外的"加戏"全部删除** ——
 * ① 「继续放大越过 1 列 ⇒ 黑幕 + 松手打开查看器」整条机制删除（单列时无法继续放大）；
 * ② 「缩到最大列数后整页 z 后推 −0.25 再弹回」删除。
 * 两端现在都只是**夹住**，画面只做列数插值。
 *
 * ⚠️ r36 修正（用户 2026-10-24「缩放动画还是不如原版」+「移开手指不会顺滑停止」）：下面三条
 * 以前是"写了但没接上/按事件驱动"，等于整条机制缺失（取证见 `docs/research/PINCH_MECHANICS.md`）：
 * ① 平滑必须**每帧推进**（9.9 `PinchAnimation` 是 `setLoop(true)` 的动画路径，`PinchGridContainerUiBase.java:409`）；
 *    原来只在指针事件里推一步 ⇒ 手指一停/一抬，`display` 立刻冻结 = 用户说的"突然顿一下"。
 * ② 捏合期间滚动量要由**指下那一项的屏幕位置**反算（9.9 `followItem` + `adjustFollowItem` +
 *    `invalidate` 的 `f = (mTop − target[Y]) + mFollowedItemOffset`，`ScrollContainerUiBase.java:1286-1305`、
 *    `1740-1779`）；原来只把质心 dy 加到滚动量、而且 `onFollow` 根本没接 ⇒ 缩放围绕"滚动量"进行，
 *    指下的照片会自己漂走。
 * ③ 焦点项的旋转/横向跟手（9.9 `PinchFocusAnimation`，`PinchGridContainerUiBase.java:470-656`）
 *    在 r27 被整条删掉了，现在按原版恢复（只作用于**焦点那一项**，不是整页）。
 */
class PinchColumnsState {

    /** 平滑后的显示列数（小数）；`pinching`/`settling` 期间生效，否则读提交后的整数列 */
    // 初值用"起始列数"（3）：改成 1 会让首帧按 1 列算几何，首格 2×2 直接撑满整屏（2026-10-17 实测踩到）
    var display by mutableFloatStateOf(GridColumnPreference.START_COLUMNS.toFloat())
        private set

    /** 手势里最新一次请求的小数列数（松手时用它决定提交到几列） */
    var requested by mutableFloatStateOf(GridColumnPreference.START_COLUMNS.toFloat())
        private set

    var pinching by mutableStateOf(false)
        private set

    var settling by mutableStateOf(false)
        private set

    /**
     * 1 列端"继续放大"的过冲量（9.9 `PinchFocusAnimation.moveTowardsCenter`）：
     * `mTowardsCenter = (1 − f) + 1`，夹到 `MAX_MAGNIFIER = 1.4`（`PinchGridContainerUiBase.java:586-592`）。
     * 1.0 = 没过冲。**用户 2026-10-24 令：只保留"焦点那张被推近"，黑幕与"松手打开查看器"都删掉。**
     */
    var overZoom by mutableFloatStateOf(1f)
        private set

    /**
     * 最大列数端"整页后推"量（9.9 `GroupLayout.BOUNCE_Z = −0.25` 的 bounce 布局）：
     * 0 = 没过冲，1 = 后推到 z=−0.25（透视后 0.8 倍）。
     */
    var bounce by mutableFloatStateOf(0f)
        private set

    /**
     * 两端过冲量的**目标值**（手指算出来的），显示值 [overZoom] / [bounce] 在 [advanceFrame] 里每帧逼近它。
     *
     * ⚠️ 为什么不直接用目标值：9.9 越过端点后会把 `mMagnifyFactor` **重锚**（`:133-136`），
     * 我们照抄 ⇒ 每个指针事件都把"又越界了多少"丢弃 ⇒ 目标值在事件流里反复跳 0 ↔ 小量
     * （手指事件间隔/位移不均匀时尤其明显）⇒ **整页反复抖动**（用户 2026-10-24 报"超过 7 列继续捏，
     * 整页反复抖动"）。加一层帧驱动的一阶平滑后，抖动消失、跟手延迟只有几帧。
     */
    private var targetOverZoom = 1f
    private var targetBounce = 0f

    private var startColumns = GridColumnPreference.START_COLUMNS.toFloat()
    private var expanding = false
    private var lastEventMillis = 0L
    private var prevEventMillis = 0L
    private var generation = 0

    /**
     * 9.9 `PinchAnimation.mCurrentInertia`：**只在事件到达时**按 `|目标 − 当前|` 重算
     * （`PinchGridContainerUiBase.java:426-437`），帧循环里用的是这个存下来的值 ——
     * 所以手指停住以后，画面会按最后那个 inertia 继续"漂"向目标（这就是顺滑停止的来源）。
     */
    private var inertia = MIN_INERTIA

    /** 当前应该显示的小数列数（就是平滑值；空闲时由 [setColumns] 与提交值对齐）。 */
    fun displayColumns(): Float = display

    /**
     * 交给**几何**的列数：夹在 [1, 最大列数]。
     * （9.9 在最大列数之后还有一段 bounce 布局，用户 2026-10-24 令删除 ⇒ 这里直接夹住。）
     */
    fun layoutColumns(): Float = display.coerceIn(
        GridColumnPreference.MIN_COLUMNS.toFloat(),
        GridColumnPreference.MAX_COLUMNS.toFloat(),
    )

    /** 外部列数变化（读偏好、恢复状态）时对齐显示值。 */
    fun setColumns(committed: Int) {
        display = committed.toFloat()
        requested = display
    }

    fun begin() {
        generation++
        // 9.9 用 `getRawColumns()`（= 还没收敛完的小数值）当锚点：连续两次缩放时不会先"跳"到整数再开始。
        // r25 之前这里锚的是**已提交的整数列**，而 `display` 可能停在 3.4 之类的中间值（上一次 spring 没走完
        // 就捏第二次）⇒ 起始那帧列数从 3.4 跳到 3，页面"乱跳"（用户 2026-10-18 报 C3）。
        val anchor = display.coerceIn(
            GridColumnPreference.MIN_COLUMNS.toFloat(),
            GridColumnPreference.MAX_COLUMNS.toFloat(),
        )
        startColumns = anchor
        requested = anchor
        inertia = MIN_INERTIA
        pinching = true
        settling = false
        expanding = false
        overZoom = 1f
        targetOverZoom = 1f
        bounce = 0f
        targetBounce = 0f
        lastEventMillis = System.currentTimeMillis()
        prevEventMillis = lastEventMillis
    }

    /**
     * 手势逐帧更新（只改"目标列数"，不直接推 [display] —— 平滑由 [advanceFrame] 每帧推进）。
     *
     * @param zoom 累计缩放比（当前span / 起始span）
     * @return 实际采用的 zoom（列数夹到端点时会重新锚定，调用方要用返回值覆盖累计值）
     */
    fun update(zoom: Float): Float {
        val now = System.currentTimeMillis()
        prevEventMillis = lastEventMillis
        lastEventMillis = now

        val effectiveZoom = zoom.coerceAtLeast(MIN_ZOOM)
        var target = startColumns / effectiveZoom
        val minColumns = GridColumnPreference.MIN_COLUMNS.toFloat()
        val maxColumns = GridColumnPreference.MAX_COLUMNS.toFloat()

        // 9.9 1 列端：列数算出来 < 1 就夹在 1，并把"继续放大的量"喂给 `moveTowardsCenter(f)`
        // （`PinchGridContainerUiBase.java:130-132,586-592`）。这里**不回锚** zoom —— 继续捏会继续推近。
        // 用户 2026-10-24 令：黑幕、松手打开查看器都删掉，只保留推近，松手顺滑停下。
        if (target < minColumns) {
            targetOverZoom = ((minColumns - target) + 1f).coerceAtMost(MAX_OVER_ZOOM)
            target = minColumns
        } else {
            targetOverZoom = 1f
        }

        // 9.9 最大列数端：越过 max 后走 bounce 布局（`GroupLayout.BOUNCE_Z = −0.25`），并把 magnifyFactor 重锚
        // （`PinchGridContainerUiBase.java:133-136`）⇒ 过冲量最多 1 列，整页后推到 0.8 倍。
        if (target > maxColumns) {
            targetBounce = (target - maxColumns).coerceIn(0f, 1f)
            target = maxColumns
        } else {
            targetBounce = 0f
        }

        // 9.9 mIsExpanding：新请求 <= 上一请求 ⇒ 列数在变小（格子变大）
        expanding = target <= requested
        requested = target
        // 9.9 `PinchAnimation.updateValues`：inertia 用"事件到达那一刻"的差距算
        inertia = inertiaFor(abs(requested - display))
        // ⚠️ **不重锚**：9.9 越过端点会把 magnifyFactor 重锚，我们照抄过一次 ⇒ 每个事件把"又越界了多少"
        // 丢弃，`bounce` 目标值在 0↔小量之间反复跳，画面就是"超过 7 列继续捏整页反复抖动"
        // （用户 2026-10-24 两次反馈）。这里让 zoom 累计下去，越界量单调增长、松手再单调收回。
        return zoom
    }

    /**
     * **每帧**推进一次平滑（9.9 `PinchAnimation.update` 的非 finishing 分支：
     * `mCurrent += (mRequestedNbrOfColumns − mCurrent) / mCurrentInertia`，`PinchGridContainerUiBase.java:464`）。
     *
     * 帧循环在 [com.example.album.ui.components.MediaGrid] 里由 `LaunchedEffect(pinching)` 驱动，
     * 所以手指停住、甚至抬起来（release 之前那一瞬）画面仍在朝目标收敛 —— 不会"突然顿住"。
     *
     * ⚠️ 2026-10-24 r48：9.9 的写法是"每帧走固定比例"，**帧率一变速度就变**（120Hz 会快 33%）。
     * 现在按 [dtSeconds] 归一：`dt = 1/90s` 时结果与原来的逐帧写法**逐帧完全相同**（本机零观感变化），
     * 换 120/60Hz 设备时实时速度也保持一样。
     */
    fun advanceFrame(dtSeconds: Float = 1f / PINCH_REFERENCE_HZ) {
        if (!pinching) return
        // 两端过冲量同样帧驱动平滑（目标值在事件里可能每帧跳 0 ↔ 小量，直接显示就是"反复抖动"）
        val smooth = perFrameAlpha(END_OVERSHOOT_SMOOTH, dtSeconds)
        overZoom += (targetOverZoom - overZoom) * smooth
        bounce += (targetBounce - bounce) * smooth
        val gap = requested - display
        if (abs(gap) < FRAME_EPSILON) {
            if (display != requested) display = requested
            return
        }
        // 原写法 `display += gap / inertia`（每帧走 1/inertia）⇒ 按时间归一后等价
        display += gap * perFrameAlpha(1f / inertia, dtSeconds)
    }

    /** 松手：按 9.9 的规则算目标整数列，spring 收敛后回调提交。 */
    fun release(
        scope: CoroutineScope,
        onSettled: (Int) -> Unit,
    ) {
        if (!pinching) return
        pinching = false
        val heldStill = lastEventMillis - prevEventMillis > PINCH_FLING_THRESHOLD_MILLIS
        val target = commitColumns(
            requested = requested,
            expanding = expanding,
            heldStill = heldStill,
            min = GridColumnPreference.MIN_COLUMNS,
            max = GridColumnPreference.MAX_COLUMNS,
        ).toFloat()

        settling = true
        val gen = ++generation
        targetOverZoom = 1f
        targetBounce = 0f

        scope.launch {
            // 列数 spring；同时把两端过冲量收回去（bounce 后推 / 1 列推近）
            launch {
                val b0 = bounce
                val z0 = overZoom
                if (b0 > 0f || z0 > 1f) {
                    animate(0f, 1f, animationSpec = tween(PINCH_OVERSCROLL_SETTLE_MILLIS)) { t, _ ->
                        if (gen == generation) {
                            bounce = b0 * (1f - t)
                            overZoom = 1f + (z0 - 1f) * (1f - t)
                        }
                    }
                }
            }
            animate(
                initialValue = display,
                targetValue = target,
                animationSpec = spring(dampingRatio = SNAP_DAMPING_RATIO, stiffness = SNAP_STIFFNESS),
            ) { value, _ -> if (gen == generation) display = value }
            if (gen == generation) {
                display = target
                bounce = 0f
                overZoom = 1f
                settling = false
                onSettled(target.roundToInt())
            }
        }
    }

    /**
     * **单指横向快滑换列**（9.9 `PinchGridContainerUiBase.onFling`，`:141-166`）：
     * `f3 = rawColumns ∓ 1`（速度向右 = 列数 −1 = 格子变大），再 `round` 并夹到 `[min, max]`，
     * 用同一条 `spring(k=100, friction=16)` 收敛；收敛期间锚点取"视口中间那一项"
     * （原版 `followItem(null)` 取 active 列表中间项 ⇒ 缩放围绕屏幕中间）。
     *
     * @param delta +1 = 列数变多（格子变小）、−1 = 列数变少（格子变大）
     */
    fun stepColumns(scope: CoroutineScope, delta: Int, onSettled: (Int) -> Unit) {
        val base = display.roundToInt()
        val target = (base + delta).coerceIn(
            GridColumnPreference.MIN_COLUMNS,
            GridColumnPreference.MAX_COLUMNS,
        )
        display = display.coerceIn(
            GridColumnPreference.MIN_COLUMNS.toFloat(),
            GridColumnPreference.MAX_COLUMNS.toFloat(),
        )
        bounce = 0f
        overZoom = 1f
        targetBounce = 0f
        targetOverZoom = 1f
        settling = true
        val gen = ++generation
        scope.launch {
            animate(
                initialValue = display,
                targetValue = target.toFloat(),
                animationSpec = spring(dampingRatio = SNAP_DAMPING_RATIO, stiffness = SNAP_STIFFNESS),
            ) { value, _ -> if (gen == generation) display = value }
            if (gen == generation) {
                display = target.toFloat()
                requested = display
                settling = false
                onSettled(target)
            }
        }
    }

    /**
     * 手势被打断（`pointerInput` 重启 / 组合销毁 / 页面被压栈）时收回状态。
     *
     * 必须做的原因（2026-10-17 用户截图实测）：一旦手势没走到 [release]，`pinching` 会一直是 true
     * ⇒ 网格会停在"小数列数"的中间态。这里只负责把状态收回常态，
     * 列数对齐交给 `LaunchedEffect(columns, pinching, settling)` → [setColumns]。
     */
    fun cancel(scope: CoroutineScope, onSettled: (Int) -> Unit) {
        pinching = false
        bounce = 0f
        overZoom = 1f
        targetBounce = 0f
        targetOverZoom = 1f
        val target = display.roundToInt().coerceIn(
            GridColumnPreference.MIN_COLUMNS,
            GridColumnPreference.MAX_COLUMNS,
        ).toFloat()
        settling = true
        val gen = ++generation
        scope.launch {
            animate(
                initialValue = display,
                targetValue = target,
                animationSpec = spring(dampingRatio = SNAP_DAMPING_RATIO, stiffness = SNAP_STIFFNESS),
            ) { value, _ -> if (gen == generation) display = value }
            if (gen == generation) {
                display = target
                requested = target
                settling = false
                onSettled(target.roundToInt())
            }
        }
    }

    companion object {
        /** 9.9 `PINCH_FLING_THRESHOLD = 500`：两次移动事件间隔超过它 = 手指停住过 ⇒ 用四舍五入 */
        const val PINCH_FLING_THRESHOLD_MILLIS = 500L

        /** 缩放累计的下限（防止除零；9.9 也有 1.0 列的下限保护） */
        private const val MIN_ZOOM = 0.05f

        /**
         * 9.9 `PinchAnimation` 的 `Spring(100, 16)` ⇒ ζ = 0.8（**会过冲约 1.5%**）。
         * 用户 2026-10-24：「松手后格子在停下后会再动一下」⇒ 改成临界阻尼（ζ=1，不过冲），刚度仍 100。
         */
        const val SNAP_STIFFNESS = 100f
        const val SNAP_DAMPING_RATIO = 1f

        /** 9.9 `MAX_INERTIA = 25` / `MIN_INERTIA = 5` */
        const val MAX_INERTIA = 25f
        const val MIN_INERTIA = 5f

        /** 9.9 `PinchFocusAnimation.MAX_MAGNIFIER = 1.4`：1 列端继续放大最多推近到 1.4（再往上不再变化）。 */
        const val MAX_OVER_ZOOM = 1.4f

        /**
         * 两端过冲量每帧朝目标逼近的比例（~4 帧到位）：
         * 9.9 越过端点会重锚 `mMagnifyFactor`，目标值在事件流里会跳 0 ↔ 小量，直接显示就是"反复抖动"。
         */
        private const val END_OVERSHOOT_SMOOTH = 0.25f

        /** 两端过冲量（bounce / 黑幕 / 推近）在普通松手时收回的时长。 */
        const val PINCH_OVERSCROLL_SETTLE_MILLIS = 300

        /** 收敛到这个差距就直接落到目标值（不再逐帧逼近），避免帧循环永远在写状态 */
        private const val FRAME_EPSILON = 1e-4f

        /**
         * 9.9 `PinchFocusAnimation.ROTATE_ENABLED_THRESHOLD = 3.1`：**列数小于 3.1 才累积旋转**
         * （或者已经在转就继续），避免 4–7 列时轻轻一扭就把整张照片转歪。
         */
        const val ROTATE_ENABLED_COLUMNS = 3.1f

        /** 9.9 同一个方法里的跳变保护：单次旋转增量 ≥ 90° 直接丢弃（换指/重定位会造成这种跳变） */
        const val ROTATE_MAX_STEP_DEGREES = 90f
    }
}

/**
 * 9.9 `PinchAnimation.updateValues` 的平滑系数：`inertia = clamp((1 − |Δ|) × 25, 5, 25)`。
 * 纯函数，JVM 单测覆盖。
 */
fun inertiaFor(gap: Float): Float {
    val clamped = min(abs(gap), 1f)
    return ((1f - clamped) * PinchColumnsState.MAX_INERTIA).coerceAtLeast(PinchColumnsState.MIN_INERTIA)
}

/**
 * 捏合手感的**时间基准帧率**（不是"给这台设备做的适配"）。
 *
 * 9.9 的平滑系数是"每帧走固定比例"写出来的，那套系数是在 90Hz 的机器上校准出来的观感；
 * 这里把它当**基准**换算成时间尺度：任何刷新率的设备上，一秒内收敛的总量都跟 90Hz 一致
 * （`perFrameAlpha` 里 `dt = 1/90s` 时结果严格等于原来的固定比例 ⇒ 本机观感零变化；
 * 120Hz 每帧步子小一点、60Hz 大一点，实时速度不变）。
 *
 * ⚠️ 不要改成"读当前设备刷新率"：那会让手感随设备漂移，正好是这次要修掉的问题。
 */
const val PINCH_REFERENCE_HZ = 90f

/**
 * 把"每帧走 k"的一阶平滑换算成"经过 [dtSeconds] 秒走多少"（等比指数衰减）：
 * `1 − (1 − k)^(dt × REFERENCE_HZ)`。
 *
 * - `dt = 1/90s` 时结果**恰好 == k** ⇒ 本机 90Hz 上的画面与旧的逐帧写法逐帧一致（观感零变化）；
 * - `dt = 1/120s` 时每帧步子更小、但一秒内步数更多 ⇒ 实时收敛速度不变（不会像逐帧写法那样快 33%）。
 *
 * 纯函数，JVM 单测覆盖（`GridPinchTest`）。
 */
fun perFrameAlpha(k: Float, dtSeconds: Float): Float {
    if (dtSeconds <= 0f) return k
    return 1f - (1f - k).pow(dtSeconds * PINCH_REFERENCE_HZ)
}

/**
 * 松手后提交到几列（9.9 `handleEndMultiTouch` 的规则）。纯函数，JVM 单测覆盖。
 *
 * - 手指停住过（`heldStill`）⇒ 四舍五入；
 * 否则**朝手势方向再走一格**：格子变大（`expanding`，列数变小）取 `floor`，格子变小取 `ceil`；
 * - 最后夹到 `[min, max]`。
 */
fun commitColumns(requested: Float, expanding: Boolean, heldStill: Boolean, min: Int, max: Int): Int {
    val target = when {
        heldStill -> requested.roundToInt()
        expanding -> floor(requested).toInt()
        else -> ceil(requested).toInt()
    }
    return target.coerceIn(min, max)
}

/**
 * 焦点项横向跟手后的**目标中心 x**（9.9 `PinchFocusAnimation.updatePath` 的 f3 夹取，`PinchGridContainerUiBase.java:616-623`）：
 *
 * ```
 * halfSurface = 0.985 · W / 2          // `mHalfSurfaceWidth`(:523)
 * 目标中心 = clamp(起始中心 + 手指横向位移, 0.0075W + w/2, 0.9925W − w/2)
 * ```
 *
 * 纯函数，JVM 单测覆盖。
 */
fun focusTargetCenterX(
    startCenterX: Float,
    fingerDx: Float,
    width: Float,
    viewportWidth: Float,
): Float {
    val margin = (1f - FOCUS_SURFACE_COVERAGE) * 0.5f * viewportWidth
    val lo = margin + width * 0.5f
    val hi = viewportWidth - margin - width * 0.5f
    if (lo > hi) return viewportWidth * 0.5f
    return (startCenterX + fingerDx).coerceIn(lo, hi)
}

/**
 * 9.9 `PinchFocusAnimation.mHalfSurfaceWidth = getWidth() * 0.985f / 2`（`PinchGridContainerUiBase.java:523`）
 * ⇒ 焦点项的四边各留 0.75% 屏宽。
 */
const val FOCUS_SURFACE_COVERAGE = 0.985f

/**
 * 9.9 `PinchFocusAnimation.MIN_Z_OFFSET = 0.015`：捏合时焦点项先微微贴近镜头
 * （透视 `1/(1−z)` ⇒ ×1.015，同时以屏心为投影点向外挪一点点）。
 */
const val PINCH_FOCUS_MIN_Z = 0.015f

/** 9.9 `PINCH_FOCUS_ANIMATION_TIME = 300`：松手后焦点项的旋转/横向位移用 easeOut 归位 */
const val PINCH_FOCUS_DECAY_MILLIS = 300

/**
 * 只接管"两根手指以上"的缩放，走 Initial pass 先于内部滚动拿走事件。
 *
 * 捏合期间做三件事（= 9.9 `handlePinch` 的全部内容）：
 * 1. **列数插值**；
 * 2. **锚点**：指下那一项钉在质心上（9.9 `followItem` + `adjustFollowItem`，由调用方写入网格状态，
 *    在 measure 里反算滚动量）；
 * 3. **焦点项**：旋转（列数 < 3.1 才累积，9.9 `ROTATE_ENABLED_THRESHOLD`）+ 横向跟手。
 *
 * ⚠️ 一旦进入过多指，**直到全部手指抬起前的所有事件都要消费**（9.9 `mConsumeSingleTouchEvent`
 * 要到下一次 `onDown` 才恢复）：否则第二指先抬起、剩下的单指会立刻拖动网格。
 *
 * ⚠️ **`pointerInput` 的 key 只许放 `pinch`（稳定的 remember 对象）**：key 一变 Compose 就会取消并重启
 * 整个手势协程，而重启会走 `finally` 里的 `pinch.cancel()` ⇒ 捏合被打断、弹簧弹回（看起来就是"页面抖动"）。
 * 2026-10-24 踩过：原来 key 里有一个每次重组都新建的 lambda（`onColumnsChange`）和一个用不到的
 * `committedColumns`，于是「1 列过冲时每帧重组」= 每帧重启手势。回调要有最新值就让调用方用
 * `rememberUpdatedState` 包一层，**不要**把回调塞进 key。
 *
 * @param onStart 手势开始（`MediaGrid` 用它记"指下是哪一项"→ 锚点 + 焦点项）
 * @param onFollowY 质心纵向位移（px，屏幕坐标、向下为正）⇒ 锚点的屏幕 y 跟着手指走
 * @param onFollowX 质心横向**累计**位移（px，相对手势起点）⇒ 焦点项横向跟手
 * @param onRotate 累计旋转角（度，Compose 约定：顺时针为正）⇒ 焦点项旋转
 * @param onHorizontalFling 单指**横向快滑**（px/s，向右为正）⇒ 列数 −1 / +1
 *   （9.9 `onFling`：`|Δx| > 2|Δy|` 且松手带速度；这一下会吃掉抬起事件，避免平台再 fling 一段）
 * @param onEnd 手势结束（焦点项 300ms 归位）
 */
fun Modifier.pinchToColumns(
    pinch: PinchColumnsState,
    onColumnsChange: (Int) -> Unit,
    scope: CoroutineScope,
    onStart: (Offset) -> Unit,
    onFollowY: (Float) -> Unit = {},
    onFollowX: (Float) -> Unit = {},
    onRotate: (Float) -> Unit = {},
    onHorizontalFling: (Float) -> Unit = {},
    onEnd: () -> Unit,
): Modifier = this.pointerInput(pinch) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            var active = false
            /** 已经提交过（首指抬起 / 全部抬起）：之后只消费事件，不再改状态 */
            var released = false
            var lastCentroidY = Float.NaN
            var startCentroidX = Float.NaN
            var rotation = 0f
            var prevAngle = Float.NaN
            // 单指手势：用来判"横向快滑换列"（9.9 `onFling` + `isHorizontalMove`）
            val startX = down.position.x
            val startY = down.position.y
            var lastX = startX
            var lastY = startY
            var multiTouchSeen = false
            var flingX = 0f
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressedCount = event.changes.count { it.pressed }
                if (!active && pressedCount >= 2) {
                    val step = event.calculateZoom()
                    if (step.isFinite() && step > 0f) {
                        active = true
                        multiTouchSeen = true
                        zoom = 1f
                        val centroid = event.calculateCentroid(useCurrent = true)
                        lastCentroidY = centroid.y
                        startCentroidX = centroid.x
                        prevAngle = twoFingerAngle(event)
                        pinch.begin()
                        onStart(centroid)
                    }
                }
                if (active) {
                    // 多指之后的所有事件都由我们吃掉（含只剩一根手指的那段）
                    event.changes.forEach { it.consume() }
                    if (released) continue
                    // ⚠️ 9.9 `onScaleEnd`：手指数掉到 1 就**立即提交**（首指抬起，不等全部抬起）。
                    // 之后单指只被消费、不再搬锚点 —— 否则"抬起一根手指后剩下那根会拖网格"（审计 E2）。
                    if (pressedCount < 2) {
                        released = true
                        onEnd()
                        pinch.release(scope) { target -> onColumnsChange(target) }
                        continue
                    }
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid != Offset.Unspecified) {
                        if (!lastCentroidY.isNaN()) {
                            val dy = centroid.y - lastCentroidY
                            if (dy != 0f) onFollowY(dy)
                        }
                        lastCentroidY = centroid.y
                        if (!startCentroidX.isNaN()) onFollowX(centroid.x - startCentroidX)
                    }
                    // 9.9 `PinchFocusAnimation.updateValues`：列数 < 3.1（或已经在转）才累积旋转；
                    // 单次增量 ≥ 90° 视为换指/重定位造成的跳变，丢掉。
                    val angle = twoFingerAngle(event)
                    if (!angle.isNaN()) {
                        if (!prevAngle.isNaN()) {
                            val delta = normalizeDegrees((angle - prevAngle) * RAD_TO_DEG)
                            val enabled = pinch.requested < PinchColumnsState.ROTATE_ENABLED_COLUMNS ||
                                rotation != 0f
                            if (enabled && abs(delta) < PinchColumnsState.ROTATE_MAX_STEP_DEGREES) {
                                rotation += delta
                                onRotate(rotation)
                            }
                        }
                        prevAngle = angle
                    }
                    // ⚠️ 只在"≥2 指"这一帧更新列数（审计 E1）：抬手那一帧若也 update，
                    // `target == requested` 会让 expanding 恒为 true ⇒ 松手永远 floor（少落一列）。
                    val step = event.calculateZoom()
                    if (step.isFinite() && step > 0f) {
                        zoom = (zoom * step).coerceAtLeast(0.05f)
                        zoom = pinch.update(zoom)
                    }
                } else if (pressedCount >= 1) {
                    // 单指拖动：只记轨迹（滚动仍交给平台 `scrollableArea`）
                    val change = event.changes.firstOrNull { it.pressed }
                    if (change != null) {
                        tracker.addPosition(change.uptimeMillis, change.position)
                        lastX = change.position.x
                        lastY = change.position.y
                    }
                } else if (!multiTouchSeen) {
                    // 抬起那一帧：判"横向快滑"（9.9 `isHorizontalMove` = `|Δx| > 2|Δy|`，
                    // 且平台判定为 fling）。**必须在这一帧吃掉事件**，否则 `scrollableArea`
                    // 还会拿着同一份速度再 fling 一段纵向滚动。
                    event.changes.firstOrNull()?.let {
                        tracker.addPosition(it.uptimeMillis, it.position)
                        lastX = it.position.x
                        lastY = it.position.y
                    }
                    val dx = lastX - startX
                    val dy = lastY - startY
                    if (abs(dx) > abs(dy) * 2f) {
                        val vx = tracker.calculateVelocity().x
                        if (abs(vx) >= viewConfiguration.minimumFlingVelocity) {
                            event.changes.forEach { it.consume() }
                            flingX = vx
                        }
                    }
                }
            } while (event.changes.any { it.pressed })

            if (active && !released) {
                onEnd()
                // 松手后弹簧跑完才回调（= 9.9 `PinchAnimation` 的 `onEvent(1)`）：
                // 调用方在这里清锚点、提交列数。即使列数没变也必须回调（否则锚点永远留着）。
                pinch.release(scope) { target -> onColumnsChange(target) }
            } else if (!active && flingX != 0f) {
                onHorizontalFling(flingX)
            }
        }
    } finally {
        // 手势被取消时（`pointerInput` 的 key 变了、组合销毁、被别的页面盖住）也必须复位，
        // 否则网格会停在"小数列数"的中间态。这里不硬掰整数（那会跳），交给 spring 收敛。
        if (pinch.pinching) {
            onEnd()
            pinch.cancel(scope) { target -> onColumnsChange(target) }
        }
    }
}

private const val RAD_TO_DEG = (180.0 / PI).toFloat()

/** 前两根手指连线的方向角（弧度，屏幕坐标 y 向下）；不足两指返回 NaN。 */
private fun twoFingerAngle(event: PointerEvent): Float {
    val pressed = event.changes.filter { it.pressed }
    if (pressed.size < 2) return Float.NaN
    val a = pressed[0].position
    val b = pressed[1].position
    return atan2(a.y - b.y, a.x - b.x)
}

/** 角度归一到 (−180, 180]，与 9.9 `RotateGestureDetector.getRotationDegrees` 的 ±2π 处理一致。 */
private fun normalizeDegrees(degrees: Float): Float {
    var d = degrees
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}
