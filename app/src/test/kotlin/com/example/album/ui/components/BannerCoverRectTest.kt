package com.example.album.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * banner 的满幅覆盖矩形（等价 `ContentScale.Crop`）。
 *
 * 判据：盖满目标框（两轴都不小）、居中（两轴溢出对称）、保持原图宽高比。
 */
class BannerCoverRectTest {

    @Test
    fun wideImageCoversBoxVertically() {
        // 4000×3000（4:3）铺进 1080×607（≈16:9）：高度限制，宽出两侧
        val rect = bannerCoverRect(imageWidth = 4000, imageHeight = 3000, dstWidth = 1080f, dstHeight = 607f)
        assertTrue("必须盖满宽度", rect.width >= 1080f - 0.01f)
        assertTrue("必须盖满高度", rect.height >= 607f - 0.01f)
        assertEquals(4000f / 3000f, rect.width / rect.height, 1e-4f)
        // 居中：左右溢出对称
        assertEquals(-(rect.width - 1080f) / 2f, rect.left, 1e-3f)
        assertEquals(-(rect.height - 607f) / 2f, rect.top, 1e-3f)
    }

    @Test
    fun tallImageCoversBoxHorizontally() {
        // 1080×2400 竖图铺进 1080×607：宽度限制，高度溢出上下
        val rect = bannerCoverRect(imageWidth = 1080, imageHeight = 2400, dstWidth = 1080f, dstHeight = 607f)
        assertEquals(1080f, rect.width, 0.01f)
        assertTrue("高度必须盖满", rect.height >= 607f - 0.01f)
        assertTrue("上下超出居中（top 为负且对称）", rect.top < 0f)
        assertEquals(-(rect.height - 607f) / 2f, rect.top, 1e-3f)
    }

    @Test
    fun matchingAspectFillsExactly() {
        val rect = bannerCoverRect(imageWidth = 1600, imageHeight = 900, dstWidth = 1080f, dstHeight = 607.5f)
        assertEquals(1080f, rect.width, 0.01f)
        assertEquals(607.5f, rect.height, 0.01f)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
    }

    @Test
    fun invalidInputIsEmpty() {
        assertEquals(CoverRect(0f, 0f, 0f, 0f), bannerCoverRect(0, 100, 1080f, 607f))
        assertEquals(CoverRect(0f, 0f, 0f, 0f), bannerCoverRect(100, 100, 0f, 607f))
    }
}
