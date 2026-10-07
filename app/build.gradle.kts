import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名从 local.properties 读（该文件被 .gitignore 忽略）：本机与 CI 都往这四个键写值，
// 本机没有该文件时不建签名配置 —— 那时 release 产物是 app-release-unsigned.apk（装不上）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signStoreFile = localProps.getProperty("storeFile")
val hasSigning = !signStoreFile.isNullOrBlank()

// CI 渠道（build-ci.yml）传 `-PciVersionSuffix=ci-<变体>.<短号>`：同一 versionCode 的多次构建在系统
// 应用信息页完全同形、认不出是哪次提交。`+` 之后属 semver 的构建元数据段，不参与版本比较。
val ciVersionSuffix = providers.gradleProperty("ciVersionSuffix").orNull?.trim()?.takeIf { it.isNotEmpty() }

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
        versionCode = 9
        versionName = "1.7.0"
        // 刻意「后赋值覆盖」而非改写上面那行字面量：CI 工作流用 sed 取本文件**第一处** versionName，
        // 字面量必须保持可被解析
        ciVersionSuffix?.let { versionName = "${android.defaultConfig.versionName}+$it" }
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
            // 开 R8：release 与正式版同构建类型，CI 每次出 release 包即顺带验证 keep 规则
            // （入口类在 java_init.list 里按名反射加载，keep 漏了是「装得上、模块不加载」的静默失败）
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
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

    // UI 只走官方 Material 3（设计方案 §9.1 / D6）：compose 版本由 BOM 统一管，不自定版本号。
    // 这几个库只进 App 侧 —— 模块端跑在 system_server，不碰 UI（见 core/EntryHook 的装配）。
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // NavigationSuiteScaffold：一套代码自适应手机底栏与宽屏侧栏（§9.1 明确不自写两套导航）
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    // 记录屏的状态要跨重组与屏幕旋转存活（在途的标注指令若随重组丢掉标志位，连点会并发下发）
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    testImplementation("junit:junit:4.13.2")
    // JVM 单测需要 org.json 的真实现：android.jar 里那份是 stub，未 mock 时调用即抛
    testImplementation("org.json:json:20240303")
    // 配置通道自检要造 XposedInterface 假实现（api.jar 是 compileOnly，不进运行时，须显式补）
    testImplementation(files("libs/libxposed/api.jar"))
}
