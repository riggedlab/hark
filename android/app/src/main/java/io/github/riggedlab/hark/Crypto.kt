package io.github.riggedlab.hark

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** v2 协议：槽位和条目 ID 参与认证，密文不能跨用途调包。 */
object Crypto {
    val audioName = Regex("[0-9]{8}-[0-9]{6}\\.opus")
    private val random = SecureRandom()

    fun decodeKey(value: String): ByteArray {
        val s = value.trim()
        val key = if (Regex("[0-9a-fA-F]{64}").matches(s))
            s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        else Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/') + "=".repeat((4 - s.length % 4) % 4))
        require(key.size == 32) { "加密密钥必须是 32 字节" }
        return key
    }

    private fun checkKey(key: ByteArray) = require(key.size == 32) { "加密密钥必须是 32 字节" }

    fun itemId(key: ByteArray, name: String): String {
        checkKey(key)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("diting:item:$name".toByteArray(Charsets.UTF_8)).take(16)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray,
                nonce: ByteArray = ByteArray(12).also { random.nextBytes(it) }): ByteArray {
        checkKey(key)
        require(nonce.size == 12) { "nonce 必须是 12 字节" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return byteArrayOf(1) + nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, packet: ByteArray, aad: ByteArray): ByteArray {
        checkKey(key)
        require(packet.size >= 29 && packet[0] == 1.toByte()) { "密文包长度或版本错误" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(packet.copyOfRange(13, packet.size))
    }

    fun packAudio(key: ByteArray, name: String, opusBytes: ByteArray): ByteArray {
        require(audioName.matches(name)) { "录音文件名格式错误" }
        return encrypt(key, name.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + opusBytes,
            "audio:${itemId(key, name)}".toByteArray(Charsets.UTF_8))
    }

    fun unpackAudio(key: ByteArray, itemId: String, packet: ByteArray): Pair<String, ByteArray> {
        val plain = decrypt(key, packet, "audio:$itemId".toByteArray(Charsets.UTF_8))
        val end = plain.indexOf(0)
        require(end >= 0) { "音频包缺少文件名" }
        val name = plain.copyOfRange(0, end).toString(Charsets.UTF_8)
        require(audioName.matches(name) && itemId(key, name) == itemId) { "录音文件名或条目 ID 不匹配" }
        return name to plain.copyOfRange(end + 1, plain.size)
    }
}
