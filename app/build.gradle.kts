plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.houg.tv"
    compileSdk = 35
    // SDK 里只有 build-tools 36，显式指定避免 AGP 尝试下载 34
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.houg.tv"
        // 适配 Android 5.0（API 21）及以上：HG片源为 HEVC+CENC，需 API 21 起的 HEVC 硬解能力
        minSdk = 21
        targetSdk = 35
        versionCode = 126
        versionName = "1.1.3"
    }

    // 传统 View 体系，不使用 Compose
    buildFeatures { }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    lint {
        // 老设备适配：关闭 NewApi 等对低版本 API 的 lint 报错（编译期不阻断）
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // androidx 基础（minSdk 14 兼容版本）
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // 网络：OkHttp 3.12.x 是最后支持 API 14 的版本（3.13+ 需 API 21）
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // 播放器：ExoPlayer media3（仅 exoplayer，避免 datasource-okhttp 与 OkHttp 3.12.13 冲突）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // media3-ui：提供 AspectRatioFrameLayout（画面按比例缩放）
    implementation("androidx.media3:media3-ui:1.4.1")

    // 图片加载：Glide 4.12（API 14+ 兼容）
    implementation("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.12.0")

    // HEIC/HEIF 封面解码（HG封面为 HEIC 格式，基于 libheif/libde265）
    // 排除 debug 变体：该库同时发布 release/debug AAR 造成重复类
    implementation("com.github.msisuzney:GlideHeifDecoder:0.5") {
        exclude(group = "com.github.msisuzney.GlideHeifDecoder", module = "heifdecoder-debug")
    }

    // 二维码生成（跨屏输入弹窗：电视出码，手机扫码打开局域网输入页）
    // core 为纯 Java 无 Android 依赖，minSdk 21 安全
    implementation("com.google.zxing:core:3.5.3")

    // 协程（API 14+ 兼容）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON 使用系统自带 org.json，无需额外依赖

    // 签名栈离线回归（signing.json 固定向量对拍），纯 JVM 单测
    testImplementation("junit:junit:4.13.2")
}
