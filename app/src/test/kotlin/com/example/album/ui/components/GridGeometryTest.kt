package com.example.album.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class GridGeometryTest {

    private fun geometry(width: Float = 1000f, basePadding: Float = 8f) = GridGeometry(width, basePadding)

    @Test
    fun `整数列按 9_9 的装配公式（首项是 2×2 大格）`() {
        val g = geometry()
        // gap = 2 × base × (0.75/n + 0.25) = 16 × (0.4375) = 7
        assertEquals(7f, g.gap(4f), 1e-3f)
        // tile = (W + gap)/n − gap = (1000 + 7)/4 − 7 = 244.75；pitch = 251.75
        assertEquals(244.75f, g.cell(4f), 1e-3f)
        assertEquals(251.75f, g.cell(4f) + g.gap(4f), 1e-3f)

        // local 0 = 2×2 大格（9.9 mHaveRoadSign = n > 2）：2·tile + gap = 496.5
        val big = g.rect(0, 4f)
        assertEquals(0f, big.x, 1e-3f)
        assertEquals(0f, big.y, 1e-3f)
        assertEquals(496.5f, big.width, 1e-3f)
        assertEquals(496.5f, big.height, 1e-3f)

        // local 1 → slot 2（第 1 行第 3 列）
        val second = g.rect(1, 4f)
        assertEquals(2f * 251.75f, second.x, 1e-3f)
        assertEquals(0f, second.y, 1e-3f)
        assertEquals(244.75f, second.width, 1e-3f)

        // local 2 → slot 3（第 1 行第 4 列）
        assertEquals(3f * 251.75f, g.rect(2, 4f).x, 1e-3f)

        // local 3 → slot 6（第 2 行第 3 列：大格罩住第 2 行前两列）
        val fourth = g.rect(3, 4f)
        assertEquals(2f * 251.75f, fourth.x, 1e-3f)
        assertEquals(251.75f, fourth.y, 1e-3f)

        // local 5 → slot 8（第 3 行第 1 列）
        assertEquals(0f, g.rect(5, 4f).x, 1e-3f)
        assertEquals(2f * 251.75f, g.rect(5, 4f).y, 1e-3f)
    }

    /**
     * 首段换位的"真值表"（用户 2026-10-24 报 A1：「首段右侧那两行 1×1 放大移出、没有重叠动画」）。
     *
     * 口径：真机 W=1080、basePadding=3dp@2.75=8.25px。断言值由 9.9 `GridLayout.doLayout` +
     * `InterpolateLayout.setLayout` 的公式复算（见 `docs/research/Z_AXIS_AND_BAND.md` §B2/B3.3）。
     */
    @Test
    fun `首段成员随列数换位（4 列与 5 列之间逐项插值）`() {
        val g = GridGeometry(widthPx = 1080f, basePaddingPx = 8.25f)

        // 纯 4 列：大格 536.3906、local5 在第 3 行第 1 列
        assertEquals(536.3906f, g.groupRectAt(0, 4).width, 1e-2f)
        assertEquals(0f, g.groupRectAt(5, 4).x, 1e-2f)
        assertEquals(543.6094f, g.groupRectAt(5, 4).y, 1e-2f)

        // 纯 5 列：local5 换到第 2 行第 4 列
        assertEquals(3f * 217.32f, g.groupRectAt(5, 5).x, 1e-2f)
        assertEquals(217.32f, g.groupRectAt(5, 5).y, 1e-2f)

        // 中间态：local3 正在往上一行飞（y 变小）、local5 正飞进首段（x 变大）
        val l3mid = g.rect(3, 4.5f)
        assertEquals(706.45f, l3mid.x, 0.5f)
        assertEquals(135.90f, l3mid.y, 0.5f)
        val l5mid = g.rect(5, 4.5f)
        assertEquals(325.98f, l5mid.x, 0.5f)
        assertEquals(380.47f, l5mid.y, 0.5f)

        // 2 列没有大格；2→3 大格"长出来"的中间态
        assertEquals(g.cell(2f), g.groupRectAt(0, 2).width, 1e-3f)
        assertEquals(626.05f, g.rect(0, 2.5f).width, 0.5f)
    }

    @Test
    fun `换列连续：跨整数列不跳、行距自洽、所有项不越界`() {
        val g = GridGeometry(widthPx = 1080f, basePaddingPx = 8.25f)
        // 4.999 → 5.001 时任一 item 的位置/尺寸变化 < 1px（端点上不额外插值）
        for (local in 0..12) {
            val before = g.rect(local, 4.999f)
            val after = g.rect(local, 5.001f)
            assertTrue("local=$local x 跳变", kotlin.math.abs(before.x - after.x) < 1f)
            assertTrue("local=$local y 跳变", kotlin.math.abs(before.y - after.y) < 1f)
            assertTrue("local=$local w 跳变", kotlin.math.abs(before.width - after.width) < 1f)
        }
        // 行距自洽：y 对 local 单调不减（含大格）
        for (n in 2..7) {
            var lastY = -1f
            for (local in 0..40) {
                val y = g.groupRectAt(local, n).y
                assertTrue("n=$n local=$local y 倒退：$lastY -> $y", y >= lastY - 1e-2f)
                lastY = y
            }
            // 右缘/下缘不得越过内容宽（9.9 会溢出 p，我们无 p 必须不出界）
            for (local in 0..40) {
                val r = g.groupRectAt(local, n)
                assertTrue("n=$n local=$local 右缘 ${r.right}", r.right <= 1080f + 0.01f)
            }
        }
    }

    @Test
    fun `小数列数在相邻两份整数布局之间线性插值`() {
        val g = geometry()
        val a = g.rect(9, 4f)
        val b = g.rect(9, 5f)
        val mid = g.rect(9, 4.5f)
        assertEquals((a.x + b.x) / 2f, mid.x, 1e-3f)
        assertEquals((a.y + b.y) / 2f, mid.y, 1e-3f)
        assertEquals((a.size + b.size) / 2f, mid.size, 1e-3f)

        // 插值是连续的：4.0 与 4.0001 几乎一样
        val just = g.rect(9, 4.0001f)
        assertEquals(a.x, just.x, 0.1f)
        assertEquals(a.y, just.y, 0.1f)
    }

    @Test
    fun `项数少于首段大格时，段高不能低于大格下缘`() {
        val g = geometry()
        // 4 列、只有 2–3 项：大格 496.5 高，最后一项（local1/local2）底边只有 244.75
        for (count in 1..3) {
            assertEquals(g.rect(0, 4f).bottom, g.runHeight(count, 4f), 1e-3f)
        }
    }

    @Test
    fun `段高与最后一项的位置自洽`() {
        val g = geometry()
        for (columns in listOf(3f, 3.5f, 4f, 4.7f, 5f, 6.2f, 7f)) {
            for (count in listOf(1, 2, 5, 13, 40)) {
                val last = g.rect(count - 1, columns)
                val height = g.runHeight(count, columns)
                // 段高 = max(最后一项底边, 首段大格下缘)：项数少时大格会罩住最后一项
                assertTrue("columns=$columns count=$count 段高盖不住最后一项", height >= last.y + last.size - 1e-3f)
                if (count >= 13) {
                    assertEquals(last.y + last.size, height, 1e-3f)
                }
            }
        }
    }

    @Test
    fun `换列时格子连续变小`() {
        val g = geometry()
        val sizes = listOf(3f, 3.5f, 4f, 4.5f, 5f, 6f, 7f).map { g.cell(it) }
        sizes.zipWithNext().forEach { (a, b) -> assertTrue("列数越多格子越小: $a -> $b", b < a) }
    }

    @Test
    fun `可见范围只覆盖视口内的格子`() {
        val g = geometry()
        val columns = 4f
        val pitch = g.cell(columns) + g.gap(columns)
        // 视口正好两行高，从第 0 行开始
        val range = g.visibleRange(count = 100, columns = columns, runTop = 0f, top = 0f, bottom = 2f * pitch)
        assertEquals(0, range.first)
        assertTrue("两行最多 8 项，实际 ${range.last}", range.last <= 7)

        // 从第三行开始看：大格只跨前两行 ⇒ 第一项是第三行行首（组内下标 5）
        val scrolled = g.visibleRange(
            count = 100,
            columns = columns,
            runTop = 0f,
            top = 2f * pitch,
            bottom = 4f * pitch,
        )
        assertEquals(5, scrolled.first)
    }

    @Test
    fun `超出内容范围返回空`() {
        val g = geometry()
        val range = g.visibleRange(count = 4, columns = 4f, runTop = 0f, top = 10_000f, bottom = 11_000f)
        assertTrue(range.isEmpty())
        assertTrue(g.visibleRange(count = 0, columns = 4f, runTop = 0f, top = 0f, bottom = 100f).isEmpty())
        assertEquals(0f, g.runHeight(0, 4f), 1e-3f)
    }

    @Test
    fun `连续流模型能定位视口与命中项`() {
        val g = geometry()
        val columns = 4f
        val gap = g.gap(columns)
        // 一整段连续流：banner（120）+ 8 项格子 + 页尾（72），格子段与页尾之间只有一道图间缝
        val bannerHeight = 120f
        val footerHeight = 72f
        val runHeight = g.runHeight(8, columns)
        val blocks = listOf(
            GridBlock(GridBlockKind.Banner, 0, 1, 0f, bannerHeight),
            GridBlock(GridBlockKind.Run, 1, 8, bannerHeight, runHeight),
            GridBlock(GridBlockKind.Footer, 9, 1, bannerHeight + runHeight + gap, footerHeight),
        )
        val model = GridModel(blocks, g, columns, blocks.last().y + blocks.last().height)

        // 命中测试按 (x, y)：banner 下第一行最右小格（组内 local2，扁平下标 = 段起点 1 + 2）
        val pitch = g.cell(columns) + gap
        assertEquals(3, model.itemIndexAt(x = 3f * pitch + 2f, y = bannerHeight + 2f))
        // 第三行第 1 格（local5；前两行第 1 列被大格罩住）
        assertEquals(6, model.itemIndexAt(x = 2f, y = bannerHeight + 2f * pitch + 2f))
        // 视口只盖住 banner：最后可见项就是 banner 自己
        assertEquals(0, model.lastVisibleItemIndex(0f, bannerHeight - 1f))
        // 视口盖到 banner 下的第一行格子：最后可见项 = 段起点 + 该行最后一格（local2）
        assertEquals(3, model.lastVisibleItemIndex(0f, bannerHeight + 1f))
        // 滚到页尾：最后可见项是页尾自己
        assertEquals(
            9,
            model.lastVisibleItemIndex(bannerHeight + runHeight + gap + 1f, 10f),
        )
        // 块定位：视口顶落在哪一块（banner / 格子段 / 页尾）
        assertEquals(0, model.blockIndexAt(0f))
        assertEquals(1, model.blockIndexAt(bannerHeight + 1f))
        assertEquals(2, model.blockIndexAt(bannerHeight + runHeight + gap + 1f))
    }

    // ---- 1 列版式（9.9 `LinearLayout.java:209-221`）----

    /** 1 列：图宽 = 满宽、高 = 宽/比例、超高等比夹到视口高并水平居中、项间缝 = 2p（n=1 ⇒ 6dp）。 */
    @Test
    fun `1 列：常规照片等宽，极长图等比缩窄并居中（9_9 原版口径）`() {
        val g = GridGeometry(widthPx = 1000f, basePaddingPx = 8f, viewportHeightPx = 500f)
        val layout = g.linearLayout(floatArrayOf(2f, 1f, 0.5f))
        val gap = g.gap(1f)
        assertEquals(16f, gap, 1e-3f)

        // 横图（宽/高 = 2）：宽满、高 = 500，正好等于视口高 ⇒ 不夹取
        val wide = layout.rect(0)
        assertEquals(0f, wide.x, 1e-3f)
        assertEquals(0f, wide.y, 1e-3f)
        assertEquals(1000f, wide.width, 1e-3f)
        assertEquals(500f, wide.height, 1e-3f)

        // 方图：自然高 1000 > 视口 500 ⇒ 等比缩到 500×500、水平居中
        val square = layout.rect(1)
        assertEquals((1000f - 500f) / 2f, square.x, 1e-3f)
        assertEquals(500f + gap, square.y, 1e-3f)
        assertEquals(500f, square.width, 1e-3f)
        assertEquals(500f, square.height, 1e-3f)

        // 竖图（宽/高 = 0.5）：自然高 2000 ⇒ 缩到 250×500、水平居中
        val tall = layout.rect(2)
        assertEquals((1000f - 250f) / 2f, tall.x, 1e-3f)
        assertEquals(2f * (500f + gap), tall.y, 1e-3f)
        assertEquals(250f, tall.width, 1e-3f)
        assertEquals(500f, tall.height, 1e-3f)

        // 段高 = 最后一项底边
        assertEquals(tall.y + 500f, layout.height, 1e-3f)
    }

    @Test
    fun `1 列没有视口高上限时图高不夹取`() {
        val g = GridGeometry(widthPx = 1000f, basePaddingPx = 8f)
        val layout = g.linearLayout(floatArrayOf(0.5f))
        assertEquals(1000f, layout.rect(0).width, 1e-3f)
        assertEquals(2000f, layout.rect(0).height, 1e-3f)
    }

    /** 比例未知（宽高为 0）时按 1.0 处理，退回正方形，不产生 NaN/0 高。 */
    @Test
    fun `未知比例按正方形兜底`() {
        val g = GridGeometry(widthPx = 1000f, basePaddingPx = 8f)
        val layout = g.linearLayout(floatArrayOf(0f, -1f))
        assertEquals(1000f, layout.rect(0).height, 1e-3f)
        assertEquals(1000f, layout.rect(1).height, 1e-3f)
    }

    /** 1 列与 2 列之间逐项插值（9.9 `InterpolateLayout`）：中点 = 两份布局的算术平均。 */
    @Test
    fun `1 列与 2 列之间逐项插值`() {
        val g = GridGeometry(widthPx = 1000f, basePaddingPx = 8f, viewportHeightPx = 500f)
        val aspects = floatArrayOf(1f, 2f, 0.5f)
        val linear = g.linearLayout(aspects)
        val runHeight = linear.height + (g.runHeight(aspects.size, 2f) - linear.height) * 0.5f
        val block = GridBlock(
            GridBlockKind.Run,
            0,
            aspects.size,
            0f,
            runHeight,
            linear,
        )
        val model = GridModel(listOf(block), g, 1.5f, runHeight)

        val a = linear.rect(0)
        val b = g.groupRectAt(0, 2)
        val mid = model.runRect(block, 0)
        assertEquals((a.x + b.x) / 2f, mid.x, 1e-3f)
        assertEquals((a.width + b.width) / 2f, mid.width, 1e-3f)
        assertEquals((a.height + b.height) / 2f, mid.height, 1e-3f)

        // 1.0 与 1.0001 列几乎一样（连续，不会在 1 列边界跳变）
        val atOne = GridModel(listOf(block), g, 1f, runHeight).runRect(block, 0)
        val justAbove = GridModel(listOf(block), g, 1.0001f, runHeight).runRect(block, 0)
        assertEquals(atOne.y, justAbove.y, 0.1f)
        assertEquals(atOne.height, justAbove.height, 0.1f)

        // 段高与最后一项底边自洽
        val last = model.runRect(block, aspects.size - 1)
        assertEquals(runHeight, last.y + last.height, 1e-3f)
    }

    /** 变高段里"可见范围/命中测试"照旧成立（二分依赖底边单调）。 */
    @Test
    fun `变高段里可见范围与命中测试仍然正确`() {
        val g = GridGeometry(widthPx = 1000f, basePaddingPx = 8f, viewportHeightPx = 500f)
        val aspects = FloatArray(10) { 1f }
        val linear = g.linearLayout(aspects)
        val block = GridBlock(GridBlockKind.Run, 0, aspects.size, 0f, linear.height, linear)
        val model = GridModel(listOf(block), g, 1f, linear.height)

        // 每项 500 高 + 16 缝：视口 600 只盖住第 0、1 项
        val range = model.visibleRange(block, top = 0f, bottom = 600f)
        assertEquals(0, range.first)
        assertEquals(1, range.last)

        // 第 2 项（y = 2×516）命中
        val r2 = model.runRect(block, 2)
        assertEquals(2, model.itemIndexAt(x = r2.x + r2.width / 2f, y = r2.y + 10f))
    }
}
