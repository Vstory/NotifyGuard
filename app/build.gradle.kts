import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名从 local.properties 读（该文件被 .gitignore 忽略）：本机与 CI 都往这四个键写值，
// 本机没有该文件时不建签名配置 —— 那时 release 产物是 app-release-unsigned.apk（装不上）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signStoreFile = localProps.getProperty("storeFile")
val hasSigning = !signStoreFile.isNullOrBlank()

android {
    namespace = "io.github.vstory.notifyguard"
    compileSdk = 37
    // 必须钉住：不写时 AGP 9.4.1 会挑自己的默认 build-tools（36.0.0），本机与 CI 只装了 37.0.0 时会
    // 直接报 "Failed to find Build Tools revision 36.0.0"
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.vstory.notifyguard"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
    // 未配签名时不建该配置，纯构建照常可跑
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signStoreFile!!)
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
                // v1（JAR 签名）只对 API < 24 有意义，本项目 minSdk 29 ⇒ 关；v4 会多产 .idsig，不分发
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }
    buildTypes {
        debug {
            // 与正式版共用同一把钥：两变体同签名才能互相覆盖安装（换签名会丢 LSPosed 作用域状态）
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // libxposed 本地 jar（api102）：api=编译期 + interface/service=运行期
    // kotlin-stdlib 由 AGP 内置 Kotlin 自动带上，不用手写
    compileOnly(files("libs/libxposed/api.jar"))
    implementation(files("libs/libxposed/interface.jar"))
    implementation(files("libs/libxposed/service.jar"))

    testImplementation("junit:junit:4.13.2")
}
