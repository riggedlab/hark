import java.util.Properties

// 公开编译默认不打包任何私人配置（用户在 App 内「服务器设置」填写）。
// 仅本地自用调试且显式传入 -PdevSecrets=true 时，才从仓库外 ../.secrets/ 注入默认值。
val useDevSecrets = providers.gradleProperty("devSecrets").orNull == "true"
val secrets = Properties().apply {
    if (useDevSecrets) {
        val f = rootProject.file("../.secrets/mailbox.env")
        if (f.exists()) f.inputStream().use { load(it) }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.riggedlab.hark"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.riggedlab.hark"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }   // 只打包 64 位 ARM，安装包小很多
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"${secrets.getProperty("SERVER_URL", "")}\"")
        buildConfigField("String", "DEFAULT_PHONE_TOKEN", "\"${secrets.getProperty("PHONE_TOKEN", "")}\"")
        buildConfigField("String", "DEFAULT_E2EE_KEY", "\"${secrets.getProperty("E2EE_KEY", "")}\"")
        // 用 IP 访问信箱时没有公共 CA 证书，仅在 -PdevSecrets=true 时内置自签证书
        val cert = if (useDevSecrets) {
            rootProject.file("../.secrets/mailbox-cert.pem").takeIf { it.exists() }?.readText() ?: ""
        } else ""
        buildConfigField("String", "MAILBOX_CERT", "\"${cert.replace("\n", "\\n")}\"")
        buildConfigField("String", "BLOCKED_HOST", "\"${secrets.getProperty("BLOCKED_HOST", "")}\"")
    }

    signingConfigs {
        val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // CI 发版注入持久签名密钥（保证跨版本覆盖升级不丢数据）；本地未配密钥时回退 debug 签名方便直装
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 手机上判断有没有人声（Silero VAD 模型在 assets/ 里，MIT 许可）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
