// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        // T09（2026-11-09）：R8 8.13.19 覆盖 AGP 8.9.1 捆绑版。官方依据
        // developer.android.com/studio/build/kotlin-d8-r8-versions —— Kotlin 2.3
        // 要求 R8 8.13.19（AGP 8.2.2–8.13 范围内、捆绑 R8 需按此覆盖）。修复
        // Release/R8 的 100 条 "An error occurred when parsing kotlin metadata"
        // 警告（集成 run 42643510385 实测）。回退=删除本 classpath 行。
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
