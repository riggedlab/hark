package io.github.riggedlab.hark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AsrQueueTest {
    @Test fun permanentFailuresStaySkippedAcrossBatchesInstancesAndModeChanges() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val chunks = RecorderService.chunkDir(ctx).apply { mkdirs() }
        val bad = File(chunks, "20261113-155000.opus").apply { writeBytes(ByteArray(64)) }
        val timeout = File(chunks, "20261113-155500.opus").apply { writeBytes(ByteArray(64)) }
        val next = File(chunks, "20261113-160000.opus").apply { writeBytes(ByteArray(64)) }
        Config.setMode(ctx, WorkMode.DIRECT_ASR)
        val first = Uploader(ctx)
        first.markAsrNoRetry(bad.name, AsrFailureKind.BAD_AUDIO)
        first.markAsrNoRetry(timeout.name, AsrFailureKind.READ_TIMEOUT)
        val afterRestart = Uploader(ctx)
        assertTrue(afterRestart.hasPendingWork()) // 后续正常录音仍在队列中。
        assertTrue(afterRestart.isSkippedBadAudio(bad))
        assertTrue(afterRestart.snapshotMarks().asrTimedOut.contains(timeout.name))
        next.delete()
        assertFalse(afterRestart.hasPendingWork())
        Config.setMode(ctx, WorkMode.CLOUD)
        assertFalse(afterRestart.hasPendingWork()) // 切回信箱也不能把可能已计费的音频再发给 Worker。
        Config.setMode(ctx, WorkMode.DIRECT_ASR)
        AsrConfig.save(ctx, AsrConfig(AsrProvider.DASHSCOPE, "http://localhost", "test"))
        assertTrue(Uploader(ctx).snapshotMarks().asrTimedOut.contains(timeout.name))
        assertTrue(bad.exists())
        assertTrue(timeout.exists())
    }
}
