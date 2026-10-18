plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    // Windows 桌面客户端（Compose Multiplatform Desktop）：独立模块，不参与 Android 构建。
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
}
