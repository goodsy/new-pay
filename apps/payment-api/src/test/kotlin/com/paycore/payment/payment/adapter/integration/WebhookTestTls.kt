package com.paycore.payment.payment.adapter.integration

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * 시험 전용 TLS 재료(WEBHOOK-001 검토 m-5). 시험 실행 때 JDK `keytool`로 일회용 시험 CA·CA가 서명한 leaf·자체 서명 leaf를 만든다(저장소에 키를 두지 않는다 — 운영 키 아님).
 * 비밀번호는 일회용 시험 값이다.
 */
class WebhookTestTls private constructor(private val dir: File) {

    val trustStore: File get() = File(dir, "trust.p12")

    /** 시험 CA만 믿는 SSLContext(전송 adapter의 TEST trust). */
    fun clientContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        val ks = load(trustStore)
        init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers, null)
    }

    /** [alias] leaf로 응답하는 서버 SSLContext(`ca-leaf-<dns>` 또는 `self-<dns>`). */
    fun serverContext(alias: String): SSLContext = SSLContext.getInstance("TLS").apply {
        val ks = load(File(dir, "$alias.p12"))
        init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASSWORD) }.keyManagers, null, null)
    }

    fun close() = dir.deleteRecursively()

    private fun load(f: File): KeyStore = KeyStore.getInstance("PKCS12").apply { FileInputStream(f).use { load(it, PASSWORD) } }

    companion object {
        private val PASSWORD = "test-only-webhook-tls".toCharArray()

        /** CA 하나와 [caLeaves] DNS 이름의 CA 서명 leaf, [selfSigned] DNS 이름의 자체 서명 leaf를 만든다. */
        fun create(caLeaves: List<String>, selfSigned: List<String>): WebhookTestTls {
            val dir = Files.createTempDirectory("webhook-tls").toFile()
            val p = String(PASSWORD)
            fun keytool(vararg args: String, input: File? = null, output: File? = null) {
                val cmd = listOf(File(System.getProperty("java.home"), "bin/keytool").path) + args
                val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(output == null)
                input?.let { pb.redirectInput(it) }
                output?.let { pb.redirectOutput(it) }
                val proc = pb.start()
                check(proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0) { "keytool ${args.first()} failed: " + (if (output == null) proc.inputStream.bufferedReader().readText() else "") }
            }
            keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=PayCore Webhook Test CA", "-ext", "bc:c", "-validity", "2",
                "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", p, "-keypass", p)
            keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-storepass", p, "-file", "ca.pem")
            keytool("-importcert", "-noprompt", "-alias", "ca", "-file", "ca.pem", "-keystore", "trust.p12", "-storetype", "PKCS12", "-storepass", p)
            caLeaves.forEach { dns ->
                val alias = "ca-leaf-$dns"
                keytool("-genkeypair", "-alias", "leaf", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=$dns", "-validity", "2",
                    "-keystore", "$alias.p12", "-storetype", "PKCS12", "-storepass", p, "-keypass", p)
                keytool("-certreq", "-alias", "leaf", "-keystore", "$alias.p12", "-storepass", p, "-file", "$alias.csr")
                keytool("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", p, "-infile", "$alias.csr", "-outfile", "$alias.pem", "-rfc",
                    "-ext", "san=dns:$dns", "-ext", "eku=serverAuth", "-validity", "2")
                keytool("-importcert", "-noprompt", "-alias", "ca", "-file", "ca.pem", "-keystore", "$alias.p12", "-storepass", p)
                keytool("-importcert", "-alias", "leaf", "-file", "$alias.pem", "-keystore", "$alias.p12", "-storepass", p)
            }
            selfSigned.forEach { dns ->
                keytool("-genkeypair", "-alias", "leaf", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=$dns", "-ext", "san=dns:$dns", "-validity", "2",
                    "-keystore", "self-$dns.p12", "-storetype", "PKCS12", "-storepass", p, "-keypass", p)
            }
            return WebhookTestTls(dir)
        }
    }
}
