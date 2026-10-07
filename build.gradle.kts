// 顶层构建：只声明 Android Gradle Plugin（版本统一管理）
//
// ⚠️ 不要加 id("org.jetbrains.kotlin.android")：AGP 9 内置 Kotlin 编译，与内置实现冲突会直接编译失败
//    （Android 官方 agp-9-0-0-release-notes「built-in Kotlin」）。Kotlin 版本随 AGP 走
//    —— AGP 9.4.1 内置 Kotlin 2.2.10；要更高版本按官方同页「runtime dependency on Kotlin
//    Gradle Plugin」在 buildscript classpath 覆盖，不要改这里引插件。
plugins {
    id("com.android.application") version "9.4.1" apply false
    // Compose 编译器自 Kotlin 2.0 起不再随 Kotlin 发布，须由本插件提供；版本必须等于 Kotlin 版本
    // （AGP 9 内置 Kotlin，插件会对齐其运行时，故这里不写 kotlin.android）
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
