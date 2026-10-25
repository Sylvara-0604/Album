package com.example.album.ui.timeline

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.album.data.GridColumnPreference
import com.example.album.data.MediaItem
import com.example.album.data.MediaRepository
import com.example.album.data.MediaStorePagingSource
import com.example.album.ui.components.BannerSlide
import com.example.album.ui.components.BannerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
// banner 洗牌队列用 ArrayDeque（kotlin.collections，无需 import）

/**
 * 时间线状态：分页数据、列数、banner、内容变化监听。
 *
 * 分页用 `cachedIn(viewModelScope)`：屏幕转屏/进出页面不重查；
 * 库内容变化（ContentObserver，去抖 500ms）通过重建 PagingSource 精准失效。
 */
class TimelineViewModel(
    private val repository: MediaRepository,
    private val columnPreference: GridColumnPreference,
) : ViewModel() {

    /**
     * 当前分页源。库内容变化时**直接 `invalidate()`**（Paging3 的失效语义），
     * 不再重建 Pager：重建会让 `itemCount` 归零一次、整屏先清空再回来（用户 2026-10-24 报
     * 「有时候页面还是会闪（也可能是刷新）」）。
     */
    private var currentSource: MediaStorePagingSource? = null

    val pagedMedia: Flow<PagingData<MediaItem>> =
        repository.pagedMedia(onSource = { currentSource = it }).cachedIn(viewModelScope)

    var columns by mutableIntStateOf(columnPreference.columns)
        private set

    private val _banner = MutableStateFlow(BannerState())
    val banner: StateFlow<BannerState> = _banner.asStateFlow()

    private var bannerCandidates: List<MediaItem> = emptyList()
    private var bannerJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // 后台期间不立刻失效（那会和"回前台补一次失效"叠成**两次刷新**），
            // 只记一笔，等回到前台统一失效一次。
            if (!resumed) {
                pendingWhilePaused = true
                return
            }
            scheduleInvalidate()
        }
    }

    init {
        // 观察者**常驻**（进程活到 VM 清理为止）：
        // 只在 RESUMED 注册的话，"后台期间库变了"这件事根本收不到 ⇒ 回前台只能"无条件失效一次"，
        // 于是"改动 → 回前台"会连刷两次（用户 2026-10-24 报 E1「增加文件导致页面连续刷新两次」）。
        // 常驻 + `pendingWhilePaused` 之后：每次真实变化只产生**一次**失效。
        repository.addObserver(observer)
    }

    private var invalidateJob: Job? = null

    /** 是否已经经历过第一次 RESUMED（冷启动那一次不触发"回前台补失效"）。 */
    private var everResumed = false

    /** 页面是否在前台（决定 onChange 是"立刻去抖失效"还是"只记一笔"）。 */
    private var resumed = false

    /** 后台期间库变过（对齐 9.9 `BaseAdapter.mContentNeedsReload`）。 */
    private var pendingWhilePaused = false

    /** 本轮连续 onChange 的起点（用于"批量写入最多拖 N 毫秒也必须失效一次"）。 */
    private var firstChangeAt = 0L

    /** 双指换列提交：clamp 到 3..7 并持久化（唯一持久化用途）。 */
    fun changeColumns(value: Int) {
        val clamped = value.coerceIn(GridColumnPreference.MIN_COLUMNS, GridColumnPreference.MAX_COLUMNS)
        if (clamped == columns) return
        columns = clamped
        columnPreference.columns = clamped
    }

    fun stopObserving() = repository.removeObserver(observer)

    /**
     * 首页首次出现（冷启动）：选出第一张 banner（100ms 淡入）。
     *
     * 用户 2026-10-24 令：「banner 切换页面再回来后切一张图的机制删除」⇒ 之后每次 RESUMED
     * （从设置 / 文件夹 / 查看器回来）**都不再换图**，只有计时器到点才换。
     */
    fun onScreenResumed() {
        resumed = true
        // 回前台只在"后台期间库真的变过"时失效一次。
        // 以前是**无条件**失效 ⇒ 与观察者那次去抖失效叠成两次刷新（E1）。
        if (!everResumed) {
            everResumed = true
        } else if (pendingWhilePaused) {
            pendingWhilePaused = false
            invalidateNow()
        }
        if (bannerJob?.isActive == true) return
        if (_banner.value.current != null) return
        bannerJob = viewModelScope.launch {
            ensureBannerPool()
            if (bannerCandidates.isEmpty()) return@launch
            advanceBanner(initial = true)
        }
    }

    /** 离开首页（进设置/文件夹/查看器或退到后台）：之后的变化先记账，等回前台再失效一次。 */
    fun onScreenPaused() {
        resumed = false
    }

    /** 定时轮换（可见 5s / 不可见 15s，计时在 UI 侧，按 RESUMED 状态暂停）。 */
    fun advanceBanner() {
        if (bannerJob?.isActive == true) return
        bannerJob = viewModelScope.launch {
            // 池在库变化时被清空过 ⇒ 到点换图时顺手重取（不做周期查询）
            ensureBannerPool()
            advanceBanner(initial = false)
        }
    }

    private suspend fun ensureBannerPool() {
        if (bannerCandidates.isEmpty()) bannerCandidates = repository.bannerCandidates()
    }

    /** 交叉淡化播完：把旧图层丢掉（少留一张图）。 */
    fun onBannerCrossfadeFinished(version: Int) {
        _banner.update { if (it.version == version) it.copy(previous = null) else it }
    }

    /**
     * 下拉刷新已按用户 2026-10-17 令**删除**：首页顶部下拉只做 banner 放大，不重扫媒体库。
     * （保留这个入口给「设置 → 清除缓存」这类显式操作以后复用的话要另开，别在 UI 里静默调用。）
     */

    /**
     * 洗牌队列（9.9 `BannerContentPlaylist`：一轮 N 张不重复，走完才回卷，审计 E17）。
     *
     * 旧实现只排除"当前这一张" ⇒ 图少时隔一两张就回来了，看着像"没换"。
     */
    private val bannerQueue = ArrayDeque<BannerSlide>()

    private fun refillBannerQueue(excludeId: Long?): Boolean {
        bannerQueue.clear()
        bannerQueue.addAll(
            bannerCandidates
                .asSequence()
                .filter { it.id != excludeId }
                .map { BannerSlide(id = it.id, uri = it.uri, dateMillis = it.dateTakenMillis) }
                .shuffled(),
        )
        return bannerQueue.isNotEmpty()
    }

    private fun nextFromQueue(excludeId: Long?): BannerSlide? {
        if (bannerQueue.isEmpty() && !refillBannerQueue(excludeId)) return null
        // 队首正好是刚看过那张 ⇒ 和队尾换位（少一张的极端情况下也不会立刻重复）
        if (bannerQueue.firstOrNull()?.id == excludeId && bannerQueue.size > 1) {
            bannerQueue.addLast(bannerQueue.removeFirst())
        }
        return bannerQueue.removeFirstOrNull()
    }

    private fun advanceBanner(initial: Boolean) {
        val current = _banner.value.current
        val target = _banner.value.next?.takeIf { it.id != current?.id }
            ?: nextFromQueue(excludeId = current?.id)
            ?: return
        _banner.value = BannerState(
            current = target,
            previous = if (initial) null else current,
            next = nextFromQueue(excludeId = target.id),
            version = _banner.value.version + 1,
        )
    }

    /**
     * 库变化 → 去抖后失效分页源。
     *
     * ⚠️ 必须有**上限**：拷贝 200 张照片会产生一串间隔 <500ms 的 onChange，
     * 纯"每次重置 500ms"的去抖会让失效永远不触底（9.9 `ContentChangeObserver` 也是
     * `CONTENT_CHANGE_THROTTLE_MAX = 5000` 强制的）。
     */
    private fun scheduleInvalidate() {
        val now = SystemClock.uptimeMillis()
        if (firstChangeAt == 0L) firstChangeAt = now
        invalidateJob?.cancel()
        val elapsed = now - firstChangeAt
        if (elapsed >= INVALIDATE_MAX_MILLIS) {
            invalidateNow()
            return
        }
        invalidateJob = viewModelScope.launch {
            delay(minOf(INVALIDATE_DEBOUNCE_MILLIS, INVALIDATE_MAX_MILLIS - elapsed))
            invalidateNow()
        }
    }

    /** 立刻失效：分页源 `invalidate()`（保留已加载项、只重查）+ 顺手丢掉 banner 池的旧快照。 */
    private fun invalidateNow() {
        firstChangeAt = 0L
        invalidateJob?.cancel()
        invalidateJob = null
        bannerCandidates = emptyList()
        currentSource?.invalidate()
    }

    /** 建文件夹/删除这类"我们自己刚写完库"的显式失效入口（零额外查询）。 */
    fun invalidateAfterWrite() = invalidateNow()

    override fun onCleared() {
        stopObserving()
    }

    private companion object {
        /** 相册写入往往连发好几条 onChange，去抖到一次失效 */
        const val INVALIDATE_DEBOUNCE_MILLIS = 500L

        /** 连续写入的上限（对齐 9.9 `CONTENT_CHANGE_THROTTLE_MAX`）：到点无论如何失效一次 */
        const val INVALIDATE_MAX_MILLIS = 3000L
    }
}
