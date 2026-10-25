package com.example.album.ui.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CropMath] 的纯 JVM 单测：不启 Robolectric、不碰任何 Android 类。
 *
 * 统一的坐标设定：图片显示区 1000×800，初始框 (100,100)-(600,500)，最小尺寸 48px。
 */
class CropMathTest {

    private val bounds = CropRect(0f, 0f, 1000f, 800f)
    private val start = CropRect(100f, 100f, 600f, 500f)

    @Test
    fun `拖左上角时对角钉死不动`() {
        val result = CropMath.resize(start, Handle.TopLeft, -20f, -30f, bounds, MIN_SIZE)
        assertRect(CropRect(80f, 70f, 600f, 500f), result)
    }

    @Test
    fun `拖右下角时对角钉死不动`() {
        val result = CropMath.resize(start, Handle.BottomRight, 40f, 50f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 100f, 640f, 550f), result)
    }

    @Test
    fun `拖右上角只动右边和上边`() {
        val result = CropMath.resize(start, Handle.TopRight, 40f, -30f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 70f, 640f, 500f), result)
    }

    @Test
    fun `拖左下角只动左边和下边`() {
        val result = CropMath.resize(start, Handle.BottomLeft, -20f, 50f, bounds, MIN_SIZE)
        assertRect(CropRect(80f, 100f, 600f, 550f), result)
    }

    @Test
    fun `拖上边时下边固定且横向不动`() {
        val result = CropMath.resize(start, Handle.Top, 0f, 60f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 160f, 600f, 500f), result)
    }

    @Test
    fun `拖下边时上边固定且横向不动`() {
        val result = CropMath.resize(start, Handle.Bottom, 0f, -60f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 100f, 600f, 440f), result)
    }

    @Test
    fun `拖左边时右边固定`() {
        val result = CropMath.resize(start, Handle.Left, 40f, 0f, bounds, MIN_SIZE)
        assertRect(CropRect(140f, 100f, 600f, 500f), result)
    }

    @Test
    fun `拖右边时左边固定`() {
        val result = CropMath.resize(start, Handle.Right, -40f, 0f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 100f, 560f, 500f), result)
    }

    @Test
    fun `往内拖过头被最小尺寸夹住`() {
        val fromLeft = CropMath.resize(start, Handle.Left, 10_000f, 0f, bounds, MIN_SIZE)
        assertRect(CropRect(600f - MIN_SIZE, 100f, 600f, 500f), fromLeft)

        val fromTop = CropMath.resize(start, Handle.Top, 0f, 10_000f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 500f - MIN_SIZE, 600f, 500f), fromTop)

        val fromRight = CropMath.resize(start, Handle.Right, -10_000f, 0f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 100f, 100f + MIN_SIZE, 500f), fromRight)

        val fromBottom = CropMath.resize(start, Handle.Bottom, 0f, -10_000f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 100f, 600f, 100f + MIN_SIZE), fromBottom)
    }

    @Test
    fun `往外拖过头被图片显示区夹住`() {
        val toTopLeft = CropMath.resize(start, Handle.TopLeft, -10_000f, -10_000f, bounds, MIN_SIZE)
        assertRect(CropRect(0f, 0f, 600f, 500f), toTopLeft)

        val toBottomRight = CropMath.resize(start, Handle.BottomRight, 10_000f, 10_000f, bounds, MIN_SIZE)
        assertRect(CropRect(100f, 100f, 1000f, 800f), toBottomRight)
    }

    @Test
    fun `显示区比最小尺寸还小时不出空区间也不会抛异常`() {
        val tiny = CropRect(0f, 0f, 30f, 30f)
        val result = CropMath.resize(tiny, Handle.Left, 100f, 0f, tiny, MIN_SIZE)
        assertRect(tiny, result)
    }

    @Test
    fun `bounds 无效时原样返回`() {
        assertEquals(start, CropMath.resize(start, Handle.Left, 10f, 0f, CropRect.Zero, MIN_SIZE))
        assertEquals(start, CropMath.move(start, 10f, 10f, CropRect.Zero))
        assertEquals(CropRect.Zero, CropMath.initial(CropRect.Zero))
    }

    @Test
    fun `框内拖动整框平移且尺寸不变`() {
        val result = CropMath.move(start, 30f, -20f, bounds)
        assertRect(CropRect(130f, 80f, 630f, 480f), result)
        assertEquals(start.width, result.width, 1e-4f)
        assertEquals(start.height, result.height, 1e-4f)
    }

    @Test
    fun `平移贴边停住且尺寸不变`() {
        val rightBottom = CropMath.move(start, 10_000f, 10_000f, bounds)
        assertRect(CropRect(500f, 400f, 1000f, 800f), rightBottom)

        val leftTop = CropMath.move(start, -10_000f, -10_000f, bounds)
        assertRect(CropRect(0f, 0f, 500f, 400f), leftTop)
    }

    @Test
    fun `归一保尺寸只微调位置`() {
        // 500×500 的框跑到右下角外面：应该整体滑回来，而不是被压扁
        val result = CropMath.clamp(CropRect(900f, 700f, 1400f, 1200f), bounds, MIN_SIZE)
        assertRect(CropRect(500f, 300f, 1000f, 800f), result)
        assertEquals(500f, result.width, 1e-4f)
        assertEquals(500f, result.height, 1e-4f)
    }

    @Test
    fun `空框被归一成最小尺寸`() {
        val result = CropMath.clamp(CropRect.Zero, bounds, MIN_SIZE)
        assertRect(CropRect(0f, 0f, MIN_SIZE, MIN_SIZE), result)
    }

    @Test
    fun `初始框居中内缩`() {
        val result = CropMath.initial(bounds)
        assertRect(CropRect(50f, 40f, 950f, 760f), result)
    }

    @Test
    fun `双指缩放以框中心为基准`() {
        val result = CropMath.scale(start, 0.5f, bounds, MIN_SIZE)
        assertRect(CropRect(225f, 200f, 475f, 400f), result)
        // 等比：宽高比不能变
        assertEquals(start.width / start.height, result.width / result.height, 1e-4f)
    }

    @Test
    fun `缩到最小尺寸为止`() {
        val result = CropMath.scale(start, 0.01f, bounds, MIN_SIZE)
        assertEquals(MIN_SIZE, result.height, 1e-4f)
        assertEquals(60f, result.width, 1e-4f)
    }

    @Test
    fun `放到图片显示区为止`() {
        val result = CropMath.scale(start, 100f, bounds, MIN_SIZE)
        assertRect(bounds, result)
    }

    @Test
    fun `contains 判定框内框外`() {
        assertTrue(CropMath.contains(start, 350f, 300f))
        assertFalse(CropMath.contains(start, 50f, 300f))
        assertFalse(CropMath.contains(CropRect.Zero, 0f, 0f))
    }

    private fun assertRect(expected: CropRect, actual: CropRect) {
        assertEquals("left", expected.left, actual.left, TOLERANCE)
        assertEquals("top", expected.top, actual.top, TOLERANCE)
        assertEquals("right", expected.right, actual.right, TOLERANCE)
        assertEquals("bottom", expected.bottom, actual.bottom, TOLERANCE)
    }

    private companion object {
        /** 屏幕侧 48dp 换算出来的像素（单测里直接给 48px） */
        const val MIN_SIZE = 48f

        const val TOLERANCE = 1e-4f
    }
}
