package io.github.riggedlab.hark

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LinkState(val label: String) {
    STOPPED("未启动"),
    SCANNING("正在寻找设备…"),
    CONNECTING("正在连接…"),
    PAIRING("正在配对…"),
    PAIRING_REQUIRED("需要重新配对"),
    STREAMING("录音中"),
    RECONNECTING("连接断开，等待重连…"),
}

data class RemoteStatus(
    val mailboxReachable: Boolean? = null,
    val mailboxError: String? = null,
    val inboxCount: Int = 0,
    val workerOnline: Boolean = false,
    val workerAgeSec: Double? = null,
    val workerPhase: String? = null,
    val workerCurrent: String? = null,
    val workerPhaseElapsedSec: Double = 0.0,
    val asrOk: Boolean? = null,
    val asrModel: String? = null,
    val workerError: String? = null,
) {
    fun aged(seconds: Double): RemoteStatus {
        val age = workerAgeSec?.plus(seconds.coerceAtLeast(0.0))
        return copy(workerAgeSec = age, workerOnline = workerPhase != null && age != null && age <= 45,
            workerPhaseElapsedSec = workerPhaseElapsedSec + seconds.coerceAtLeast(0.0))
    }
}

data class Status(
    val remote: RemoteStatus = RemoteStatus(),
    val link: LinkState = LinkState.STOPPED,
    val deviceName: String? = null,
    val batteryMv: Int? = null,
    val totalPackets: Long = 0,
    val lostPackets: Long = 0,
    val silentFrames: Long = 0,
    val bytesPerSec: Int = 0,
    val currentChunk: String? = null,
    val currentChunkSeconds: Double = 0.0,
    val message: String? = null,
    val pendingUploads: Int = 0,
    val uploadedThisSession: Int = 0,
    val localAsrRunning: String? = null,
    val localAsrError: String? = null,
    val textVersion: Int = 0,
    val lastWriteNanos: Long = 0,
    val liveLevel: Float? = null,
    val liveSpeech: Boolean = false,
    val liveUnavailable: Boolean = false,
)

/** 服务和界面之间共享的状态 */
object AppState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private var remoteUpdatedAt = System.nanoTime()

    @Synchronized
    fun updateRemote(f: (RemoteStatus) -> RemoteStatus) {
        val now = System.nanoTime()
        val elapsed = (now - remoteUpdatedAt) / 1e9
        _status.update { it.copy(remote = f(it.remote.aged(elapsed))) }
        remoteUpdatedAt = now
    }

    fun update(f: (Status) -> Status) = _status.update(f)
}
