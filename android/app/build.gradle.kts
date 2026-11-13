import java.io.File
import java.net.URL
import java.security.MessageDigest
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
        versionCode = 2
        versionName = "0.1.1"
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
        debug {
            // 本地配置了 ANDROID_KEYSTORE_PATH 时与 Release 共用同一签名，方便真机无缝覆盖调试
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
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
    androidResources {
        noCompress += "onnx"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/probeAssets"))
}

data class ModelAssetSpec(
    val cacheFileName: String,
    val assetFileName: String,
    val sha256: String,
    val url: String,
)

val bundledModelSpecs = listOf(
    ModelAssetSpec(
        cacheFileName = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
        assetFileName = "campplus_zh_16k.onnx",
        sha256 = "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
    ),
    ModelAssetSpec(
        cacheFileName = "ced_tiny_audio_tagging_int8.onnx",
        assetFileName = "ced_tiny_audio_tagging_int8.onnx",
        sha256 = "73aa22e783115f6a4ae169b36089907e07d0fd44795595eddea9c1bfc74cc945",
        url = "https://huggingface.co/k2-fsa/sherpa-onnx-ced-tiny-audio-tagging-2024-04-19/resolve/main/model.int8.onnx",
    ),
    ModelAssetSpec(
        cacheFileName = "bge_small_zh_v15_q8.onnx",
        assetFileName = "bge_small_zh_v15_q8.onnx",
        sha256 = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc",
        url = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_quantized.onnx",
    ),
    ModelAssetSpec(
        cacheFileName = "bge_small_zh_vocab.txt",
        assetFileName = "bge_small_zh_vocab.txt",
        sha256 = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
        url = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/vocab.txt",
    ),
)

fun fileSha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().buffered().use { ins ->
        val buf = ByteArray(65536)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val syncProbeAsset by tasks.registering {
    val probeSrc = rootProject.file("../internal/asr/probe.opus")
    val outDir = layout.buildDirectory.dir("generated/probeAssets")
    val userHome = System.getProperty("user.home")
    val gradleHome = gradle.gradleUserHomeDir
    inputs.file(probeSrc)
    inputs.property("modelShas", bundledModelSpecs.joinToString(",") { "${it.assetFileName}:${it.sha256}" })
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        probeSrc.copyTo(File(dir, "probe.opus"), overwrite = true)

        for (spec in bundledModelSpecs) {
            val localCache = File(userHome, ".cache/hark/models/${spec.cacheFileName}")
            val gradleCache = File(gradleHome, "caches/hark-models/${spec.cacheFileName}")
            val modelFile = when {
                localCache.exists() && fileSha256(localCache) == spec.sha256 -> localCache
                gradleCache.exists() && fileSha256(gradleCache) == spec.sha256 -> gradleCache
                else -> {
                    gradleCache.parentFile.mkdirs()
                    logger.lifecycle("Downloading ${spec.assetFileName} (${spec.url})...")
                    val tmp = File(gradleCache.parentFile, "${spec.cacheFileName}.tmp")
                    URL(spec.url).openConnection().apply {
                        setRequestProperty("User-Agent", "Hark-Build/1.0")
                        connectTimeout = 30_000
                        readTimeout = 120_000
                    }.getInputStream().use { ins ->
                        tmp.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    val gotSha = fileSha256(tmp)
                    check(gotSha == spec.sha256) {
                        "Model SHA-256 mismatch for ${spec.assetFileName}: expected ${spec.sha256}, got $gotSha"
                    }
                    tmp.renameTo(gradleCache)
                    gradleCache
                }
            }
            modelFile.copyTo(File(dir, spec.assetFileName), overwrite = true)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncProbeAsset)
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
    testImplementation("org.json:json:20231013")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
