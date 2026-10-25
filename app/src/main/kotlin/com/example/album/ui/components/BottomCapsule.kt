package com.example.album.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.album.R
import com.example.album.ui.common.SelectionState

/**
 * 首页底部的浮动胶囊。
 *
 * ## 外观＝照抄 Launcher3 微件面板底部那颗药丸（用户 2026-10-24 令）
 *
 * 真值来源：`_ref_launcher3/.../modules/widgetpicker/src/com/android/launcher3/widgetpicker/ui/components/
 * ScrollableFloatingToolbar.kt`（LineageOS 24.0 的微件选择器**本身就是 Compose 写的**，可 1:1 搬）。
 * 逐项对照我们旧版（M3 `HorizontalFloatingToolbar` + 1dp 描边）：
 *
 * | 项 | Launcher3 | 旧版 | 现在 |
 * | --- | --- | --- | --- |
 * | 外底 | M3 `Surface` + `surfaceBright`（浅色近纯白） | `surfaceContainer`（灰） | `surfaceBright` |
 * | 描边 | **没有** | 1dp `outlineVariant` | **删除** |
 * | 阴影 | `shadowElevation = 3.dp`（Surface 原生） | 3dp | 3dp |
 * | tonalElevation | 不设（0dp），底色不被 tone 污染 | 默认 | 0dp |
 * | 形状 | `CircleShape`（半径恒 = 高/2，**不是写死 32dp**） | CircleShape | CircleShape |
 * | 高度 | 64dp = tab 行 **48dp** + 上下各 **8dp** | 64dp（组件内部算） | 64dp（同样 48 + 8×2） |
 * | 左右内边距 | `edgePadding = 8.dp` | 组件内部 8dp | 8dp |
 * | 选中层 | 选中 tab 内再叠一层 **`secondaryContainer` 药丸**（48dp 高、左右 16dp） | 无 | 多选态的"已选 N 项"用它 |
 *
 * ⇒ 旧版"不好看"的三个来源：底色偏灰、外面套了一圈灰描边、没有内层色阶。现在三条都按 Launcher3 改。
 *
 * ## 宽度变化（用户 2026-10-24 报「D1 无改善：先向外扩大一圈再收起」）
 *
 * 两条硬约束：
 * 1. **出场内容必须 0ms 消失**：`AnimatedContent` 的尺寸目标 = max(出场内容, 入场内容)
 *    （`AnimatedContentMeasurePolicy.measure` 只跳过 `isPendingTarget`），出场那套宽内容若还在，
 *    容器就被它顶住 ⇒ 弹簧会先把"展开"走完再收缩。
 * 2. **尺寸弹簧要临界阻尼**（ζ=1）：官方 `FastSpatial` 是 ζ=0.6，宽 116dp→约 400dp 会**过冲 9.5%**
 *    （两边各多出约 13dp）——那正是"先向外扩大一圈"的观感来源。这里只保留弹簧的加速感、不保留过冲。
 */
private val CapsuleTabHeight = 48.dp

/** Launcher3 `verticalPadding`：上下各 8dp ⇒ 总高 48 + 16 = 64dp */
private val CapsuleVerticalPadding = 8.dp

/** Launcher3 `edgePadding`：左右各 8dp */
private val CapsuleHorizontalPadding = 8.dp

/** Launcher3 那颗药丸的投影（Surface 原生 shadow，不做自定义 ambient/spot 色） */
private val CapsuleShadow = 3.dp

/** 药丸离导航栏的距离（= `FloatingToolbarDefaults.ScreenOffset` 的 16dp）。 */
private val BottomGap = 16.dp

/** 图标按钮边长：Material 最小触控目标 48dp。 */
private val ActionSize = 48.dp

/** 图标边长（Launcher3 用 material3 `Icon` 默认 24dp）。 */
private val IconSize = 24.dp

/**
 * 首页底部的浮动胶囊。
 *
 * 内容：
 * - 平时：文件夹 / 设置两个入口（没有文字标签）；
 * - 多选态：× / 已选 N 项（内层 `secondaryContainer` 药丸）/ 分享 / **新建文件夹（+）** / 删除。
 */
@Composable
fun BottomCapsule(
    onOpenFolders: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    selection: SelectionState? = null,
    onShare: () -> Unit = {},
    onDelete: () -> Unit = {},
    onCreateFolder: () -> Unit = {},
    /** 多选态是否显示「+ 新建文件夹」：首页显示；文件夹内容页没有这个功能（审计 E21） */
    showCreateFolder: Boolean = true,
) {
    // 手势导航/三键导航都要避开：药丸浮在导航栏之上
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val activeSelection = selection?.takeIf { it.active }
    val motion = MaterialTheme.motionScheme

    Surface(
        modifier = modifier.padding(bottom = bottomInset + BottomGap),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceBright,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Launcher3：只有 M3 Surface 的原生 shadow，3dp；**没有描边**。
        shadowElevation = CapsuleShadow,
        // Launcher3 不设 tonalElevation（否则 surfaceBright 会被 tone 染灰）
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = CapsuleHorizontalPadding,
                vertical = CapsuleVerticalPadding,
            ),
        ) {
            AnimatedContent(
                targetState = activeSelection != null,
                transitionSpec = {
                    // 出场 0ms（见类注释第 1 条）；进场用官方 effects 规格
                    (fadeIn(animationSpec = motion.fastEffectsSpec<Float>()) togetherWith
                        fadeOut(animationSpec = tween(0))).using(
                        // 尺寸：临界阻尼弹簧 —— 只有横向变化（高度由 48dp 行定死），不过冲
                        SizeTransform(clip = true) { _, _ ->
                            spring(dampingRatio = 1f, stiffness = 800f)
                        },
                    )
                },
                contentAlignment = Alignment.Center,
                label = "capsule",
            ) { selecting ->
                if (selecting && activeSelection != null) {
                    SelectionCapsuleContent(
                        count = activeSelection.count,
                        onClose = activeSelection::clear,
                        onShare = onShare,
                        onCreateFolder = onCreateFolder,
                        onDelete = onDelete,
                        showCreateFolder = showCreateFolder,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CapsuleAction(
                            icon = Icons.Filled.Folder,
                            label = stringResource(R.string.capsule_folders),
                            onClick = onOpenFolders,
                        )
                        CapsuleAction(
                            icon = Icons.Filled.Settings,
                            label = stringResource(R.string.capsule_settings),
                            onClick = onOpenSettings,
                        )
                    }
                }
            }
        }
    }
}

/** 多选态的药丸内容：× / 已选 N 项（内层色药丸）/ 分享 / 新建文件夹 / 删除。 */
@Composable
private fun SelectionCapsuleContent(
    count: Int,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onCreateFolder: () -> Unit,
    onDelete: () -> Unit,
    showCreateFolder: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsuleAction(
            icon = Icons.Filled.Close,
            label = stringResource(R.string.capsule_exit_selection),
            onClick = onClose,
        )
        // 9.9 也是"左 ×、中间计数、右侧操作"的次序。
        // 这一块照 Launcher3「选中 tab」的写法：内层再叠一颗 `secondaryContainer` 药丸
        // （48dp 高 = 内层满高、左右 16dp），整颗胶囊因此有"外底 + 选中"两级色阶。
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(
                modifier = Modifier
                    .height(CapsuleTabHeight)
                    .widthIn(min = CapsuleTabHeight)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.selection_count, count, count),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
        CapsuleAction(
            icon = Icons.Filled.Share,
            label = stringResource(R.string.selection_share),
            onClick = onShare,
        )
        if (showCreateFolder) {
            CapsuleAction(
                icon = Icons.Filled.CreateNewFolder,
                label = stringResource(R.string.selection_new_folder),
                onClick = onCreateFolder,
            )
        }
        CapsuleAction(
            icon = Icons.Filled.Delete,
            label = stringResource(R.string.selection_delete),
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CapsuleAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(ActionSize)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(IconSize),
        )
    }
}
