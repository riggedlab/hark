package io.github.riggedlab.hark

import java.util.concurrent.Executor

/** 等写入队列封完最后一段，再独立补传；调用者不等待磁盘或网络。 */
internal fun finishRecording(io: Executor, closeChunk: () -> Unit, upload: () -> Unit) {
    io.execute {
        closeChunk()
        Thread(upload, "hark-final-upload").apply { isDaemon = true; start() }
    }
}
