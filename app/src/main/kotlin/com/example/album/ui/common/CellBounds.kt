package com.example.album.ui.common

/**
 * 一个 item 在**窗口坐标**里的矩形（px）：换页 z 动画要用它算"以屏心为投影点"的径向位移。
 *
 * 故意用**普通可变字段**（不是 Compose 状态）：滚动时每个可见项每帧都会刷新一次 bounds，
 * 用 `mutableStateOf` 会让每帧产生 60×N 次状态写入 + 失效（实测滚动热路径上的白工）。
 * 读它的地方都在 `graphicsLayer` / `drawBehind` 里，而那里本来就会随滚动/动画每帧重跑。
 */
class CellBoundsHolder {

    var value: CellBounds = CellBounds.Zero
}

/** 一个矩形（px），左/上/宽/高。 */
data class CellBounds(val left: Int, val top: Int, val width: Int, val height: Int) {

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        val Zero = CellBounds(0, 0, 0, 0)
    }
}
