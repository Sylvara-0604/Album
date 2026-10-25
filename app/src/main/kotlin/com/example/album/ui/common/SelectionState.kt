package com.example.album.ui.common

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 网格的「长按选中」状态（9.9 `SelectionModeManager` 的 MULTIPLE 模式：长按进入、点按切换、按返回退出）。
 *
 * 只存 id（不是下标）：时间线与文件夹网格是两套下标空间，id 才跨页面稳定。
 * 状态对象放在屏幕层（时间线 / 文件夹网格共用一块网格），所以选择顶栏与网格读的是同一份。
 */
@Stable
class SelectionState {

    /** 是否处于选择模式（= 顶栏切换、点按变成"选中/取消"而不是"打开"） */
    var active by mutableStateOf(false)
        private set

    private val ids = mutableStateMapOf<Long, Unit>()

    val count: Int get() = ids.size

    fun isSelected(id: Long): Boolean = ids.containsKey(id)

    /** 长按：进入选择模式并选中这一项（9.9 长按 = 开始选择并选中）。 */
    fun begin(id: Long) {
        active = true
        ids[id] = Unit
    }

    /** 点按（选择模式下）：切换选中；全部取消就退出选择模式。 */
    fun toggle(id: Long) {
        if (ids.remove(id) == null) ids[id] = Unit
        if (ids.isEmpty()) active = false
    }

    /** 退出选择模式（顶栏的 × / 系统返回 / 操作完成）。 */
    fun clear() {
        ids.clear()
        active = false
    }

    /** 当前选中的 id 快照（分享 / 删除用）。 */
    fun selectedIds(): List<Long> = ids.keys.toList()
}
