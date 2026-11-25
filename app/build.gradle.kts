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

    // 单元测试的 Robolectric 运行参数。
    //
    // 为什么需要：Robolectric 在测试期按 `@Config(sdk = ...)` 去 Maven Central 下
    // android-all jar；CI runner 会被 429 限流，失败表现是**与本次改动无关的既有测试**
    // 报 MavenArtifactFetcher IOException（run 45585761735 实测 5 条）。
    // CI 先把 jar 预取到 $ROBO_ANDROID_ALL_DIR，这里把它转发进 fork 出来的测试 JVM
    // 并开启 offline 模式；本地没设这个环境变量时不加任何参数，行为不变。
    testOptions {
        unitTests.all {
            val roboDir = System.getenv("ROBO_ANDROID_ALL_DIR")
            if (!roboDir.isNullOrBlank()) {
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", roboDir)
            }
        }
    }

    // #94：合成样图（50 张 PNG + 金标准）同时作为 androidTest 的 assets，
    // 让设备测试跑的是与 JVM 单测同一批文件，避免两份数据走样。
    sourceSets {
        getByName("androidTest") {
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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

