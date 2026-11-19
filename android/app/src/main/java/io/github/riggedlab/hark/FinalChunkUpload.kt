package io.github.riggedlab.hark

import java.util.concurrent.Executor

/** 等写入队列封完最后一段，再独立补传；调用者不等待磁盘或网络。 */
internal fun finishRecording(
    io: Executor,
    closeChunk: () -> Unit,
    onFinished: () -> Unit = {},
    upload: () -> Unit,
) {
    io.execute {
        var uploadStarted = false
        try {
            closeChunk()
            Thread({
                try { upload() }
                finally { onFinished() }
            }, "hark-final-upload").apply { isDaemon = true; start() }
            uploadStarted = true
        } finally {
            if (!uploadStarted) onFinished()
        }
    }
}
