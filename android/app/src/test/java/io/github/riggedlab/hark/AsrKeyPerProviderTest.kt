package io.github.riggedlab.hark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 切换服务商不能把上一家的密钥带过去（曾导致百炼测试连接 401）。 */
@RunWith(RobolectricTestRunner::class)
class AsrKeyPerProviderTest {
    @Test
    fun eachProviderKeepsItsOwnKey() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val volc = AsrConfig(AsrProvider.VOLCENGINE, AsrProvider.VOLCENGINE.defaultUrl, "volc-key")
        AsrConfig.save(ctx, volc)
        assertEquals("volc-key", AsrConfig.savedKey(ctx, AsrProvider.VOLCENGINE))
        assertEquals("", AsrConfig.savedKey(ctx, AsrProvider.DASHSCOPE))

        AsrConfig.save(ctx, AsrConfig(AsrProvider.DASHSCOPE, AsrProvider.DASHSCOPE.defaultUrl, "sk-ds"))
        assertEquals("sk-ds", AsrConfig.savedKey(ctx, AsrProvider.DASHSCOPE))
        assertEquals("volc-key", AsrConfig.savedKey(ctx, AsrProvider.VOLCENGINE))
    }

    @Test
    fun legacySingleKeyOnlyBelongsToItsSavedProvider() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear()
            .putString("asr_provider", "volcengine").putString("asr_api_key", "old-volc").commit()
        assertEquals("old-volc", AsrConfig.savedKey(ctx, AsrProvider.VOLCENGINE))
        assertEquals("", AsrConfig.savedKey(ctx, AsrProvider.DASHSCOPE))
    }
}
