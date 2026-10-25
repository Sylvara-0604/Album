package com.example.album.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 选中徽章：9.9 `icn_selected` 的等价实现 —— **左上角** 1.2×1.2 的橙色圆 + 深色勾
 * （`#FF9800` 圆 + `#211300` 勾，取色自 9.9 资源）。
 *
 * 出现/消失是 spring（9.9 `GridSpringAnimation`：k=100 / friction=10 ⇒ ζ≈0.5），
 * 缩放与透明度都在 draw 阶段读，不重组网格。
 *
 * 选择模式的操作条**不在顶部**（用户 2026-10-17 令）：改为底部胶囊横向拉长，
 * 见 [BottomCapsule]。
 */
@Composable
fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
    )
    if (progress <= 0.01f) return
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = progress
                scaleY = progress
                alpha = progress.coerceIn(0f, 1f)
            }
            .size(22.dp)
            .background(color = Color(0xFFFF9800), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0.5f) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xFF211300),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
