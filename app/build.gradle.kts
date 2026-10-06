plugins {
    id("com.android.application")
}

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
    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    // libxposed 本地 jar（api102）：api=编译期 + interface/service=运行期
    // kotlin-stdlib 由 AGP 内置 Kotlin 自动带上，不用手写
    compileOnly(files("libs/libxposed/api.jar"))
    implementation(files("libs/libxposed/interface.jar"))
    implementation(files("libs/libxposed/service.jar"))
}
