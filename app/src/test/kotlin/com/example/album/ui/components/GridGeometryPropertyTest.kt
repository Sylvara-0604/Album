package com.example.album.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 性质 / 变形测试（B3）：不打点值，只钉"对任意输入都必须成立"的不变量。
 *
 * 为什么加这层：现有 `GridGeometryTest` 是**点值 golden**（`rect(1,4f).x == 2*251.75`），
 * 一旦公式被"看起来等价"地改掉，只有恰好被钉住的那几个下标会报警。这里用固定种子的随机宽/间距，
 * 覆盖 1–7 列（含小数插值）× 任意屏幕宽，检查几何自洽性。
 *
 * 不引新依赖（不用 kotest-property）：`Random(seed)` 可复现，失败时打印 seed 与参数。
 */
class GridGeometryPropertyTest {

    private fun sample(): List<GridGeometry> = buildList {
        for (seed in 1..25) {
            val r = Random(seed)
            add(
                GridGeometry(
                    widthPx = r.nextDouble(320.0, 2400.0).toFloat(),
                    basePaddingPx = r.nextDouble(1.0, 12.0).toFloat(),
                )
            )
        }
    }

    @Test
    fun `任意宽度任意列数：格子尺寸为正、横向上不溢出网格`() {
        for (g in sample()) {
            for (n in 1..7) {
                val cols = n.toFloat()
                val count = 4 * n + 6
                for (i in 0 until count) {
                    val r = g.rect(i, cols)
                    assertTrue("seed? W=${g.widthPx} pad=${g.basePaddingPx} n=$n i=$i w=${r.width}",
                        r.width > 0f && r.height > 0f)
                    assertTrue("x<0 W=${g.widthPx} n=$n i=$i x=${r.x}", r.x >= -1e-3f)
                    assertTrue("y<0 n=$n i=$i y=${r.y}", r.y >= -1e-3f)
                    assertTrue(
                        "溢出 W=${g.widthPx} n=$n i=$i right=${r.x + r.width}",
                        r.x + r.width <= g.widthPx + 1e-2f
                    )
                }
            }
        }
    }

    @Test
    fun `变形：整数列时每行正好填满可用宽度（与 cell 公式互为反函数）`() {
        for (g in sample()) {
            for (n in 3..7) {
                // cell*n + gap*(n-1) 必须等于 widthPx —— 这是 cell() 的定义，换列时不该漂
                val tile = g.cell(n.toFloat())
                val gap = g.gap(n.toFloat())
                val filled = n * tile + (n - 1) * gap
                assertTrue("W=${g.widthPx} n=$n filled=$filled", abs(filled - g.widthPx) < 0.05f)

                // 位置独立验证：local n-2 的 slot = n-1（最后一列），右边界必须贴住 widthPx
                val last = g.groupRectAt(n - 2, n)
                assertTrue(
                    "W=${g.widthPx} n=$n right=${last.x + last.width}",
                    abs(last.x + last.width - g.widthPx) < 0.05f
                )
            }
        }
    }

    @Test
    fun `2×2 大格之外的任意两项互不重叠`() {
        for (g in sample()) {
            for (n in 3..7) {
                val rects = (0 until 3 * n).map { g.groupRectAt(it, n) }
                for (i in rects.indices) {
                    for (j in i + 1 until rects.size) {
                        val a = rects[i]
                        val b = rects[j]
                        val overlap = a.x < b.x + b.width - 1e-2f &&
                            b.x < a.x + a.width - 1e-2f &&
                            a.y < b.y + b.height - 1e-2f &&
                            b.y < a.y + a.height - 1e-2f
                        assertTrue(
                            "重叠 W=${g.widthPx} n=$n i=$i j=$j (${a.x},${a.y},${a.width}) vs (${b.x},${b.y},${b.width})",
                            !overlap
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `小数插值必须严格等于两端整数布局的线性组合`() {
        for (g in sample()) {
            for (n in 1..6) {
                for (step in 1..9) {
                    val t = step / 10f
                    val a = g.groupRectAt(3, n)
                    val b = g.groupRectAt(3, n + 1)
                    val mid = g.rect(3, n + t)
                    assertTrue("x W=${g.widthPx} n=$n t=$t", abs(mid.x - (a.x + (b.x - a.x) * t)) < 1e-2f)
                    assertTrue("y W=${g.widthPx} n=$n t=$t", abs(mid.y - (a.y + (b.y - a.y) * t)) < 1e-2f)
                    assertTrue("w W=${g.widthPx} n=$n t=$t", abs(mid.width - (a.width + (b.width - a.width) * t)) < 1e-2f)
                }
            }
        }
    }

    @Test
    fun `段高必须罩住最后一个普通项与 2×2 大格的下缘`() {
        for (g in sample()) {
            for (n in 1..7) {
                for (count in 1..2 * n + 2) {
                    val h = g.runHeight(count, n.toFloat())
                    val last = g.rect(count - 1, n.toFloat())
                    assertTrue("W=${g.widthPx} n=$n count=$count h=$h lastBottom=${last.y + last.height}",
                        h + 1e-2f >= last.y + last.height)
                    if (n > 2) {
                        val first = g.rect(0, n.toFloat())
                        assertTrue("大格被裁 n=$n count=$count h=$h bigBottom=${first.y + first.height}",
                            h + 1e-2f >= first.y + first.height)
                    }
                }
            }
        }
    }
}
