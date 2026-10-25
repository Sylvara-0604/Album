package com.example.album.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `分秒格式不补零分钟`() {
        assertEquals("0:05", Formatters.duration(5_000L))
        assertEquals("1:05", Formatters.duration(65_000L))
        assertEquals("12:34", Formatters.duration(754_000L))
        assertEquals("59:59", Formatters.duration(3_599_000L))
    }

    @Test
    fun `满一小时变成时分秒`() {
        assertEquals("1:00:00", Formatters.duration(3_600_000L))
        assertEquals("1:02:03", Formatters.duration(3_723_000L))
        assertEquals("10:00:01", Formatters.duration(36_001_000L))
    }

    @Test
    fun `毫秒向下取整`() {
        assertEquals("0:00", Formatters.duration(999L))
        assertEquals("0:01", Formatters.duration(1_999L))
    }

    @Test
    fun `未知时长返回空串`() {
        assertEquals("", Formatters.duration(0L))
        assertEquals("", Formatters.duration(-1L))
    }

    @Test
    fun `缓存大小按 1024 进位`() {
        assertEquals("512 B", Formatters.fileSize(512L))
        assertEquals("1.5 KB", Formatters.fileSize(1536L))
        assertEquals("12.3 MB", Formatters.fileSize(12_897_484L))
        assertEquals("1.0 GB", Formatters.fileSize(1_073_741_824L))
    }

    @Test
    fun `空缓存返回空串`() {
        assertEquals("", Formatters.fileSize(0L))
        assertEquals("", Formatters.fileSize(-5L))
    }
}
