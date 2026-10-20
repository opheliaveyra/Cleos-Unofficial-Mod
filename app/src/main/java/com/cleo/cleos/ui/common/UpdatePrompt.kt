package com.cleo.cleos.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.AppContainer
import com.cleo.cleos.InstallStep
import com.cleo.cleos.glass.*
import kotlinx.coroutines.launch

/**
 * 有新版本时的弹窗：**用户点了才算数**。
 *
 * 三个出口都留着：现在装（先下载、校验通过才给安装按钮）、稍后、跳过这个版本。
 * 自动检查只负责把它叫出来，不会自己下载或安装。
 */
@Composable
fun UpdatePrompt(c: AppContainer) {
    val info by c.updates.available.collectAsStateWithLifecycle()
    val update = info ?: return
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val install by c.updates.install.collectAsStateWithLifecycle()
    var busy by remember(update.version) { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (!busy) c.updates.dismiss() }) {
        GlassSurface(modifier = Modifier.fillMaxWidth(), shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("发现新版本 " + update.version, color = palette.content, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    update.notes.ifBlank { "有新版本可用。" },
                    color = palette.contentSecondary, fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                )

                when (val s = install) {
                    is InstallStep.Downloading -> {
                        val pct = if (s.total > 0) (s.done * 100 / s.total) else 0
                        Text("正在下载 " + pct + "%", color = palette.content, fontSize = 14.sp)
                    }
                    InstallStep.Verifying -> Text("正在校验安装包…", color = palette.content, fontSize = 14.sp)
                    InstallStep.Ready -> {
                        Text("下载好了，校验通过。", color = palette.content, fontSize = 14.sp)
                        GlassButton(onClick = { c.updates.install() }, backdrop = LocalWallpaperBackdrop.current, modifier = Modifier.fillMaxWidth()) {
                            Text("安装", color = palette.content)
                        }
                    }
                    is InstallStep.Failed -> Text(s.reason, color = palette.error, fontSize = 12.sp)
                    InstallStep.Idle -> {
                        if (update.apkUrl == null) {
                            Text("这个版本没有附带安装包，只能去 Release 页面看看。", color = palette.contentSecondary, fontSize = 12.sp)
                        } else {
                            Text(
                                "安装包 " + String.format("%.1f", update.apkSize / 1024.0 / 1024.0) + " MB" +
                                    if (update.sha256 != null) " · 带校验值" else "",
                                color = palette.contentSecondary, fontSize = 12.sp,
                            )
                        }
                    }
                }

                if (update.apkUrl != null && install is InstallStep.Idle) {
                    GlassButton(onClick = {
                        busy = true
                        scope.launch {
                            try { c.updates.downloadAndVerify(update) } finally { busy = false }
                        }
                    }, backdrop = LocalWallpaperBackdrop.current, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "正在下载…" else "下载更新", color = palette.content)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassButton({ c.updates.dismiss() }, LocalWallpaperBackdrop.current) { Text("稍后") }
                    GlassButton({ c.updates.dismiss(skip = true) }, LocalWallpaperBackdrop.current) { Text("跳过此版本") }
                }
                GlassButton({ c.updates.openPage(update.pageUrl) }, LocalWallpaperBackdrop.current, modifier = Modifier.fillMaxWidth()) {
                    Text("去 Release 页面看", color = palette.content)
                }
            }
        }
    }
}
