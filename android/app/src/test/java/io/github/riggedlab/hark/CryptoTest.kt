package io.github.riggedlab.hark

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoTest {
    private val key = Crypto.decodeKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
    private val name = "20261112-123000.opus"
    private val aad = "text:3a2e641c9b0f".toByteArray()
    private val plain = "你好，谛听".toByteArray()
    private val vector = "010102030405060708090a0b0ce157fa3049291f3ac04ad3dcf583461f34b5c5b47754ab690856ab26f8491f"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun fixedVectorMatchesPython() {
        assertEquals("dccc04d892bab8cd0700141f1784459a", Crypto.itemId(key, name))
        assertArrayEquals(plain, Crypto.decrypt(key, vector, aad))
        assertArrayEquals(vector, Crypto.encrypt(key, plain, aad, ByteArray(12) { (it + 1).toByte() }))
        assertArrayEquals(plain, Crypto.decrypt(key, Crypto.encrypt(key, plain, aad), aad))
    }

    @Test fun acceptsHexStandardAndUrlBase64WithoutPadding() {
        val bytes = ByteArray(32) { it.toByte() }
        assertArrayEquals(bytes, key)
        assertArrayEquals(bytes, Crypto.decodeKey("  " + bytes.joinToString("") { "%02x".format(it) } + "\n"))
        val special = ByteArray(32) { 0xfb.toByte() }
        assertArrayEquals(special, Crypto.decodeKey(java.util.Base64.getEncoder().encodeToString(special)))
        assertArrayEquals(special, Crypto.decodeKey(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(special)))
    }

    @Test fun rejectsTamperingWrongKeyAndWrongAad() {
        val tampered = vector.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { Crypto.decrypt(key, tampered, aad) }
        assertThrows(AEADBadTagException::class.java) { Crypto.decrypt(key, vector, "slot:worker_status".toByteArray()) }
        assertThrows(AEADBadTagException::class.java) { Crypto.decrypt(ByteArray(32), vector, aad) }
    }

    @Test fun rejectsMalformedPacketsAndKeys() {
        for (value in listOf("", "abc", "!!!!", "00".repeat(16), "AA=="))
            assertThrows(IllegalArgumentException::class.java) { Crypto.decodeKey(value) }
        assertThrows(IllegalArgumentException::class.java) { Crypto.encrypt(ByteArray(16), plain, aad) }
        assertThrows(IllegalArgumentException::class.java) { Crypto.decrypt(key, ByteArray(28), aad) }
        assertThrows(IllegalArgumentException::class.java) { Crypto.decrypt(key, vector.copyOf().apply { this[0] = 2 }, aad) }
    }

    @Test fun audioRoundTripAndIdBinding() {
        val audio = "OggS 中文音频".toByteArray()
        val packet = Crypto.packAudio(key, name, audio)
        val (actualName, actualAudio) = Crypto.unpackAudio(key, Crypto.itemId(key, name), packet)
        assertEquals(name, actualName)
        assertArrayEquals(audio, actualAudio)
        assertThrows(AEADBadTagException::class.java) { Crypto.unpackAudio(key, "0".repeat(32), packet) }
        assertThrows(IllegalArgumentException::class.java) { Crypto.packAudio(key, "../bad.opus", audio) }
    }
}
