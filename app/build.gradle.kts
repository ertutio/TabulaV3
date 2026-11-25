import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=coil.annotation.ExperimentalCoilApi"
        )
    }
}

android {
    namespace = "com.tabula.v3"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tabula.v3"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "3.4"
        buildConfigField("String", "RELEASE_DATE", "\"2026.11.16\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystore = System.getenv("TABULA_KEYSTORE_FILE")
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs.create("forkRelease") {
            storeFile = file(releaseKeystore)
            storePassword = requireNotNull(System.getenv("TABULA_STORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("TABULA_KEY_ALIAS"))
            keyPassword = requireNotNull(System.getenv("TABULA_KEY_PASSWORD"))
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".recyclebin.test"
            versionNameSuffix = "-recyclebin-test"
        }
        release {
            if (!releaseKeystore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("forkRelease")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    // JVM 单测与设备测试共用的测试侧工具。
    //
    // 动机：批次日志解析这类逻辑原先只写在 androidTest 里，写错了要跑五分钟
    // 模拟器才暴露（见 BatchLogLine 的注释）。放进 sharedTest 后两边都能用，
    // JVM 上秒级就能钉住。
    //
    // #94：合成样图（50 张 PNG + 金标准）同时作为 androidTest 的 assets，
    // 让设备测试跑的是与 JVM 单测同一批文件，避免两份数据走样。
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest") {
            java.srcDir("src/sharedTest/java")
            assets.srcDirs("src/test/resources")
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material.icons.extended)

    // Coil - High Performance Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // Konfetti - Celebration Animation
    implementation(libs.konfetti.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.process)
    
    // Media Session (for Fluid Cloud)
    implementation(libs.androidx.media)

    // #94 本地中文 OCR：Tesseract4Android（Apache-2.0）+ tessdata_fast/chi_sim（Apache-2.0）。
    // 选它而不是 ML Kit：完全离线、零遥测，与 LICENSE「不上传任何照片或个人信息」一致；
    // 代价是每个 ABI 约 7MB 原生库 + 2.4MB 模型（实测数字见
    // docs/intent-ocr-evaluation-2026-11-25.md）。见 readme 中的「体积」一节。
    implementation(libs.tesseract4android)

    // Debug
    debugImplementation(libs.androidx.ui.tooling)

    // Tests
    testImplementation(libs.junit)
    // C4/T07 任务 E：防抖时序确定性验证用（与 coroutines 同版本，仅测试作用域）
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation("androidx.test:runner:1.6.2")
    // #87 审计：真实选片界面（BurstSelectionScreen）的 Compose 界面测试
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

