package io.github.riggedlab.hark

import android.content.Context

/**App 本地设置优先；仅本地开发开启 -PdevSecrets=true 时才有编译默认值。 */
data class Config(val serverUrl: String, val token: String, val e2eeKey: ByteArray) {
    companion object {
        fun rawValues(ctx: Context): Triple<String, String, String> {
            val p = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
            val saved = p.getString("server_url", null)
                ?.takeUnless { BuildConfig.BLOCKED_HOST.isNotBlank() && it.contains(BuildConfig.BLOCKED_HOST) }
            return Triple((saved ?: BuildConfig.DEFAULT_SERVER_URL).trim().trimEnd('/'),
                p.getString("token", null) ?: BuildConfig.DEFAULT_PHONE_TOKEN,
                p.getString("e2ee_key", null) ?: BuildConfig.DEFAULT_E2EE_KEY)
        }

        fun statusHint(ctx: Context): String? {
            val (url, token, key) = rawValues(ctx)
            if (url.isBlank() || token.isBlank()) return "未配置服务器（点下方「服务器设置」）"
            if (key.isBlank()) return "未配置加密密钥（点下方「服务器设置」）"
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
                .putString("e2ee_key", cleanKey).apply()
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
