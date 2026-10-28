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
val frozenVersionCode = providers.gradleProperty("dgg.versionCode")
    .orElse("2")
    .get()
    .toInt()
val frozenVersionName = providers.gradleProperty("dgg.versionName")
    .orElse("1.1")
    .get()

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
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
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