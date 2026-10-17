package com.cleo.cleos.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import java.io.File

/**
 * 拍照的状态：**拍完只是"待预览"，不确认就不进聊天**。
 *
 * 单聊和群聊共用这一个（和 VoiceHold 一样，抽出来免得又只加在一边）。
 */
@Stable
internal class PhotoCapture {
    /** 刚拍下、还没确认的那张。界面看到它就弹预览。 */
    var pending by mutableStateOf<File?>(null)
    /** 正在等相机返回（这段时间可以显示"正在打开相机"）。 */
    var waiting by mutableStateOf(false)
    /** 上一次没拍成的原因（没权限、没相机、相机没回图）。 */
    var problem by mutableStateOf<String?>(null)
    internal var shoot: () -> Unit = {}

    fun take() = shoot()

    /** 这张不要了（重拍 / 取消）：临时文件一起删。 */
    fun discard() {
        pending?.delete()
        pending = null
        problem = null
    }
}

/**
 * 调系统相机拍一张。
 *
 * 临时文件放 cacheDir/camera/，用 FileProvider 交给相机 app —— Android 10 起不能再传 file://。
 * **方向与压缩不在这里做**：确认后交给 `ImageStore.import`（ImageDecoder 按 EXIF 转正、缩到 2048、压成 JPEG）。
 */
@Composable
internal fun rememberPhotoCapture(): PhotoCapture {
    val context = LocalContext.current
    val state = remember { PhotoCapture() }
    // 相机 app 要往这个文件里写，所以它得活到结果回来为止（不能提前删）。
    val target = remember { arrayOfNulls<File>(1) }
    // 自己发 Intent 而不是用 TakePicture 契约：**要显式带上 URI 授权 flag**。
    // 契约版只 putExtra(EXTRA_OUTPUT)，部分相机 app 拿不到写权限，结果是 RESULT_OK 但文件是空的。
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        state.waiting = false
        val file = target[0]
        target[0] = null
        val ok = result.resultCode == android.app.Activity.RESULT_OK
        if (ok && file != null && file.length() > 0) {
            state.pending = file
        } else {
            // 取消拍照 / 相机没给图：清掉临时文件，聊天和草稿都原样留着。
            file?.delete()
            state.pending = null
        }
    }
    fun launch() {
        state.problem = null
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        target[0] = file
        state.waiting = true
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            takePicture.launch(intent)
        } catch (e: Exception) {
            // 没有相机应用、或者系统不让起：给一句人话，界面那边还有相册可选。
            state.waiting = false
            target[0] = null
            file.delete()
            state.problem = "这台设备调不起相机（没有相机应用，或者被系统限制了链接）—— 可以从相册选一张"
        }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch() else state.problem = "需要相机权限才能拍照 —— 也可以从相册选一张"
    }
    state.shoot = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) launch() else askCamera.launch(Manifest.permission.CAMERA)
    }
    return state
}

/**
 * 拍完的预览：**看一眼再决定**。
 *
 * 重拍 = 丢掉这一张再开相机；取消 = 什么都不发，输入框里的字一个不动。
 */
@Composable
internal fun PhotoPreviewDialog(
    file: File,
    problem: String?,
    busy: Boolean,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    Dialog(onDismissRequest = onCancel) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            style = palette.card,
            shape = GlassShape.Rounded(26.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这张可以吗？", color = palette.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                AsyncImage(
                    model = file,
                    contentDescription = "刚拍的照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(18.dp)),
                )
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp, lineHeight = 17.sp) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = onRetake, enabled = !busy) { Text("重拍") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
                    TextButton(onClick = onConfirm, enabled = !busy) { Text(if (busy) "正在添加…" else "用这张") }
                }
            }
        }
    }
}
