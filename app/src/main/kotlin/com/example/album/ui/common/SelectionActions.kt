package com.example.album.ui.common

import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.album.AlbumApplication
import com.example.album.R
import kotlinx.coroutines.launch

/** 选择模式的两个操作：分享（系统选择器）与删除（MediaStore 的官方删除请求，走系统确认框）。 */
class SelectionActions(val share: () -> Unit, val delete: () -> Unit)

/**
 * 分享 / 删除的落地实现（官方 API，不自己删文件）：
 * - 分享 = `Intent.ACTION_SEND_MULTIPLE` + `EXTRA_STREAM`（只读授权）；
 * - 删除 = `MediaStore.createDeleteRequest` ⇒ 系统弹确认框，用户点「允许」才真的删。
 *
 * ⚠️ URI 一律经 `MediaStoreSource.mediaUris()` 按**卷 + 类型**拼：
 * 旧实现用 `Files.getContentUri(VOLUME_EXTERNAL, id)`（合并卷）——`_ID` 只在卷内唯一，
 * 多卷设备（SD 卡）上会指向错行（2026-10-24 新建文件夹调研时发现，同批修掉）。
 */
@Composable
fun rememberSelectionActions(selection: SelectionState): SelectionActions {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as AlbumApplication).container.repository }
    val scope = rememberCoroutineScope()
    val failureHint = stringResource(R.string.selection_delete_failed)
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            selection.clear()
        } else {
            Toast.makeText(context, failureHint, Toast.LENGTH_SHORT).show()
        }
    }
    return remember(selection, deleteLauncher, repository) {
        SelectionActions(
            share = {
                val ids = selection.selectedIds()
                if (ids.isNotEmpty()) {
                    scope.launch {
                        val uris = repository.mediaUris(ids)
                        if (uris.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                }
            },
            delete = {
                val ids = selection.selectedIds()
                if (ids.isNotEmpty()) {
                    scope.launch {
                        val uris = repository.mediaUris(ids)
                        if (uris.isNotEmpty()) {
                            val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                            deleteLauncher.launch(
                                IntentSenderRequest.Builder(request.intentSender).build(),
                            )
                        }
                    }
                }
            },
        )
    }
}
