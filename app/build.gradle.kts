plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.baselineProfile)
    id("jacoco") // coverage gate (B4): Gradle built-in, no extra dependency
}

// 统一签名配置来自工作区级约定脚本（不在工程里写死 keystore 路径）
apply(from = file("${rootProject.projectDir}/gradle/signing.gradle"))

val appVersionName: String = (findProperty("album.versionName") as String?) ?: "0.1.0"
val appVersionCode: Int = ((findProperty("album.versionCode") as String?) ?: "1").toInt()

android {
    namespace = "com.example.album"
    // 编译平台跟到本机最新：android-37.2（ApiLevel=37.2 / IsBaseSdk / ExtensionLevel=24）。
    // 不写 37.1：停在旧 minor 等于人为欠一次迁移。minimum 要求（compose 1.13.0-beta01 要 >= 37.1）自动满足。
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        applicationId = "com.example.album"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.findByName("unified")
        }
        getByName("release") {
            signingConfig = signingConfigs.findByName("unified")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Baseline Profile 的采集变体：内容同 release（R8 全开），但不 debuggable 以便 profileable
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("unified")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // 版本可用性检查依赖网络、且与"能否构建"无关：AGP 9.4.1 内置 Kotlin 2.4.20，
        // compose 插件版本必须与之一致；有更新的 Kotlin 时升级要连 AGP 一起动，属独立升级任务。
        disable += "NewerVersionAvailable"
    }

    // JVM 单测要在本地跑 Android 框架（Bundle/Uri/MediaStore 常量）⇒ Robolectric；
    // android-all 构件走阿里云镜像（比 central 稳），首次运行下载后本地缓存，后续可离线。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        unitTests.all { test ->
            test.maxHeapSize = "2g"
            test.systemProperty("robolectric.dependency.repo.id", "aliyun")
            test.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
            test.systemProperty("robolectric.logging", "stderr")
            // JDK 27 + Robolectric：FileDescriptor 的 SharedSecrets 访问需要显式开放内部包
            test.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
            )
        }
    }
}

// 不许留警告（防"以后再说"的债）：Kotlin 编译警告即错误
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Baseline Profile 的来源模块：`generateReleaseBaselineProfile` 跑 :benchmark 的
    // BaselineProfileGenerator，把 profile 落到 app/src/release/generated/baselineProfiles/。
    // 不写这一行，任务会"3 秒成功但什么都没采"（AGP 只发一条警告）。
    baselineProfile(project(":benchmark"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // 视频页：官方纯 Compose 播放器（PlayerSurface + 自绘控制条），不引 AndroidView 互操作层
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    // 裁剪保存时保留拍摄时间/机型等（方向由 ImageDecoder 在解码阶段归一）
    implementation(libs.androidx.exifinterface)

    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)

    // Compose 的 Robolectric UI 测试：自绘网格的测量/摆放必须在 JVM 上就能验（不依赖真机）
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

// ---- coverage gate (B4) ----------------------------------------------------
// Gradle 自带 jacoco，不引新依赖：`gradle :app:testDebugUnitTest :app:jacocoTestReport`
// 报告 app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
// 门禁 = shared/bin/coverage_gate.py 读这份 XML（阈值在 shared/data/coverage.json）。
tasks.withType<Test>().configureEach {
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> { isEnabled = true }
}

tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        // AGP 9 把 Kotlin 编译产物放在 built_in_kotlinc 下（旧的 tmp/kotlin-classes 已不存在）
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").get().asFile) {
            exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*")
        },
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile) {
            exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*")
        }
    )
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(fileTree(layout.buildDirectory.get().asFile) { include("jacoco/testDebugUnitTest.exec") })
}
