package com.example.album.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换页动画的解算（9.9 `AlbumAnimator` reason 10–13 的等价物）：
 * 出/进的 z 与 alpha、以及透视缩放 `1/(1−z)`。
 */
class PageSwitchTest {

    private val eps = 1e-4f

    @Test
    fun `下钻 新页从远处推近 结束时到位且不透明`() {
        val start = pageItemState(
            role = PageRole.ENTERING,
            direction = PageSwitchDirection.INTO,
            enterProgress = 0f,
            exitProgress = 1f,
            depth = 0.6f,
            kind = ItemDepthKind.NORMAL,
        )
        assertEquals(-0.6f, start.z, eps)
        assertEquals(0f, start.alpha, eps)

        val end = pageItemState(
            role = PageRole.ENTERING,
            direction = PageSwitchDirection.INTO,
            enterProgress = 1f,
            exitProgress = 1f,
            depth = 0.6f,
            kind = ItemDepthKind.NORMAL,
        )
        assertEquals(0f, end.z, eps)
        assertEquals(1f, end.alpha, eps)
    }

    @Test
    fun `下钻 旧页朝镜头飞散 z 到正 0点7乘随机`() {
        val end = pageItemState(
            role = PageRole.EXITING,
            direction = PageSwitchDirection.INTO,
            enterProgress = 1f,
            exitProgress = 1f,
            depth = 0.5f,
            kind = ItemDepthKind.NORMAL,
        )
        assertEquals(0.35f, end.z, eps)
        assertEquals(0f, end.alpha, eps)
    }

    @Test
    fun `返回 旧页退向远处 z 为负 新页从近处收回`() {
        val old = pageItemState(
            role = PageRole.EXITING,
            direction = PageSwitchDirection.OUT,
            enterProgress = 1f,
            exitProgress = 1f,
            depth = 0.4f,
            kind = ItemDepthKind.NORMAL,
        )
        assertEquals(-0.4f, old.z, eps)
        val parent = pageItemState(
            role = PageRole.ENTERING,
            direction = PageSwitchDirection.OUT,
            enterProgress = 0f,
            exitProgress = 1f,
            depth = 0.4f,
            kind = ItemDepthKind.NORMAL,
        )
        assertEquals(0.4f, parent.z, eps)
    }

    @Test
    fun `固定深度 2x2 大格 下钻负1 返回负1 退出朝镜头`() {
        assertEquals(
            -1f,
            pageItemState(PageRole.ENTERING, PageSwitchDirection.INTO, 0f, 1f, 0.3f, ItemDepthKind.ROAD_SIGN).z,
            eps,
        )
        assertEquals(
            -1f,
            pageItemState(PageRole.EXITING, PageSwitchDirection.OUT, 1f, 1f, 0.3f, ItemDepthKind.ROAD_SIGN).z,
            eps,
        )
        assertEquals(
            0.7f,
            pageItemState(PageRole.EXITING, PageSwitchDirection.INTO, 1f, 1f, 0.3f, ItemDepthKind.ROAD_SIGN).z,
            eps,
        )
    }

    @Test
    fun `banner 返回方向固定 0点25`() {
        assertEquals(
            0.25f,
            pageItemState(PageRole.ENTERING, PageSwitchDirection.OUT, 0f, 1f, 0.9f, ItemDepthKind.BANNER).z,
            eps,
        )
    }

    @Test
    fun `透视缩放 1 除 1 减 z`() {
        assertEquals(1f, pageScaleFromZ(0f), eps)
        assertEquals(0.5f, pageScaleFromZ(-1f), eps)
        assertEquals(3.3333f, pageScaleFromZ(0.7f), 1e-3f)
    }

    @Test
    fun `空闲页不动`() {
        val idle = pageItemState(PageRole.IDLE, PageSwitchDirection.INTO, 0f, 0f, 0.5f, ItemDepthKind.NORMAL)
        assertEquals(0f, idle.z, eps)
        assertEquals(1f, idle.alpha, eps)
    }

    /**
     * r34 换页交接（用户令：9.9 串行那一下空白可以接受，要"衔接柔和"）：
     * 用真实的曲线（[AlbumEaseIn]/[AlbumEaseOut] 先作用在进度上，α 再取三次方）逐毫秒扫描整段，
     * 钉死三条性质 —— ① 任何一帧都不会两页同时 ≥5%（没有双影）；② 也不会两页同时 <1%（没有整屏白）；
     * ③ 总不透明度最低落在 2%–8%（"柔和的一口气"），且只维持 ≤4 帧。
     */
    @Test
    fun `换页交接 不双影 也不整屏白`() {
        val total = PAGE_SWITCH_MILLIS + PAGE_SWITCH_ENTER_DELAY.toInt()
        var ghostFrames = 0
        var blankFrames = 0
        val sums = ArrayList<Float>(total + 1)
        for (t in 0..total) {
            val exitT = (t.toFloat() / PAGE_SWITCH_MILLIS).coerceIn(0f, 1f)
            val exitProg = AlbumEaseIn.transform(exitT)
            val enterT = ((t - PAGE_SWITCH_ENTER_DELAY).coerceAtLeast(0).toFloat() / PAGE_SWITCH_MILLIS)
                .coerceIn(0f, 1f)
            val enterProg = AlbumEaseOut.transform(enterT)
            val outAlpha = pageItemState(
                PageRole.EXITING,
                PageSwitchDirection.INTO,
                enterProgress = 1f,
                exitProgress = exitProg,
                depth = 0.5f,
                kind = ItemDepthKind.NORMAL,
            ).alpha
            val inAlpha = pageItemState(
                PageRole.ENTERING,
                PageSwitchDirection.INTO,
                enterProgress = enterProg,
                exitProgress = 1f,
                depth = 0.5f,
                kind = ItemDepthKind.NORMAL,
            ).alpha
            if (outAlpha >= 0.05f && inAlpha >= 0.05f) ghostFrames++
            if (outAlpha < 0.01f && inAlpha < 0.01f) blankFrames++
            sums.add(outAlpha + inAlpha)
        }
        assertEquals("双影帧", 0, ghostFrames)
        assertEquals("整屏白帧", 0, blankFrames)
        val minSum = sums.minOrNull() ?: 1f
        assertTrue("最低总 α=$minSum 应落在 2%–8%", minSum in 0.02f..0.08f)
        val dimFrames = sums.count { it < 0.05f }
        assertTrue("总 α<5% 只应持续 ≤25ms，实际 $dimFrames ms", dimFrames <= 25)
    }
}
