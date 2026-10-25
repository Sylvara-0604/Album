package com.example.album.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.album.R
import com.example.album.data.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 缺失值的占位符。破折号不是"词"，中英一致，所以不做成 string 资源，
 * 免得 md/strings.xml 里多三份一样的条目。
 */
private const val UNKNOWN_VALUE = "—"

/** 1KB / 1MB 的字节数（二进制单位：MediaStore 的 `SIZE` 就是字节） */
private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = BYTES_PER_KB * 1024.0

/**
 * 详情面板（蓝图 §5：「详情」= 底部 `ModalBottomSheet`，**只读四项**）。
 *
 * 四项全部直接来自 `MediaItem`（即 `MediaStore` 列），不新开权限、不解码文件：
 * 尺寸、拍摄时间、文件大小、路径。**清除 EXIF 已在 v5.4 撤销**，这里没有、也不该有"写"的入口。
 *
 * 可长按/选中复制：正文包在 `SelectionContainer` 里（路径尤其需要，用户可能要看完整文件名）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(item: MediaItem, onDismiss: () -> Unit) {
    // material3 1.5.0-beta01 里 `rememberModalBottomSheetState` 已弃用（工程 warningsAsErrors ⇒ 直接编译失败），
    // 官方给的新入口是 `rememberBottomSheetState`：显式给 Hidden 起点，
    // ModalBottomSheet 自己的 LaunchedEffect 会 show() 上来 —— 行为与旧版一致。
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    val sizeText = if (item.widthPx <= 0 || item.heightPx <= 0) {
        UNKNOWN_VALUE
    } else {
        "${item.widthPx} × ${item.heightPx}"
    }

    // 日期格式放到 strings.xml（工程既有约定：日期只写模式，locale 由 DateTimeFormatter 传）。
    // 时区用系统时区：拍摄时间在 MediaStore 里是 UTC 毫秒，用户要看的是"本地几点拍的"。
    val datePattern = stringResource(R.string.viewer_details_date_pattern)
    val dateText = remember(datePattern, item.dateTakenMillis) {
        formatDate(item.dateTakenMillis, datePattern)
    }

    val fileSizeText = fileSizeText(item.sizeBytes)

    // 路径：这里是只读展示，用 `displayName` 足够（完整绝对路径不在 MediaStore 里，硬拼反而会错）
    val pathText = item.displayName.ifBlank { UNKNOWN_VALUE }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // 用户 2026-10-24：「详情面板顶部有手势条却还有"X"那一行，没必要」⇒
        // 整行（标题 + 关闭按钮）删掉。面板自带拖拽把手 + 下滑/返回键都能关，
        // 再放一行 X 既重复又占高度。
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SelectionContainer {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    DetailRow(stringResource(R.string.viewer_details_size), sizeText)
                    DetailRow(stringResource(R.string.viewer_details_taken), dateText)
                    DetailRow(stringResource(R.string.viewer_details_file_size), fileSizeText)
                    DetailRow(stringResource(R.string.viewer_details_path), pathText)
                }
            }
        }
    }
}

/** 一行详情：左标签（次要色）+ 右值（主色，右对齐；长路径换行而不是被截断）。 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 文件大小：B/KB/MB 自适应（蓝图只到 MB）。
 * 字节数是整数，"1 位小数"只对 KB/MB 有意义 —— 给字节加 `.0` 是假精度。
 */
@Composable
private fun fileSizeText(bytes: Long): String {
    if (bytes <= 0L) return UNKNOWN_VALUE
    val value = bytes.toDouble()
    return when {
        value < BYTES_PER_KB -> stringResource(R.string.viewer_details_size_b, bytes.toString())
        value < BYTES_PER_MB -> stringResource(R.string.viewer_details_size_kb, oneDecimal(value / BYTES_PER_KB))
        else -> stringResource(R.string.viewer_details_size_mb, oneDecimal(value / BYTES_PER_MB))
    }
}

/** 默认 locale 的小数点（中文/英文都是 `.`，其它语言可能是 `,`，跟着系统走） */
private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

/** `yyyy-MM-dd HH:mm`（模式来自 strings.xml）；无拍摄时间（0）时显示占位符。 */
private fun formatDate(millis: Long, pattern: String): String {
    if (millis <= 0L) return UNKNOWN_VALUE
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}
