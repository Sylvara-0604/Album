package com.example.album.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 顶部下拉时 banner 的"跟手放大"状态（**2026-10-24 用户令：按原版加回来**）。
 *
 * 9.9 真值（`OverscrollAnimatorSpring` + `BannerUiItem.onOverscroll`）：
 * - 手指每拖 `Δpx`，弹簧的 stretch 增加 `Δ`（GL 单位 = px / 屏宽）；
 * - 显示用的越界量 `u = 0.5·√stretch`（`getOverscrollValueFromStretch`，`OverscrollAnimatorSpring.java:57-59`）；
 * - banner 的放大 = `1 + 0.3·u`（`BannerUiItem.java:46,204`，锚点在**banner 底边**，见 `setOverscroll`）；
 * - 松手后弹簧 `Spring(k=82, b=15)`（ζ≈0.83）回 0，时间尺度 ×4（`update(f * SPEED=4)`）。
 *
 * Compose 这边用"stretch 直接当状态、u 在读取时算"的等价写法：
 * 拖拽期同步写值（不挂起），松手时用同参数弹簧动画回 0。
 */
@Stable
class BannerPullState {

    /** 弹簧伸长量（GL 单位 = 屏宽的百分比），对应 9.9 `Spring.getStretch()` */
    var stretch by mutableFloatStateOf(0f)
        private set

    var maxStretch by mutableFloatStateOf(0f)
        private set

    private var settleJob: Job? = null

    /** 当前该用的 banner 放大倍数（1.0 = 不放大） */
    val scale: Float
        get() {
            val u = 0.5f * sqrt(abs(stretch))
            return 1f + OVERSCROLL_SCALE_FACTOR * u
        }

    val isActive: Boolean
        get() = stretch > 0.0001f || settleJob?.isActive == true

    /** 手指下拉 `deltaPx`（正数 = 往下拉） */
    fun pullBy(deltaPx: Float, viewportWidthPx: Float) {
        if (viewportWidthPx <= 0f) return
        settleJob?.cancel()
        settleJob = null
        val next = stretch + abs(deltaPx) / viewportWidthPx
        stretch = next.coerceAtMost(MAX_STRETCH)
        if (stretch > maxStretch) maxStretch = stretch
    }

    /** 松手：按 9.9 的弹簧（k=82 / b=15，ζ≈0.83，时间尺度 ×4）回 0 */
    fun release(scope: CoroutineScope) {
        if (stretch <= 0f) return
        settleJob?.cancel()
        settleJob = scope.launch {
            val from = stretch
            animate(
                initialValue = from,
                targetValue = 0f,
                animationSpec = spring(
                    // 9.9 的时间尺度是 ×4（`OverscrollAnimatorSpring.update` 里 `f * SPEED`），
                    // 换成 Compose 的 stiffness 大致是 82 × 4² ≈ 1300，观感等价（≈300ms 收干净）。
                    dampingRatio = 15f / (2f * sqrt(82f)),
                    stiffness = 1300f,
                ),
            ) { value, _ -> stretch = value }
            stretch = 0f
        }
    }

    /** 立刻清零（换页/重新测量等场景） */
    fun reset() {
        settleJob?.cancel()
        settleJob = null
        stretch = 0f
    }

    companion object {
        /** 9.9 `BannerUiItem.OVERSCROLL_FACTOR = 0.3f`：放大 = 1 + 0.3·u */
        const val OVERSCROLL_SCALE_FACTOR = 0.3f

        /** stretch 上限（防止无限下拉把 banner 拉爆；9.9 没有硬上限，这里给一个物理上限） */
        private const val MAX_STRETCH = 0.36f
    }
}

/**
 * 网格的越界处理：**顶部（banner 方向）由我们接管成 banner 放大**，其它方向仍交给平台
 * （底部 stretch 用户已结案，保持平台行为）。
 *
 * 2026-10-24 用户令：「banner下拉放大 + 顶部弹簧 按原版重新加回来」。
 */
@OptIn(ExperimentalFoundationApi::class)
internal class BannerOverscrollEffect(
    private val pull: BannerPullState,
    private val viewportWidthPx: () -> Float,
    private val atTop: () -> Boolean,
    private val scope: () -> CoroutineScope,
    private val platform: OverscrollEffect?,
) : OverscrollEffect {

    override val isInProgress: Boolean
        get() = pull.isActive || platform?.isInProgress == true

    override fun applyToScroll(
        delta: Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val consumed = performScroll(delta)
        val left = delta - consumed
        // 只有"往下拉且已经在顶部"这一种才吃进 banner 放大。
        // ⚠️ 符号：手指往下拖 ⇒ `delta.y > 0`（内容想往下走 = 滚动量想变小）；
        // 2026-10-24 之前写成 `< 0f` ⇒ 顶部下拉永远进不来（用户报「banner 下拉根本没有」）。
        if (left.y > 0f && atTop()) {
            pull.pullBy(left.y, viewportWidthPx())
            return delta
        }
        val rest = platform ?: return consumed
        return consumed + rest.applyToScroll(left, source) { Offset.Zero }
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        pull.release(scope())
        platform?.applyToFling(velocity, performFling) ?: performFling(velocity)
    }
}

/**
 * fling 摩擦按 9.9 的 `setFriction(0.009f)`（`ScrollContainerUiBase.java:687`）。
 *
 * 平台默认（`ScrollableDefaults.flingBehavior()`，摩擦 0.015）同样速度下滑行距离只有原版的 ~69%。
 * 指数衰减的距离 = `v / (frictionMultiplier × 4.2)`；要让 v=6000 时滑 3122px（9.9 实测值），
 * 需要 `frictionMultiplier ≈ 6000 / 3122 / 4.2 ≈ 0.458`。
 * 速度阈值 180px/s 用来砍掉指数衰减那条"永远在爬"的尾巴（末段损失 ≈ 5%，肉眼看不出）。
 */
internal object AlbumFling {
    /**
     * 指数衰减的摩擦系数（1/s）：`v(t) = v0·e^(−f·t)` ⇒ 总距离 = `v0 / f`。
     * 取 f = 1.922 时 v0=6000 → 3122px，与 9.9 的 `setFriction(0.009)` 实测值一致（见 `audit/02`）。
     */
    const val FRICTION_PER_SECOND = 1.922f
    const val STOP_VELOCITY = 180f
}

internal class AlbumFlingBehavior : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < AlbumFling.STOP_VELOCITY) return 0f
        var velocity = initialVelocity
        var lastFrame = 0L
        // 自己按帧积分（9.9 也是 `setConstantFrameMode(true)` 每帧推进）：
        // 平台默认摩擦 0.015 只滑到原版的 ~69%，这里按原版摩擦重算距离。
        while (abs(velocity) >= AlbumFling.STOP_VELOCITY) {
            withFrameNanos { now ->
                if (lastFrame != 0L) {
                    val dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    val delta = velocity * dt
                    val consumed = scrollBy(delta)
                    // 撞到两端：剩余部分不再走（原版撞边也是"补到边界就停"）
                    if (abs(consumed - delta) > 0.5f) {
                        velocity = 0f
                    } else {
                        velocity *= exp(-AlbumFling.FRICTION_PER_SECOND * dt)
                    }
                }
                lastFrame = now
            }
        }
        return 0f
    }
}
