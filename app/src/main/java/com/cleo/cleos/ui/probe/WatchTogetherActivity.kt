package com.cleo.cleos.ui.probe

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
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
import androidx.compose.runtime.DisposableEffect
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
 *
 * 两个关键点（都踩过）：
 * 1. **本 App 在前台时一帧都不发**：投屏截的是整块屏幕，本页自己也在里面，
 *    发出去 TA 看到的就是这个设置页（以前就是这样）。
 * 2. 截帧/缩放/编码放到后台线程：以前在主线程做，一起看时手机会一顿一顿的。
 */
class WatchTogetherActivity : ComponentActivity() {
    companion object {
        /**
         * 整个 App（不只这一页）是否在前台。
         *
         * 投屏截的是整块屏幕，只要 Cleos 自己在前面，截到的就是 Cleos 自己的界面 ——
         * 发出去 TA 看到的是聊天页/设置页，会说些莫名其妙的话。所以按**进程内
         * 有几个 Activity 处于 resumed** 来判断，而不是只看本页。
         */
        private val resumedActivities = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile
        private var lastForegroundAt = 0L

        private var callbacksInstalled = false

        fun install(application: android.app.Application) {
            if (callbacksInstalled) return
            callbacksInstalled = true
            application.registerActivityLifecycleCallbacks(
                object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: android.app.Activity) {
                        resumedActivities.incrementAndGet()
                        lastForegroundAt = System.currentTimeMillis()
                    }

                    override fun onActivityPaused(activity: android.app.Activity) {
                        resumedActivities.decrementAndGet()
                        lastForegroundAt = System.currentTimeMillis()
                    }

                    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityStarted(activity: android.app.Activity) {}
                    override fun onActivityStopped(activity: android.app.Activity) {}
                    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: android.app.Activity) {}
                },
            )
        }

        /** 本 App 在前台（或刚切走不到 600ms，避免切页那一瞬间把自己截进去）。 */
        fun appInForeground(): Boolean =
            resumedActivities.get() > 0 || System.currentTimeMillis() - lastForegroundAt < 600
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        install(application)
        setContent { MaterialTheme { WatchTogetherScreen() } }
    }
}

private const val FRAME_INTERVAL_MS = 5_000L
private const val MAX_SIDE = 720
private const val TAG = "CleosWatch"

/** 一帧处理完的结果：缩放后的位图（给消息用）和它的 JPEG 字节（只用来判断画面有没有变）。 */
private class Frame(val bmp: Bitmap, val jpeg: ByteArray)

/**
 * 把 ImageReader 的一帧转成缩放后的位图。**必须在后台线程调用**。
 *
 * ImageReader 的 buffer 可能带 rowPadding（rowStride > width * pixelStride），
 * 直接按 width×height 读会斜切、出条纹 —— 这是之前「一堆水平虚线」的原因。
 */
private fun toFrame(image: Image, maxSide: Int): Frame? {
    val plane = image.planes.firstOrNull() ?: return null
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val rowPadding = rowStride - pixelStride * image.width
    val paddedWidth = image.width + rowPadding / pixelStride
    var padded: Bitmap? = null
    var cropped: Bitmap? = null
    var scaled: Bitmap? = null
    try {
        padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        val longest = maxOf(cropped.width, cropped.height)
        scaled = if (longest > maxSide) {
            val k = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * k).toInt().coerceAtLeast(1),
                (cropped.height * k).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            cropped
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Frame(scaled, out.toByteArray())
    } catch (e: Throwable) {
        Log.d(TAG, "frame failed: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
        return null
    } finally {
        padded?.recycle()
        // scaled === cropped 时是同一个对象，只能留一个给别人用，不能在这里回收。
        if (cropped !== scaled) cropped?.recycle()
    }
}

@Composable
private fun WatchTogetherScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as CleosApp
    val scope = rememberCoroutineScope()
    val c = app.container

    var running by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("未开始") }
    var lastSentText by remember { mutableStateOf("") }

    var projection by remember { mutableStateOf<MediaProjection?>(null) }
    var reader by remember { mutableStateOf<ImageReader?>(null) }
    var display by remember { mutableStateOf<VirtualDisplay?>(null) }
    var worker by remember { mutableStateOf<HandlerThread?>(null) }
    var conversationId by remember { mutableStateOf<Long?>(null) }
    var lastSentAt by remember { mutableStateOf(0L) }
    var lastSignature by remember { mutableStateOf("") }

    fun stopAll(why: String) {
        val wasRunning = running
        running = false
        status = why
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        MediaProjectionService.projection = null
        worker?.quitSafely(); worker = null
        // 前台服务不关掉，通知栏会一直挂着一条「投屏中」。
        if (wasRunning) {
            runCatching {
                context.stopService(android.content.Intent(context, MediaProjectionService::class.java))
            }
        }
    }

    fun startCapture(mp: MediaProjection) {
        projection = mp
        // currentWindowMetrics 是 API 30 的；这台机上有 Android 10（API 29），直接用 displayMetrics。
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val dpi = context.resources.displayMetrics.densityDpi
        val r = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        reader = r
        val thread = HandlerThread("cleos-watch-frames").also { it.start() }
        worker = thread
        val handler = Handler(thread.looper)
        r.setOnImageAvailableListener({ ir ->
            val image = try {
                ir.acquireLatestImage()
            } catch (e: Throwable) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastSentAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                // Cleos 自己在前面：截到的就是 Cleos 的界面，发出去没意义，跳过。
                if (WatchTogetherActivity.appInForeground()) return@setOnImageAvailableListener
                val frame = toFrame(image, MAX_SIDE) ?: return@setOnImageAvailableListener
                val signature = frame.jpeg.contentHashCode().toString()
                if (signature == lastSignature) {
                    frame.bmp.recycle()
                    return@setOnImageAvailableListener
                }
                lastSignature = signature
                lastSentAt = now
                val w0 = frame.bmp.width
                val h0 = frame.bmp.height
                Log.d(TAG, "frame " + w0 + "x" + h0 + " " + frame.jpeg.size + "B")
                scope.launch {
                    var ok = false
                    try {
                        val id = conversationId
                        if (id != null) {
                            val stored = c.images.save(frame.bmp, "watch-")
                            ok = c.chat.send(
                                id,
                                "（一起看）这是我手机屏幕的截图，我正在看这个。别猜那是什么设备的输出，就按你看到的画面随口说说。",
                                pictures = listOf(MessageImage(stored, w0, h0)),
                            )
                        }
                    } catch (e: Throwable) {
                        status = "发送失败：" + e.javaClass.simpleName + "：" + (e.message ?: "")
                    } finally {
                        frame.bmp.recycle()
                    }
                    if (ok) {
                        sent += 1
                        lastSentText = "已发 " + sent + " 帧（" + w0 + "×" + h0 + "）"
                        Log.d(TAG, "sent=$sent")
                    }
                }
            } catch (e: Throwable) {
                status = "截帧失败：" + e.javaClass.simpleName + "：" + (e.message ?: "")
            } finally {
                image.close()
            }
        }, handler)
        display = mp.createVirtualDisplay(
            "cleos-watch", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null,
        )
        running = true
        status = "已开始。退回桌面或用别的 App，画面有变化才会发（本页在前台时暂停）"
        // 把自己藏到后台：不然截到的都是这个页面，看不到你真正在刷的东西。
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { (context as? android.app.Activity)?.moveTaskToBack(true) }
        }, 1200)
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
                // 自己点「停止」时 running 已经是 false，别把状态改成「被系统停止了」。
                override fun onStop() { if (running) stopAll("投屏被系统停止了") }
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

    // 退出这个页面就停：投屏不能跟着人走。
    DisposableEffect(Unit) {
        onDispose { stopAll("已停止（离开页面）") }
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
        Text("状态：" + status, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (sent > 0) Text(lastSentText, fontSize = 12.sp)
        Text(
            "说明：这是验证版集成，帧图会作为一条聊天消息发给当前 TA（会出现在聊天记录里，消耗 token）。" +
                "本页面在前台时不会发帧——先退到桌面/别的 App，TA 才能看到你真正在看的东西。",
            fontSize = 11.sp,
        )
    }
}
