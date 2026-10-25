package com.example.album.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.example.album.data.MediaItem
import com.example.album.data.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自绘 lazy 网格的**运行时**验证（Robolectric + Compose UI，不需要真机）。
 *
 * 这是"把 `LazyVerticalGrid` 换成自绘布局"之后唯一能离线证明"测量/摆放没被写坏"的手段：
 * 真的组一棵 Compose 树、真的跑一遍 measure/layout，然后核对
 * 内容总高是否等于解析公式、换列（小数）是否真的重排、滚动是否真的换可见项。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FractionalGridLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun media(i: Int) = MediaItem(
        id = i.toLong(),
        uri = Uri.parse("content://media/item/$i"),
        kind = MediaKind.Image,
        dateTakenMillis = i.toLong(),
        durationMillis = 0L,
    )

    private fun buildSlots(count: Int): List<Slot> =
        (0 until count).map { local -> Slot.Media(GridEntry(local, media(local)), local) }

    private val geometry = GridGeometry(1080f, 8f)

    private fun model(slots: List<Slot>, columns: Float): GridModel {
        val runItems = slots.count { it is Slot.Media }
        // 连续流：整段格子从 y=0 开始，没有日期头 / 分组间距
        val run = GridBlock(
            GridBlockKind.Run,
            0,
            runItems,
            0f,
            geometry.runHeight(runItems, columns),
        )
        return GridModel(listOf(run), geometry, columns, run.height)
    }

    @Test
    fun `自绘网格能量出内容高度并随小数列数重排`() {
        val slots = buildSlots(24)
        val columns = mutableStateOf(4f)
        lateinit var state: FractionalGridState

        compose.setContent {
            state = remember { FractionalGridState() }
            MaterialTheme {
                FractionalGrid(
                    slots = slots,
                    state = state,
                    columns = { columns.value },
                    buildModel = { value -> model(slots, value) },
                    overscrollEffect = null,
                    bannerOverlapPx = 0,
                    modifier = Modifier.fillMaxSize(),
                    content = {
                        Box(Modifier.fillMaxSize().background(Color.Gray))
                    },
                )
            }
        }
        compose.waitForIdle()

        // 真的跑过 measure/layout
        assertTrue("viewport 高度应 > 0", state.viewportHeightPx > 0f)
        assertEquals(
            "内容总高应等于解析公式",
            model(slots, 4f).contentHeightPx,
            state.contentHeightPx,
            1.5f,
        )

        // 换列（小数）：真的重排，而且换成 6.5 列后内容变矮
        val before = state.contentHeightPx
        compose.runOnIdle { columns.value = 6.5f }
        compose.waitForIdle()
        assertTrue("列数变多后内容应变矮：$before -> ${state.contentHeightPx}", state.contentHeightPx < before)
        assertEquals(
            "小数列数也走同一套插值公式",
            model(slots, 6.5f).contentHeightPx,
            state.contentHeightPx,
            1.5f,
        )

        // 滚动：换掉可见项
        compose.runOnIdle { state.scrollOffsetPx = state.contentHeightPx / 2f }
        compose.waitForIdle()
        assertTrue("滚动后应有可见项", state.lastVisibleItemIndex >= 0)
        assertTrue(
            "最后可见项不能超出槽位表：${state.lastVisibleItemIndex}",
            state.lastVisibleItemIndex < slots.size,
        )
    }

    @Test
    fun `滚动量被夹在合法范围内`() {
        val slots = buildSlots(12)
        lateinit var state: FractionalGridState
        compose.setContent {
            state = remember { FractionalGridState() }
            MaterialTheme {
                FractionalGrid(
                    slots = slots,
                    state = state,
                    columns = { 3f },
                    buildModel = { value -> model(slots, value) },
                    overscrollEffect = null,
                    bannerOverlapPx = 0,
                    modifier = Modifier.fillMaxSize(),
                    content = { Box(Modifier.fillMaxSize()) },
                )
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { state.dispatchRawDelta(1_000_000f) }
        compose.waitForIdle()
        assertTrue(state.scrollOffsetPx <= state.maxScrollPx + 0.5f)

        compose.runOnIdle { state.dispatchRawDelta(-1_000_000f) }
        compose.waitForIdle()
        assertEquals(0f, state.scrollOffsetPx, 0.5f)
    }
}
