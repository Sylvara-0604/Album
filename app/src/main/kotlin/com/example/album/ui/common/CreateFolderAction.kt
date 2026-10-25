package com.example.album.ui.common

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.album.AlbumApplication
import com.example.album.R
import com.example.album.ui.components.CreateFolderDialog
import kotlinx.coroutines.launch

/** 「新建文件夹」的入口（多选态胶囊里的 "+" 调它）。 */
class CreateFolderAction(val start: () -> Unit)

/**
 * 多选 → 新建文件夹 → 把选中的媒体移进 `/sdcard/Pictures/<名字>`（用户 2026-10-24 令）。
 *
 * 全程**不新增任何权限**（方案与取证见 `docs/research/CREATE_FOLDER_PLAN.md`）：
 * 1. `MediaStore.createWriteRequest(resolver, uris)` ⇒ 系统弹一次确认框（授权绑定当前 Activity 生命周期）；
 * 2. 回调里 `resolver.update(uri, RELATIVE_PATH="Pictures/<名字>/")` 逐条移动（磁盘 rename，id/日期都不变）；
 * 3. 目录由 MediaProvider 隐式创建；失败（跨卷 / 被拒）静默跳过并在必要时提示条数。
 *
 * @param onMoved 移动完成（成功条数 > 0）后回调：调用方刷新分页 / 退出多选
 */
@Composable
fun rememberCreateFolderAction(
    selection: SelectionState,
    onMoved: () -> Unit,
): CreateFolderAction {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as AlbumApplication).container.repository }
    val scope = rememberCoroutineScope()

    var dialogVisible by remember { mutableStateOf(false) }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingName by remember { mutableStateOf("") }

    val failedText = stringResource(R.string.folder_new_failed)
    val partialText = stringResource(R.string.folder_new_partial)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = pendingUris
            val name = pendingName
            pendingUris = emptyList()
            pendingName = ""
            if (uris.isNotEmpty()) {
                scope.launch {
                    val moved = repository.moveToPictures(uris, name)
                    when {
                        moved > 0 -> {
                            selection.clear()
                            onMoved()
                            if (moved < uris.size) {
                                Toast.makeText(context, partialText, Toast.LENGTH_SHORT).show()
                            }
                        }

                        else -> Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            pendingUris = emptyList()
            pendingName = ""
            Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
        }
    }

    if (dialogVisible) {
        CreateFolderDialog(
            onDismiss = { dialogVisible = false },
            onConfirm = { name ->
                dialogVisible = false
                val ids = selection.selectedIds()
                if (ids.isEmpty()) return@CreateFolderDialog
                scope.launch {
                    val uris = repository.mediaUris(ids)
                    if (uris.isEmpty()) {
                        Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    // targetSdk ≥ 36 时单批上限 2000 条（超了 createWriteRequest 抛 IllegalArgumentException）
                    val batch = uris.take(MAX_WRITE_REQUEST)
                    pendingUris = batch
                    pendingName = name
                    val request = try {
                        repository.writeRequest(batch)
                    } catch (_: IllegalArgumentException) {
                        pendingUris = emptyList()
                        pendingName = ""
                        Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    launcher.launch(IntentSenderRequest.Builder(request).build())
                }
            },
        )
    }

    return remember(selection) {
        CreateFolderAction(start = { dialogVisible = true })
    }
}

/** `MediaStore` 在 targetSdk ≥ 36 时单批写入请求的上限（官方在文档里给的值）。 */
private const val MAX_WRITE_REQUEST = 2000
