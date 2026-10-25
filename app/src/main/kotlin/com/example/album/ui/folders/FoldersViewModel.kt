package com.example.album.ui.folders

import android.os.SystemClock

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.album.data.MediaBucket
import com.example.album.data.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 文件夹列表状态：一次扫描的结果 + 加载/错误态。
 *
 * 不走 Paging：文件夹是几十的量级，`MediaRepository.buckets()` 一次扫描就全取回，且已按名称升序排好
 * （UI 不再排序，也不提供排序控件）。
 */
@Immutable
data class FoldersUiState(
    val buckets: List<MediaBucket> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/**
 * 文件夹列表 ViewModel。
 *
 * 扫描在 `init` 里做一次；之后由「错误态重试」与「回到本屏」驱动 [refresh]。
 *
 * 用户 2026-10-24 报「增加新文件/文件夹，app 内没有及时刷新」：本页是压栈页，从文件夹网格
 * pop 回来时 ViewModel 还活在 entry 里 ⇒ 只扫一次会把新文件夹藏到进程重启。
 * 现在每次 **RESUMED** 做一次**静默**重扫（不切 loading、不闪骨架），并用
 * [RETURN_REFRESH_MIN_INTERVAL_MILLIS] 挡住相邻两次（切页/转屏不会连扫）。
 * 仍不常驻 ContentObserver —— 一次全表 cursor 几十毫秒，只在回到本屏时付一次。
 */
class FoldersViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _state = MutableStateFlow(FoldersUiState())
    val state: StateFlow<FoldersUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** 上一次扫描完成的时刻（`SystemClock.uptimeMillis`），用于 [refreshOnReturn] 的节流。 */
    private var lastLoadedAt = 0L

    init {
        refresh()
    }

    /** 回到本屏时的静默重扫：不切 loading 态、相邻两次至少间隔 [RETURN_REFRESH_MIN_INTERVAL_MILLIS]。 */
    fun refreshOnReturn() {
        if (SystemClock.uptimeMillis() - lastLoadedAt < RETURN_REFRESH_MIN_INTERVAL_MILLIS) return
        refresh(silent = true)
    }

    /**
     * 重新扫描。扫描中重复调用直接忽略（连点重试不会排队堆几次全表扫）。
     *
     * @param silent true = 保留当前列表、不显示骨架（回到本屏时用，避免闪一下）
     */
    fun refresh(silent: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, failed = false) }
            val result = runCatching { repository.buckets() }
            val failure = result.exceptionOrNull()
            // 协程被取消不算"读取失败"：照常往上抛，别把取消吞成错误态
            if (failure is CancellationException) throw failure
            val buckets = result.getOrNull()
            if (buckets == null) {
                // 权限被撤 / provider 异常：保留上一次的结果（有内容就继续显示），只把错误态交给 UI
                _state.update { it.copy(loading = false, failed = true) }
            } else {
                _state.value = FoldersUiState(buckets = buckets, loading = false, failed = false)
            }
            lastLoadedAt = SystemClock.uptimeMillis()
        }
    }

    private companion object {
        /** 回到本屏的静默重扫最短间隔：切页/转屏来回不会连扫 */
        const val RETURN_REFRESH_MIN_INTERVAL_MILLIS = 1500L
    }
}
