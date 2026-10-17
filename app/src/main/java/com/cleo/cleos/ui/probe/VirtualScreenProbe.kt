package com.cleo.cleos.ui.probe

import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.max

/**
 * 虚拟屏可行性探针：**只用来验证，不参与聊天**。
 *
 * 它把"角色在虚拟屏里刷抖音"这条链拆成可单独观察的几步：
 * 创建虚拟屏 → 把抖音启动到该屏 → 读一帧画面 → 往该屏注入滑动 → 释放。
 * 每一步都显示成功或**具体错误**（SecurityException 就是 SecurityException），
 * **不会退回主屏操作**。复制诊断可以把这一整页结果带走。
 */
class VirtualScreenProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ProbeScreen() } }
    }
}

private const val DOUYIN = "com.ss.android.ugc.aweme"
private const val SHIZUKU = "moe.shizuku.privileged.api"
private const val W = 720
private const val H = 1280
private const val DPI = 320

private data class Step(val title: String, val ok: Boolean, val detail: String)

@Composable
private fun ProbeScreen() {
    val context = LocalContext.current
    val steps = remember { mutableStateListOf<Step>() }
    var displayId by remember { mutableStateOf(-1) }
    var reader by remember { mutableStateOf<ImageReader?>(null) }
    var display by remember { mutableStateOf<VirtualDisplay?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }

    fun add(title: String, ok: Boolean, detail: String) { steps.add(Step(title, ok, detail)) }

    fun systemInfo() {
        val pm = context.packageManager
        val douyin = runCatching { pm.getPackageInfo(DOUYIN, 0) }.getOrNull()
        val shizuku = runCatching { pm.getPackageInfo(SHIZUKU, 0) }.getOrNull()
        val root = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su").any { File(it).exists() }
        add("系统信息", true, buildString {
            appendLine("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("抖音：${if (douyin != null) "已安装 ${douyin.versionName}" else "没装（或不是这个包名）"}")
            appendLine("Shizuku：${if (shizuku != null) "已安装" else "没装"}")
            appendLine("su 可执行文件：${if (root) "有" else "没有"}（有也不代表给了权限）")
            appendLine("公开虚拟屏 API：${if (Build.VERSION.SDK_INT >= 30) "有（API 30+）" else "没有（系统低于 Android 11）"}")
        })
    }

    fun create() {
        val dm = context.getSystemService(DisplayManager::class.java)
        if (Build.VERSION.SDK_INT < 30) {
            add("创建虚拟屏", false, "这台 Android ${Build.VERSION.RELEASE} 低于 Android 11，没有公开的 createVirtualDisplay；旧版只能反射隐藏 API，不保证能用。")
            return
        }
        try {
            if (reader == null) reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
            val vd = dm.createVirtualDisplay(
                "cleos-probe", W, H, DPI, reader!!.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            )
            display = vd
            displayId = vd.display.displayId
            add("创建虚拟屏", true, "成功：displayId=${vd.display.displayId}，${W}×${H}，dpi $DPI。屏幕状态：${vd.display.state}")
        } catch (e: Throwable) {
            add("创建虚拟屏", false, "失败：${e.javaClass.simpleName}：${e.message ?: "没有说明"}")
        }
    }

    fun launch() {
        if (displayId < 0) { add("启动抖音到虚拟屏", false, "先创建虚拟屏"); return }
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(DOUYIN)
        if (intent == null) { add("启动抖音到虚拟屏", false, "没装抖音，或者包名不是 $DOUYIN"); return }
        try {
            val options = ActivityOptions.makeBasic()
            options.launchDisplayId = displayId
            context.startActivity(intent, options.toBundle())
            add("启动抖音到虚拟屏", true, "startActivity 已发出（displayId=$displayId）。注意：这一步不报错不等于抖音真的显示在那块屏上，要看下一步读到的画面。")
        } catch (e: Throwable) {
            add("启动抖音到虚拟屏", false, "失败：${e.javaClass.simpleName}：${e.message ?: "没有说明"}（普通应用通常需要 MANAGE_ACTIVITY_TASKS / INTERNAL_SYSTEM_WINDOW，只有系统/root/ADB 能给）")
        }
    }

    fun frame() {
        val r = reader ?: run { add("读取画面", false, "先创建虚拟屏"); return }
        try {
            val image = r.acquireLatestImage()
            if (image == null) {
                add("读取画面", false, "没有帧：虚拟屏上没有任何东西在画（抖音没在上面，或者还没出画面）")
                return
            }
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                // 粗略判断是不是全黑：真实画面不可能每个像素都是 0。
                var nonBlack = 0
                var i = 0
                while (i + 3 < bytes.size) {
                    if (bytes[i].toInt() != 0 || bytes[i + 1].toInt() != 0 || bytes[i + 2].toInt() != 0) nonBlack++
                    i += 4 * 97
                }
                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                val file = File(context.filesDir, "probe-frame.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                bitmap.recycle()
                add("读取画面", nonBlack > 0, "拿到一帧：${image.width}×${image.height}，采样到的非黑点 $nonBlack 个；已存 ${file.absolutePath}")
            } finally {
                image.close()
            }
        } catch (e: Throwable) {
            add("读取画面", false, "失败：${e.javaClass.simpleName}：${e.message ?: "没有说明"}")
        }
    }

    fun swipe() {
        if (displayId < 0) { add("向虚拟屏滑动", false, "先创建虚拟屏"); return }
        try {
            val instrument = Instrumentation()
            val now = SystemClock.uptimeMillis()
            fun event(action: Int, x: Float, y: Float) = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            val down = event(MotionEvent.ACTION_DOWN, W / 2f, H * 0.8f)
            val move = event(MotionEvent.ACTION_MOVE, W / 2f, H * 0.2f)
            val up = event(MotionEvent.ACTION_UP, W / 2f, H * 0.2f)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            instrument.sendPointerSync(down)
            instrument.sendPointerSync(move)
            instrument.sendPointerSync(up)
            add("向虚拟屏滑动", true, "sendPointerSync 没报错（但事件很可能落在主屏，不一定进了虚拟屏 —— 要真机看抖音有没有翻页）")
        } catch (e: Throwable) {
            add("向虚拟屏滑动", false, "失败：${e.javaClass.simpleName}：${e.message ?: "没有说明"}（注入手势需要 INJECT_EVENTS，只有系统签名能给；普通应用和无障碍服务都进不了指定虚拟屏）")
        }
    }

    fun stop() {
        try {
            display?.release()
            display = null
            reader?.close()
            reader = null
            displayId = -1
            add("停止并释放", true, "虚拟屏和 ImageReader 都放掉了")
        } catch (e: Throwable) {
            add("停止并释放", false, "释放时出错：${e.javaClass.simpleName}：${e.message ?: "没有说明"}")
        }
    }

    val diagnostic = remember(steps.size) {
        buildString {
            appendLine("Cleos 虚拟屏探针")
            appendLine("Android ${Build.VERSION.RELEASE}（${Build.VERSION.SDK_INT}）· ${Build.MANUFACTURER} ${Build.MODEL}")
            steps.forEach { s -> appendLine("${if (s.ok) "✅" else "❌"} ${s.title}：${s.detail}") }
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("虚拟屏可行性探针", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("只做验证，不参与聊天。每一步都显示真实结果，不会改用主屏。", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::systemInfo) { Text("系统信息") }
            OutlinedButton(onClick = ::create) { Text("创建虚拟屏") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::launch) { Text("启动抖音") }
            OutlinedButton(onClick = ::frame) { Text("读取画面") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ::swipe) { Text("向该屏滑动") }
            OutlinedButton(onClick = ::stop) { Text("停止释放") }
        }
        OutlinedButton(onClick = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("虚拟屏探针", diagnostic))
            copied = "诊断已复制"
        }) { Text("复制诊断") }
        copied?.let { Text(it, fontSize = 12.sp) }
        steps.forEach { s ->
            Text(
                "${if (s.ok) "✅" else "❌"} ${s.title}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(s.detail, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
        }
        Text(
            "预期：未 root 的普通手机上，「创建虚拟屏」多半能成功；「启动抖音到虚拟屏」和「向该屏滑动」会以 SecurityException 失败 —— 这正是要验证的边界。",
            fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}
