package com.cleo.cleos.ui.probe

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.CleosApp
import com.cleo.cleos.data.MessageImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 「一起看」：把**主屏画面**定时截给 TA，让 TA 陪着你刷视频。
 *
 * 不是让 TA 自己在虚拟屏里刷——是你刷，TA 看同一块屏幕并开口。
 * 需要 MediaProjection 授权（系统弹窗点一次），不需要 Shizuku / root。
 * 每 5 秒最多发一帧，画面没换（字节相同）就跳过，省 token。
 */
class WatchTogetherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchTogetherScreen() } }
    }
}

private const val FRAME_INTERVAL_MS = 5_000L
private const val MAX_WIDTH = 720

@Composable
private fun WatchTogetherScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as CleosApp
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("未开始") }
    val c = app.container

    var projection by remember { mutableStateOf<MediaProjection?>(null) }
    var reader by remember { mutableStateOf<ImageReader?>(null) }
    var display by remember { mutableStateOf<VirtualDisplay?>(null) }
    var conversationId by remember { mutableStateOf<Long?>(null) }
    var lastSentAt by remember { mutableStateOf(0L) }
    var lastSignature by remember { mutableStateOf("") }
    var lastSentText by remember { mutableStateOf("") }

    fun stopAll(why: String) {
        running = false
        status = why
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
    }

    fun startCapture(mp: MediaProjection) {
        projection = mp
        val wm = context.getSystemService(WindowManager::class.java)
        val w = wm.currentWindowMetrics.bounds.width()
        val h = wm.currentWindowMetrics.bounds.height()
        val dpi = context.resources.displayMetrics.densityDpi
        val r = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastSentAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                // ImageReader 的 buffer 可能带 rowPadding：直接用 width×height 的 bitmap 会斜切/出条纹。
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * image.width
                val padded = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888,
                )
                padded.copyPixelsFromBuffer(plane.buffer)
                val bmp = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                if (padded !== bmp) padded.recycle()
                val scaled = Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * MAX_WIDTH / bmp.height).coerceAtLeast(1),
                    MAX_WIDTH,
                    true,
                )
                bmp.recycle()
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
                val jpeg = out.toByteArray()
                bmp.recycle()
                val signature = jpeg.contentHashCode().toString()
                if (signature == lastSignature) { scaled.recycle(); return@setOnImageAvailableListener }
                lastSignature = signature
                lastSentAt = now
                scope.launch {
                    val id = conversationId ?: return@launch
                    val stored = c.images.save(scaled, "watch-")
                    val ok = c.chat.send(
                        id,
                        "（一起看）这是我手机屏幕的截图，我正在看这个。别猜那是什么设备的输出，就按你看到的画面随口说说。",
                        pictures = listOf(MessageImage(stored, scaled.width, scaled.height)),
                    )
                    scaled.recycle()
                    if (ok) { sent += 1; lastSentText = "已发 $sent 帧" }
                }
            } catch (e: Throwable) {
                status = "截帧失败：${e.javaClass.simpleName}：${e.message ?: ""}"
            } finally {
                image.close()
            }
        }, Handler(Looper.getMainLooper()))
        display = mp.createVirtualDisplay(
            "cleos-watch", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null,
        )
        running = true
        status = "正在一起看（每 5 秒最多一帧，画面没换就跳过）"
        // 把自己藏到后台：不然截到的都是这个页面，看不到你真正在刷的东西。
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { (context as? android.app.Activity)?.moveTaskToBack(true) }
        }, 1500)
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            status = "投屏授权被取消"; return@rememberLauncherForActivityResult
        }
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        androidx.core.content.ContextCompat.startForegroundService(
            context, android.content.Intent(context, MediaProjectionService::class.java),
        )
        Handler(Looper.getMainLooper()).postDelayed({
            val mp = runCatching { mpm.getMediaProjection(result.resultCode, data) }.getOrNull()
            if (mp == null) { status = "拿到投屏 token 失败，再点一次"; return@postDelayed }
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopAll("投屏被系统停止了") }
            }, Handler(Looper.getMainLooper()))
            MediaProjectionService.projection = mp
            scope.launch {
                val ta = c.companions.current.first()
                val remembered = c.settings.currentConversation.first()
                conversationId = c.chat.resolveConversation(remembered, ta.id)
                startCapture(mp)
            }
        }, 1200)
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("一起看", fontSize = 20.sp)
        Text(
            "你刷视频的时候，让 TA 看同一块屏幕并开口。每 5 秒最多一帧，画面没变就跳过；不需要 root / Shizuku。",
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                launcher.launch(mpm.createScreenCaptureIntent())
            }, enabled = !running) { Text("开始一起看") }
            OutlinedButton(onClick = { stopAll("已停止") }, enabled = running) { Text("停止") }
        }
        Text("状态：$status", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (sent > 0) Text(lastSentText, fontSize = 12.sp)
        Text(
            "说明：这是验证版集成，帧图会作为一条聊天消息发给当前 TA（会出现在聊天记录里，消耗 token）。",
            fontSize = 11.sp,
        )
    }
}