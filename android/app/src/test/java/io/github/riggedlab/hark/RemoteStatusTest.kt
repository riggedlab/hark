package io.github.riggedlab.hark

import org.junit.Assert.*
import org.junit.Test

class RemoteStatusTest {
    @Test fun heartbeatExpiresWithoutAnotherNetworkResponse() {
        val status = RemoteStatus(workerOnline = true, workerAgeSec = 40.0,
            workerPhase = "transcribing", workerPhaseElapsedSec = 10.0)
        assertTrue(status.aged(5.0).workerOnline)
        val stale = status.aged(6.0)
        assertFalse(stale.workerOnline)
        assertEquals(46.0, stale.workerAgeSec!!, 0.01)
        assertEquals(16.0, stale.workerPhaseElapsedSec, 0.01)
        assertFalse(RemoteStatus().aged(60.0).workerOnline)
        assertFalse(RemoteStatus(workerAgeSec = 0.0).aged(1.0).workerOnline)
    }
}
