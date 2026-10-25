package com.example.album.ui.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.album.AlbumApplication
import com.example.album.data.GridColumnPreference
import com.example.album.data.MediaItem
import com.example.album.ui.common.PageId
import com.example.album.ui.common.SelectionState
import com.example.album.ui.common.rememberSelectionActions
import com.example.album.ui.components.BottomCapsule
import com.example.album.ui.components.EmptyState
import com.example.album.ui.components.MediaGrid
import com.example.album.ui.components.rememberFractionalGridState

/**
 * 文件夹网格（蓝图 P2 · S4）：与时间线同一个 [MediaGrid]（连续流 / 双指换列 / 按下反馈全复用），
 * 只是数据源换成 `pagedMedia(bucketId)`、没有 banner。
 *
 * 列数与时间线**共用同一份持久化**（`prefs_nbr_of_columns`）：在本屏双指换列会写回偏好，
 * 出去再进来、乃至重启后都记得。
 *
 * **没有顶栏、没有返回按钮**（用户 2026-10-17 令）：内容从状态栏下开始，进出靠系统返回手势/返回键。
 *
 * **多选**（二轮取证 E21：原版文件夹内页同样支持长按多选）：
 * `SelectionState` 放在本屏、交给 [MediaGrid]（长按进入 / 点按切换 / 选中缩 9% / 橙勾都在网格内部）；
 * 底部胶囊**只在多选态出现**——原版文件夹内容页浏览时没有常驻胶囊，所以不学首页那样挂着。
 *
 * @param onOpen 点某一格：本文件夹分页里的下标 + 缩略图像素边长 + **那一项本身**
 *   （查看器首帧要立刻画出预览，所以要带 id/uri/宽高比）
 */
@Composable
fun FolderGridScreen(
    bucketId: String,
    onOpen: (index: Int, thumbPx: Int, item: MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    /** 从查看器返回时要"居中到刚才看的那张"（9.9 `ScrollResumeInfo`，审计 E15）；null = 不处理 */
    centerOnMediaId: Long? = null,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as AlbumApplication).container }
    val columnPreference = remember(container) { container.gridColumns }

    // 文件夹内容页按名称升序（用户 2026-10-18 令「全部文件都按名称排序」）
    val pagingFlow = remember(container.repository, bucketId) {
        container.repository.pagedMedia(bucketId, sortByName = true)
    }
    val pagingItems = pagingFlow.collectAsLazyPagingItems()

    // 列数放 state 里（同一次停留期间不重复读偏好）；读写都经过 GridColumnPreference，跨重启记住
    val columnsState = remember { mutableIntStateOf(columnPreference.columns) }
    // 回调必须 remember：MediaGrid 用它当 pointerInput 的 key，每重组一个新 lambda 会让手势检测器重启
    val onColumnsChange: (Int) -> Unit = remember {
        { value: Int ->
            val clamped = value.coerceIn(
                GridColumnPreference.MIN_COLUMNS,
                GridColumnPreference.MAX_COLUMNS,
            )
            if (clamped != columnsState.intValue) {
                columnsState.intValue = clamped
                columnPreference.columns = clamped
            }
        }
    }

    // 长按多选：状态放在屏幕层，网格与底部胶囊读同一份（与时间线同一套 `SelectionState`）
    val selection = remember { SelectionState() }
    val shareOrDelete = rememberSelectionActions(selection)
    // 本页没有 ViewModel，删除完成后只能靠 `pagingItems.refresh()` 让分页重新查一遍。
    // `rememberSelectionActions(selection)` 没有 onDone 回调参数（见 `SelectionActions` 签名），
    // 它删除成功时内部只会 `selection.clear()` ⇒ 用"发起过删除 + 选中态落回 false"这两个条件
    // 判断删除流程结束，再刷新。取消删除（系统框点拒绝）会保留选中态，不会误刷。
    var deleteRequested by remember { mutableStateOf(false) }
    LaunchedEffect(selection.active) {
        if (!selection.active && deleteRequested) {
            deleteRequested = false
            pagingItems.refresh()
        }
    }

    val gridState = rememberFractionalGridState()
    // 没有顶栏：内容从状态栏下开始
    val contentTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // 系统返回优先退出多选（9.9 同行为），否则才交给外层导航 pop
    BackHandler(enabled = selection.active) { selection.clear() }

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        val refresh = pagingItems.loadState.refresh
        when {
            // 首次加载：留白，不闪"还没有照片"
            pagingItems.itemCount == 0 && refresh is LoadState.Loading -> Unit

            pagingItems.itemCount == 0 && refresh is LoadState.Error -> FolderLoadFailed(
                onRetry = pagingItems::refresh,
                contentTop = contentTop,
            )

            pagingItems.itemCount == 0 -> EmptyState(modifier = Modifier.padding(top = contentTop))

            else -> MediaGrid(
                pagingItems = pagingItems,
                columns = columnsState.intValue,
                onColumnsChange = onColumnsChange,
                onOpen = onOpen,
                banner = null,
                // 文件夹网格没有 banner：内容从状态栏下开始
                contentPaddingTop = contentTop,
                // 换页的"出/进"由 `PageSwitchState` 统一驱动（9.9 reason 10/13）
                pageId = PageId.FOLDER_GRID,
                state = gridState,
                centerOnMediaId = centerOnMediaId,
                // 多选交给网格：长按进入 / 点按切换 / 选中缩 9% / 橙勾全在 MediaGrid 内部处理
                selection = selection,
            )
        }

        // 底部胶囊：**只在多选态出现**（原版文件夹内容页浏览时没有常驻胶囊）。
        // 刻意不做进出场动画：`BottomCapsule` 自己按 `selection.active` 切换内容，若在淡出期间
        // active 已经变 false，会先闪一下"文件夹/设置"那两个按钮，所以直接挂/卸。
        if (selection.active) {
            BottomCapsule(
                onOpenFolders = {},
                onOpenSettings = {},
                modifier = Modifier.align(Alignment.BottomCenter),
                selection = selection,
                onShare = shareOrDelete.share,
                onDelete = {
                    deleteRequested = true
                    shareOrDelete.delete()
                },
                // 本页不提供"新建文件夹"（那是首页胶囊的功能）⇒ 把「+」整颗隐藏
                showCreateFolder = false,
            )
        }
    }
}
