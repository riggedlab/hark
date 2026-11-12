package io.github.riggedlab.hark

import android.content.Context

enum class WorkMode(val id: String, val label: String) {
    CLOUD("cloud", "云端信箱同步"),
    DIRECT_ASR("direct_asr", "手机直连转写");

    companion object {
        fun fromId(id: String?): WorkMode? = entries.firstOrNull { it.id == id }
    }
}

enum class AsrProvider(
    val id: String,
    val label: String,
    val shortLabel: String,
    val defaultUrl: String,
    val defaultModel: String,
) {
    VOLCENGINE(
        id = "volcengine",
        label = "火山引擎 · 豆包语音大模型",
        shortLabel = "豆包语音",
        defaultUrl = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash",
        defaultModel = "volc.bigasr.auc_turbo",
    ),
    OPENAI(
        id = "openai",
        label = "OpenAI 兼容接口",
        shortLabel = "OpenAI 兼容",
        defaultUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini-transcribe",
    ),
    HARK_HTTP(
        id = "hark_http",
        label = "自建 Hark HTTP (/transcribe)",
        shortLabel = "自建 HTTP",
        defaultUrl = "http://127.0.0.1:8787",
        defaultModel = "",
    );

    companion object {
        fun fromId(id: String?): AsrProvider = entries.firstOrNull { it.id == id } ?: VOLCENGINE
    }
}

data class AsrConfig(
    val provider: AsrProvider,
    val baseUrl: String,
    val apiKey: String,
    val appId: String = "",
    val model: String = "",
) {
    fun normalized(): AsrConfig {
        val cleanKey = apiKey.trim()
        val cleanApp = appId.trim()
        val rawUrl = baseUrl.trim()
        val rawModel = model.trim()
        return when (provider) {
            AsrProvider.VOLCENGINE -> {
                val url = when {
                    rawUrl.isBlank() -> provider.defaultUrl
                    rawUrl.contains("/api/") -> rawUrl
                    else -> rawUrl.trimEnd('/') + "/api/v3/auc/bigmodel/recognize/flash"
                }
                AsrConfig(
                    provider = provider,
                    baseUrl = url,
                    apiKey = cleanKey,
                    appId = cleanApp,
                    model = rawModel.ifBlank { provider.defaultModel },
                )
            }
            AsrProvider.OPENAI -> AsrConfig(
                provider = provider,
                baseUrl = rawUrl.ifBlank { provider.defaultUrl }.trimEnd('/'),
                apiKey = cleanKey,
                appId = "",
                model = rawModel.ifBlank { provider.defaultModel },
            )
            AsrProvider.HARK_HTTP -> AsrConfig(
                provider = provider,
                baseUrl = rawUrl.trimEnd('/'),
                apiKey = cleanKey,
                appId = "",
                model = rawModel,
            )
        }
    }

    fun validationError(): String? {
        val c = normalized()
        return when (c.provider) {
            AsrProvider.VOLCENGINE -> if (c.apiKey.isBlank()) "未填写豆包语音 API Key（点下方「转写与同步设置」）" else null
            AsrProvider.OPENAI -> when {
                c.baseUrl.isBlank() -> "未填写 OpenAI 兼容接口地址"
                c.model.isBlank() -> "未填写转写模型名称"
                !c.baseUrl.startsWith("http://") && !c.baseUrl.startsWith("https://") -> "接口地址需以 http:// 或 https:// 开头"
                else -> null
            }
            AsrProvider.HARK_HTTP -> when {
                c.baseUrl.isBlank() -> "未填写自建转写服务地址"
                !c.baseUrl.startsWith("http://") && !c.baseUrl.startsWith("https://") -> "接口地址需以 http:// 或 https:// 开头"
                else -> null
            }
        }
    }

    companion object {
        fun rawValues(ctx: Context): AsrConfig {
            val p = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
            val provider = AsrProvider.fromId(p.getString("asr_provider", null))
            return AsrConfig(
                provider = provider,
                baseUrl = p.getString("asr_base_url", null) ?: provider.defaultUrl,
                apiKey = p.getString("asr_api_key", null) ?: "",
                appId = p.getString("asr_app_id", null) ?: "",
                model = p.getString("asr_model", null) ?: provider.defaultModel,
            )
        }

        fun statusHint(ctx: Context): String? = rawValues(ctx).validationError()

        fun load(ctx: Context): AsrConfig? {
            val cfg = rawValues(ctx).normalized()
            return if (cfg.validationError() == null) cfg else null
        }

        fun save(ctx: Context, cfg: AsrConfig) {
            ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString("asr_provider", cfg.provider.id)
                .putString("asr_base_url", cfg.baseUrl.trim())
                .putString("asr_api_key", cfg.apiKey.trim())
                .putString("asr_app_id", cfg.appId.trim())
                .putString("asr_model", cfg.model.trim())
                .apply()
            Uploader.clearLocalAsrFailures()
            AppState.update { it.copy(localAsrError = null) }
        }
    }
}

/** App 本地设置优先；仅本地开发开启 -PdevSecrets=true 时才有编译默认值。 */
data class Config(val serverUrl: String, val token: String, val e2eeKey: ByteArray) {
    companion object {
        fun mode(ctx: Context): WorkMode {
            val p = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
            WorkMode.fromId(p.getString("work_mode", null))?.let { return it }
            return when {
                load(ctx) != null -> WorkMode.CLOUD
                AsrConfig.load(ctx) != null -> WorkMode.DIRECT_ASR
                else -> WorkMode.CLOUD
            }
        }

        fun setMode(ctx: Context, mode: WorkMode) {
            val prev = mode(ctx)
            ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString("work_mode", mode.id)
                .apply()
            if (prev != mode) {
                AppState.update { it.copy(localAsrRunning = null, localAsrError = null) }
                if (mode == WorkMode.DIRECT_ASR) {
                    AppState.updateRemote { RemoteStatus() }
                }
            }
        }

        fun rawValues(ctx: Context): Triple<String, String, String> {
            val p = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
            val saved = p.getString("server_url", null)
                ?.takeUnless { BuildConfig.BLOCKED_HOST.isNotBlank() && it.contains(BuildConfig.BLOCKED_HOST) }
            return Triple(
                (saved ?: BuildConfig.DEFAULT_SERVER_URL).trim().trimEnd('/'),
                p.getString("token", null) ?: BuildConfig.DEFAULT_PHONE_TOKEN,
                p.getString("e2ee_key", null) ?: BuildConfig.DEFAULT_E2EE_KEY,
            )
        }

        fun statusHint(ctx: Context): String? {
            val (url, token, key) = rawValues(ctx)
            if (url.isBlank() || token.isBlank()) return "未配置服务器（点下方「转写与同步设置」）"
            if (key.isBlank()) return "未配置加密密钥（点下方「转写与同步设置」）"
            if (runCatching { Crypto.decodeKey(key) }.isFailure) return "加密密钥格式错误（需 32 字节 Base64 或 hex）"
            return null
        }

        fun load(ctx: Context): Config? {
            val (url, token, key) = rawValues(ctx)
            if (url.isBlank() || token.isBlank()) return null
            return runCatching { Config(url, token, Crypto.decodeKey(key)) }.getOrNull()
        }

        fun save(ctx: Context, url: String, token: String, key: String) {
            val prev = rawValues(ctx)
            val cleanUrl = url.trim().trimEnd('/')
            val cleanKey = key.trim()
            ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString("server_url", cleanUrl)
                .putString("token", token.trim())
                .putString("e2ee_key", cleanKey)
                .apply()
            if (cleanUrl != prev.first || cleanKey != prev.third) {
                // 只重置服务端拉取游标，保留 speaker_edits_dirty / speaker_edits_revision 等未同步本地修改
                ctx.getSharedPreferences("sync_state", Context.MODE_PRIVATE).edit()
                    .remove("ver").remove("after_seq")
                    .remove("ws_ver").remove("sm_ver").remove("sp_ver")
                    .apply()
                AppState.updateRemote { RemoteStatus() }
            }
        }
    }
}
