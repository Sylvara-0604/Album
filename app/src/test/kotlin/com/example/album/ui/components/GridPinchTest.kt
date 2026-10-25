package com.example.album.ui.components

import com.example.album.data.GridColumnPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class GridPinchTest {

    /**
     * 2026-10-24 r48：每帧系数改成按 dt 归一之后，**本机 90Hz 上必须与旧的逐帧写法完全相同**
     * （用户令「效果不能有任何变化」）⇒ dt = 1/90s 时换算结果要严格等于 k。
     */
    @Test
    fun `perFrameAlpha 在 90Hz 下逐帧等价于固定比例`() {
        val dt90 = 1f / PINCH_REFERENCE_HZ
        for (k in floatArrayOf(0.25f, 0.5f, 0.2f, 1f / 5f, 1f / 25f, 0.9f)) {
            assertEquals(k, perFrameAlpha(k, dt90), 1e-6f)
        }
    }

    /** 换刷新率：每帧步子变小，但**一秒内的收敛量不变**（实时速度一致）。 */
    @Test
    fun `perFrameAlpha 换刷新率时实时速度不变`() {
        val k = 0.25f
        val a120 = perFrameAlpha(k, 1f / 120f)
        val a60 = perFrameAlpha(k, 1f / 60f)
        assertTrue("120Hz 每帧步子应更小", a120 < k)
        assertTrue("60Hz 每帧步子应更大", a60 > k)
        val perSec90 = 1f - (1f - k).pow(90f)
        val perSec120 = 1f - (1f - a120).pow(120f)
        val perSec60 = 1f - (1f - a60).pow(60f)
        assertEquals(perSec90, perSec120, 1e-4f)
        assertEquals(perSec90, perSec60, 1e-4f)
    }

    @Test
    fun `平滑系数按 9_9 的 (1 - Δ) times 25`() {
        assertEquals(25f, inertiaFor(0f), 1e-4f)
        assertEquals(12.5f, inertiaFor(0.5f), 1e-4f)
        assertEquals(20f, inertiaFor(0.2f), 1e-4f)
    }

    @Test
    fun `平滑系数下限是 5（Δ 达到 1 列及以上）`() {
        assertEquals(5f, inertiaFor(1f), 1e-4f)
        assertEquals(5f, inertiaFor(2f), 1e-4f)
    }

    @Test
    fun `手指停住过就四舍五入`() {
        assertEquals(4, commitColumns(requested = 4.3f, expanding = false, heldStill = true, min = 1, max = 7))
        assertEquals(5, commitColumns(requested = 4.6f, expanding = true, heldStill = true, min = 1, max = 7))
    }

    @Test
    fun `格子变大（列数变小）朝手势方向取 floor`() {
        assertEquals(4, commitColumns(requested = 4.3f, expanding = true, heldStill = false, min = 1, max = 7))
        assertEquals(4, commitColumns(requested = 4.7f, expanding = true, heldStill = false, min = 1, max = 7))
    }

    @Test
    fun `格子变小（列数变大）朝手势方向取 ceil`() {
        assertEquals(6, commitColumns(requested = 5.1f, expanding = false, heldStill = false, min = 1, max = 7))
        assertEquals(6, commitColumns(requested = 5.7f, expanding = false, heldStill = false, min = 1, max = 7))
    }

    @Test
    fun `列数下限是 1 上限是 7 两端只夹住没有 bounce 与 over-zoom`() {
        assertEquals(1, commitColumns(requested = 0.2f, expanding = true, heldStill = false, min = 1, max = 7))
        assertEquals(1, commitColumns(requested = 0.9f, expanding = true, heldStill = true, min = 1, max = 7))
        assertEquals(7, commitColumns(requested = 7.2f, expanding = false, heldStill = false, min = 1, max = 7))
        assertEquals(7, commitColumns(requested = 8.4f, expanding = false, heldStill = true, min = 1, max = 7))
    }

    @Test
    fun `捏合到两端时显示列数被夹住不再有整页后推与黑幕`() {
        val pinch = PinchColumnsState()
        pinch.setColumns(GridColumnPreference.MAX_COLUMNS)
        pinch.begin()
        // 继续捏（zoom < 1 = 想变小格子 = 更多列）：夹在最大列数
        pinch.update(0.5f)
        assertEquals(
            GridColumnPreference.MAX_COLUMNS.toFloat(),
            pinch.display,
            1e-4f,
        )
        // 反向（放大到 1 列以外）：夹在 1 列
        pinch.setColumns(GridColumnPreference.MIN_COLUMNS)
        pinch.begin()
        pinch.update(3f)
        assertEquals(1f, pinch.display, 1e-4f)
    }

    /**
     * 2026-10-24 改口径：**不再重锚 zoom**（9.9 的 `mMagnifyFactor = raw*span` 重锚会把"又越界了多少"
     * 每个事件丢掉一次 ⇒ `bounce` 目标值在 0↔小量之间反复跳 ⇒ 超过 7 列继续捏时整页反复抖动）。
     * 现在 zoom 一路累计，`update` 原样返回它；越界量由 `bounce` 单调表达。
     */
    @Test
    fun `顶到端点后不重锚 zoom 越界量单调 往回捏马上有反应`() {
        val pinch = PinchColumnsState()
        pinch.setColumns(7)
        pinch.begin()
        // zoom 0.5 = 想变成 14 列 ⇒ 列数夹到 7，越界量 = min(1, 14-7) = 1
        val effective = pinch.update(0.5f)
        assertEquals(0.5f, effective, 1e-4f)
        assertEquals(7f, pinch.requested, 1e-4f)
        repeat(8) { pinch.advanceFrame() }
        assertTrue("越界量应当接近上限：${pinch.bounce}", pinch.bounce > 0.8f)
        // 往回捏到 zoom 1.2（累计值）⇒ 列数应该马上变小（7 / 1.2 ≈ 5.83），越界量归零
        pinch.update(1.2f)
        assertTrue("应当立刻有反应：${pinch.requested}", pinch.requested < 7f)
        repeat(8) { pinch.advanceFrame() }
        assertTrue("往回捏后越界量应当收回：${pinch.bounce}", pinch.bounce < 0.1f)
    }

    @Test
    fun `第二次捏合从当前小数列数起算，不回跳整数列`() {
        val pinch = PinchColumnsState()
        pinch.setColumns(3)
        pinch.begin()
        pinch.update(0.9f)
        repeat(120) { pinch.advanceFrame() }
        val mid = pinch.display
        assertTrue("第一次手势应收敛到小数列数：$mid", mid > 3f && mid < 4f)
        pinch.begin()
        assertEquals(mid, pinch.requested, 1e-4f)
    }

    @Test
    fun `平滑每帧推进：手指不动了画面还会继续收敛（顺滑停止）`() {
        val pinch = PinchColumnsState()
        pinch.setColumns(3)
        pinch.begin()
        // 3 列 → 6 列：事件只改目标值，显示值不动
        pinch.update(0.5f)
        assertEquals(3f, pinch.display, 1e-4f)
        repeat(3) { pinch.advanceFrame() }
        assertTrue("事件停了仍需每帧收敛：${pinch.display}", pinch.display > 3f)
        repeat(600) { pinch.advanceFrame() }
        assertEquals(6f, pinch.display, 1e-3f)
    }

    @Test
    fun `焦点项横向跟手被 0_985 屏宽夹住`() {
        val w = 1080f
        // 0.0075·W = 8.1px 的四边留白；半宽 100
        assertEquals(540f, focusTargetCenterX(540f, 0f, 200f, w), 1e-3f)
        assertEquals(640f, focusTargetCenterX(540f, 100f, 200f, w), 1e-3f)
        assertEquals(971.9f, focusTargetCenterX(540f, 5000f, 200f, w), 1e-2f)
        assertEquals(108.1f, focusTargetCenterX(540f, -5000f, 200f, w), 1e-2f)
    }
}
