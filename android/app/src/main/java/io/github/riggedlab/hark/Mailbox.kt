package io.github.riggedlab.hark

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** 打开到信箱的连接。用 IP 访问时只信任内置的服务器证书；用域名时走系统证书。 */
object Mailbox {
    private val pinned: SSLSocketFactory? by lazy {
        val pem = BuildConfig.MAILBOX_CERT
        if (pem.isBlank()) return@lazy null
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray()))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("mailbox", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    private val ipHost = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && ipHost.matches(conn.url.host)) pinned?.let { conn.sslSocketFactory = it }
        return conn
    }
}
