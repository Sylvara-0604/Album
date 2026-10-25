package com.example.album.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.example.album.R

/** 文件夹名里不允许的字符（MediaStore `RELATIVE_PATH` 的目录名不能带这些） */
private val INVALID_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * 「新建文件夹」命名对话框（用户 2026-10-24 令）：
 * 多选态胶囊里的 "+" → 弹这个框 → 确认后把选中的媒体移进 `/sdcard/Pictures/<名字>`。
 *
 * 只做**本地校验**：空的、含非法字符的、`.`/`..` 一律不允许确认；
 * 重名不在这里拦（MediaProvider 会把移动合并进已有目录，用户自己看得见结果）。
 */
@Composable
fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() &&
        trimmed.none { it in INVALID_CHARS } &&
        trimmed != "." &&
        trimmed != ".."

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_new_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = trimmed.isNotEmpty() && !valid,
                label = { Text(stringResource(R.string.folder_new_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(trimmed) }, enabled = valid) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
