package io.github.riggedlab.hark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AsrQueueTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun chunk(name: String) = File(RecorderService.chunkDir(ctx).apply { mkdirs() }, name).apply {
        writeBytes(ByteArray(64))
        File(ctx.filesDir, "vad_gate/$name.txt").apply { parentFile?.mkdirs() }.writeText("keep")
    }

    @Test fun snapshotIgnoresTemporaryAndUnreadableFailureEntries() {
        val bad = chunk("20261113-155000.opus")
        val next = chunk("20261113-160000.opus")
        val failures = File(ctx.filesDir, "asr_no_retry").apply { mkdirs() }
        File(failures, bad.name).writeText(AsrFailureKind.BAD_AUDIO.name)
        // A temporary entry can disappear during rename. An unreadable entry must also be harmless.
        File(failures, ".${next.name}.tmp").mkdir()
        File(failures, next.name).mkdir()

        val uploader = Uploader(ctx)
        val snapshot = uploader.snapshotMarks()
        assertEquals(setOf(bad.name), snapshot.badAudio)
        assertEquals(1, snapshot.unuploadedCount)
        assertTrue(uploader.hasPendingWork())
        assertFalse(uploader.isSkippedBadAudio(next))
    }

    @Test fun legacyTimeoutRemainsScopedToThePreviousProvider() {
        val timeout = chunk("20261113-155500.opus")
        val uploader = Uploader(ctx)
        val cfg = AsrConfig(AsrProvider.DASHSCOPE, "http://localhost", "test")
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString("asr_provider", cfg.provider.id).commit()
        File(ctx.filesDir, "asr_no_retry/${timeout.name}").writeText(AsrFailureKind.READ_TIMEOUT.name)
        Config.setMode(ctx, WorkMode.DIRECT_ASR)
        assertFalse(Uploader(ctx).hasPendingWork())
        assertEquals(setOf(timeout.name), uploader.snapshotMarks().asrTimedOut)

        val alternate = AsrConfig(AsrProvider.HARK_HTTP, "http://localhost", "")
        AsrConfig.save(ctx, alternate)
        assertTrue(Uploader(ctx).hasPendingWork())
        assertTrue(uploader.snapshotMarks().asrTimedOut.isEmpty())

        AsrConfig.save(ctx, cfg)
        assertFalse(Uploader(ctx).hasPendingWork())
        assertEquals(setOf(timeout.name), uploader.snapshotMarks().asrTimedOut)
    }

    @Test fun timedOutAudioCanBeTranscribedByAnotherProviderAndUploadedToCloud() {
        val timedOut = chunk("20261113-170000.opus")
        val calls = AtomicInteger()
        val uploads = AtomicInteger()
        withTestServer({ request ->
            when {
                request.requestLine.startsWith("POST /transcribe ") -> {
                    calls.incrementAndGet()
                    HttpResp(body = """{"text":"恢复转写","duration_s":1}""")
                }
                request.requestLine.startsWith("PUT /v2/inbox/") -> {
                    uploads.incrementAndGet()
                    HttpResp()
                }
                else -> HttpResp(status = 500)
            }
        }) { url ->
            val cfg = AsrConfig(AsrProvider.DASHSCOPE, url, "test")
            val uploader = Uploader(ctx)
            AsrConfig.save(ctx, cfg)
            Config.setMode(ctx, WorkMode.DIRECT_ASR)
            uploader.markReadTimeout(timedOut.name, cfg.provider)
            assertEquals(0 to 0, Uploader(ctx).transcribePendingLocally())

            // Timeouts still count toward unsent cloud work.
            assertEquals(1, uploader.snapshotMarks().unuploadedCount)
            Config.save(ctx, url, "test-phone-token", Base64.getEncoder().encodeToString(ByteArray(32)))
            Config.setMode(ctx, WorkMode.CLOUD)
            assertTrue(uploader.snapshotMarks().asrTimedOut.isEmpty())
            assertEquals(1 to 0, uploader.uploadPending())
            assertEquals(1, uploads.get())

            AsrConfig.save(ctx, AsrConfig(AsrProvider.HARK_HTTP, url, ""))
            Config.setMode(ctx, WorkMode.DIRECT_ASR)
            assertEquals(1 to 0, uploader.transcribePendingLocally())
            assertEquals(1, calls.get())
            assertEquals("恢复转写", Transcripts.get(ctx, timedOut.name)?.text)
        }
    }

    @Test fun runawayIsRecordedWithoutAnEmptyTranscriptAndQueueContinues() {
        val runaway = chunk("20261113-180000.opus")
        val good = chunk("20261113-180001.opus")
        val calls = AtomicInteger()
        withTestServer({
            val text = if (calls.incrementAndGet() == 1) "不可用".repeat(100) else "正常句。"
            HttpResp(body = JSONObject().put("text", text).put("duration_s", 1).toString())
        }) { url ->
            AsrConfig.save(ctx, AsrConfig(AsrProvider.HARK_HTTP, url, ""))
            Config.setMode(ctx, WorkMode.DIRECT_ASR)
            val uploader = Uploader(ctx)

            assertEquals(1 to 0, uploader.transcribePendingLocally())
            assertEquals(2, calls.get())
            assertNull(Transcripts.get(ctx, runaway.name))
            assertEquals("正常句。", Transcripts.get(ctx, good.name)?.text)
            assertEquals(setOf(runaway.name), uploader.snapshotMarks().asrRejected)
            assertTrue(File(ctx.filesDir, "asr_no_retry/${runaway.name}").readText().contains("文字密度异常"))
            assertFalse(uploader.hasPendingWork())
        }
    }
}
