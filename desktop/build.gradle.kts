import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipFile

// Windows 桌面客户端：独立 Kotlin/JVM 模块，与 Android 的 :app 互不影响。
// 复用协议与思路（OpenAI 兼容 SSE、JSON 配置），不共享 Android 源码（那边绑死 Room/DataStore/Context）。
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    testImplementation(kotlin("test"))
}

// 无界面自检：模拟 SSE 服务器，跑通「配置 → 角色 → 流式 → 停止 → 保存 → 重载」。
tasks.register<JavaExec>("selftest") {
    group = "verification"
    description = "Run the desktop self test (no window, mock server)."
    mainClass.set("com.cleo.desktop.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args("--selftest")
}

// 可运行的 Windows 测试包：jar + 运行时依赖 + 启动脚本。
// 绕开 jpackage/createDistributable（本机构建时那个任务会卡住）。
// 可运行的 Windows 测试包：jar + 运行时依赖 + 启动脚本。
// 不用 Sync：两端 classpath 里有同名 jar（JetBrains 的 runtime-desktop 只是 416 字节的壳，
// androidx 那份才是真的），必须留大的那份；Sync 的 duplicatesStrategy 只看先后，会留下壳。
tasks.register("bundleRuntime") {
    group = "distribution"
    description = "Assemble a runnable folder (jar + libs + .bat)."
    dependsOn(tasks.named("jar"))
    doLast {
        val dir = layout.buildDirectory.dir("bundle").get().asFile
        if (dir.exists()) dir.deleteRecursively()
        File(dir, "libs").mkdirs()
        (tasks.named("jar").get() as org.gradle.jvm.tasks.Jar).archiveFile.get().asFile
            .copyTo(File(dir, "desktop.jar"), overwrite = true)
        val seen = HashMap<String, Long>()
        for (f in configurations.runtimeClasspath.get().files) {
            if (!f.isFile) continue
            val prev = seen[f.name]
            if (prev != null && prev >= f.length()) continue
            f.copyTo(File(dir, "libs/" + f.name), overwrite = true)
            seen[f.name] = f.length()
        }
        // 把 skiko 的原生库和 ICU 数据直接解出来带上：有的机器/环境里 skiko 自己解压到临时目录
        // 会失败（临时目录不可写、被安全软件拦），带一份最稳。
        val skikoJar = File(dir, "libs").listFiles()?.firstOrNull { it.name.startsWith("skiko-awt-runtime-windows-x64") }
        if (skikoJar != null) {
            val out = File(dir, "skiko-libs").apply { mkdirs() }
            ZipFile(skikoJar).use { zip ->
                for (name in listOf("skiko-windows-x64.dll", "icudtl.dat")) {
                    val entry = zip.getEntry(name) ?: continue
                    zip.getInputStream(entry).use { input ->
                        File(out, name).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        // 启动脚本自己找 Java：这台机器上 java 不在 PATH 里（JDK 装在 .dsh\tools 下），
        // 不探测的话双击就是 "'java' 不是内部或外部命令"。
        File(dir, "CleosDesktop.bat").writeText(
            "@echo off\r\n" +
                "setlocal\r\n" +
                "set DIR=%~dp0\r\n" +
                "set JAVA=\r\n" +
                "if exist \"%JAVA_HOME%\\bin\\java.exe\" set JAVA=%JAVA_HOME%\\bin\\java.exe\r\n" +
                "if exist \"%DIR%runtime\\bin\\java.exe\" set JAVA=%DIR%runtime\\bin\\java.exe\r\n" +
                "if not defined JAVA if exist \"%USERPROFILE%\\.dsh\\tools\\jdk\\jdk-21.0.12.1+1\\bin\\java.exe\" set JAVA=%USERPROFILE%\\.dsh\\tools\\jdk\\jdk-21.0.12.1+1\\bin\\java.exe\r\n" +
                "if not defined JAVA for /d %%D in (\"C:\\Program Files\\Java\\jdk-21*\") do if exist \"%%D\\bin\\java.exe\" set JAVA=%%D\\bin\\java.exe\r\n" +
                "if not defined JAVA for /d %%D in (\"C:\\Program Files\\Eclipse Adoptium\\jdk-21*\") do if exist \"%%D\\bin\\java.exe\" set JAVA=%%D\\bin\\java.exe\r\n" +
                "if not defined JAVA (\r\n" +
                "  echo 找不到 Java 21。请安装 JDK 21，或把 JAVA_HOME 指向它。\r\n" +
                "  pause\r\n" +
                "  exit /b 1\r\n" +
                ")\r\n" +
                "if exist \"%DIR%skiko-libs\\skiko-windows-x64.dll\" set JAVA_TOOL_OPTIONS=-Dskiko.library.path=%DIR%skiko-libs\r\n" +
                "\"%JAVA%\" -cp \"%DIR%desktop.jar;%DIR%libs\\*\" com.cleo.desktop.MainKt %*\r\n",
            Charsets.UTF_8,
        )
        // 双击用这个：VBS 把 bat 藏起来跑，不会先弹一个黑框再出窗口。
        val vbs = """
            Set fso = CreateObject("Scripting.FileSystemObject")
            Set shell = CreateObject("WScript.Shell")
            dir = fso.GetParentFolderName(WScript.ScriptFullName)
            shell.Run "cmd /c " & Chr(34) & dir & "\CleosDesktop.bat" & Chr(34), 0, False
        """.trimIndent()
        File(dir, "CleosDesktop.vbs").writeText(vbs + "\r\n", Charsets.UTF_8)
    }
}

compose.desktop {
    application {
        mainClass = "com.cleo.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "CleosDesktop"
            packageVersion = "0.1.0"
        }
    }
}
