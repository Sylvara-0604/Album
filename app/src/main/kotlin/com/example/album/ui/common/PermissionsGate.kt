package com.example.album.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.album.R

private val mediaPermissions = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
)

private enum class Access { Full, Partial, Denied }

/**
 * 权限门。
 *
 * - Full：两项权限都给 ⇒ 正常显示。
 * - Partial：Android 14 的"仅选中的照片"⇒ 正常显示，但顶栏给「选择更多」入口（onSelectMore 非空）。
 * - Denied：只显示申请页。系统在连续拒绝后不再弹窗，所以必须同时给「打开设置」这条出路，否则是死胡同。
 */
@Composable
fun PermissionsGate(content: @Composable (onSelectMore: (() -> Unit)?) -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(context.mediaAccess()) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        access = context.mediaAccess()
    }

    // 从系统设置页返回时要重新判定（用户可能在设置里刚给了权限）
    LifecycleResumeEffect(Unit) {
        access = context.mediaAccess()
        onPauseOrDispose { }
    }

    when (access) {
        Access.Full -> content(null)
        Access.Partial -> content { request.launch(mediaPermissions) }
        Access.Denied -> PermissionRequest(
            onRequest = { request.launch(mediaPermissions) },
            onOpenSettings = { context.openAppSettings() },
        )
    }
}

private fun Context.mediaAccess(): Access {
    val images = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    val video = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    if (images && video) return Access.Full

    val userSelected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    return if (images || video || userSelected) Access.Partial else Access.Denied
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.permission_body),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRequest,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(stringResource(R.string.permission_grant))
        }
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.action_open_settings))
        }
    }
}
