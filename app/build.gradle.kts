import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val frozenApplicationId = providers.gradleProperty("dgg.applicationId")
    .orElse("com.example.dgg")
    .get()
val releaseApiBaseUrl = providers.gradleProperty("dgg.apiBaseUrl")
    .orElse("https://api.example.com/")
    .get()
val explicitVersionCode = providers.gradleProperty("dgg.versionCode").orNull
val frozenVersionCode = providers.gradleProperty("dgg.versionCode")
    .orElse("2")
    .get()
    .toInt()
val frozenVersionName = providers.gradleProperty("dgg.versionName")
    .orElse("1.1")
    .get()

// ⚠️ 只要显式指定了版本号（= 这是一次真正的发布构建），就必须同时指定服务端地址。
//
// 踩过：2026-10-29 连着打了两个包都只传了 -Pdgg.versionCode/versionName，
// 结果 API_BASE_URL 悄悄落回占位值 https://api.example.com/——能编译、能安装、
// 甚至能跑（授权 token 有本地缓存），但授权握手 / OTA / 日志上报全部指向不存在的域名。
// 这种包发出去没人能一眼看出来，只能在服务端日志里发现「这个设备从没来过」。
if (explicitVersionCode != null && releaseApiBaseUrl.contains("api.example.com")) {
    throw GradleException(
        "release 构建必须同时指定服务端地址：-Pdgg.apiBaseUrl=http://<host>:<port>/ " +
            "（当前仍是占位值 $releaseApiBaseUrl）"
    )
}

android {
    namespace = "com.example.dgg"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = frozenApplicationId
        minSdk = 24
        targetSdk = 37
        versionCode = frozenVersionCode
        versionName = frozenVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 服务端地址：debug 用 adb reverse tcp:3000 tcp:3000 + 回环；
        // release 必须在发布前设置 dgg.apiBaseUrl（HTTPS）。
        buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")

                // 🔴 必须签 v1（JAR signing），哪怕 minSdk=24 用 v2 也能装。
                //
                // 原因不是安装，是 **OTA 校验**：`OtaVerifier` 在 API < 28 的设备上只能用
                // `getPackageArchiveInfo(path, GET_SIGNATURES)` 读 **APK 文件**的签名，而
                // GET_SIGNATURES 读的正是 v1（JAR）签名。minSdk 24 时 AGP 默认关 v1，
                // 包就只有 v2 —— 于是旧系统上 `archive.signatures` 为 null，
                // 校验报「安装包没有签名」，OTA 在这类设备上永远升不了级。
                //
                // 2026-10-30 线上事故（日志 R-20261030-50E355，vc33 / Android 7~8）：
                // `校验拒绝：安装包没有签名`，而 `apksigner verify -v` 显示
                // v1=false / v2=true —— 两头一对就清楚了。
                //
                // v1 不改变签名证书，只多一份 JAR 签名：证书指纹仍是 59927d81…，
                // 服务端 APP_SIGNING_CERT_SHA256 不用动，已装设备照样能覆盖升级。
                // 安全上也没有退化：v2 存在时系统优先用 v2，v1 只在旧系统解析/兜底时被读。
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 默认回环 3000；本机 3000 被别的服务占了时用
            // -Pdgg.debugApiBaseUrl=http://127.0.0.1:3100/ 覆盖（配合 adb reverse tcp:3100 tcp:3100）。
            // 之所以要能换端口：adb reverse 转发到本机某端口时，若该端口已被其它进程绑在
            // 0.0.0.0 上，请求会随机落到别的服务，表现为「莫名其妙回了别人的错误 JSON」。
            val debugApiBaseUrl = (project.findProperty("dgg.debugApiBaseUrl") as String?)
                ?: "http://127.0.0.1:3000/"
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // 只混淆本工程自己的代码。曾把 androidx.** / kotlin.** / kotlinx.** 也放进 scope，
            // 结果 release 包启动即崩：IllegalAccessError: Class kotlin.collections.h extended by
            // class j7 is inaccessible（androidx.startup.InitializationProvider 初始化时类加载失败）。
            // 第三方库不参与改名，只跟随 shrinking；真要混淆它们必须逐库补 keep 规则。
            optimization {
                enable = true
                packageScope = setOf("com.example.dgg.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}