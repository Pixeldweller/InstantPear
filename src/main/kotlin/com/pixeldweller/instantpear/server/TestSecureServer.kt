package com.pixeldweller.instantpear.server

import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import java.io.FileInputStream
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 9275
    val server = TestPearServer(port)

    val sslContext = createSelfSignedSSLContext()
    server.setWebSocketFactory(DefaultSSLWebSocketServerFactory(sslContext))

    server.start()
    println("===========================================")
    println("  InstantPear Secure Test Server")
    println("  Running on wss://localhost:$port")
    println("  (self-signed certificate)")
    println("===========================================")
    println("Press Ctrl+C to stop")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nShutting down...")
        server.stop(1000)
    })
}

private fun createSelfSignedSSLContext(): SSLContext {
    val keystoreFile = Files.createTempFile("instantpear", ".jks")
    val password = "instantpear"

    println("[*] Generating self-signed certificate...")

    val keytoolPath = findKeytool()
    val process = ProcessBuilder(
        keytoolPath, "-genkeypair",
        "-alias", "instantpear",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "365",
        "-keystore", keystoreFile.toString(),
        "-storepass", password,
        "-keypass", password,
        "-dname", "CN=localhost, OU=Test, O=InstantPear, C=US"
    ).redirectErrorStream(true).start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("keytool failed (exit $exitCode): $output")
    }

    println("[*] Certificate generated")

    val keyStore = KeyStore.getInstance("JKS")
    FileInputStream(keystoreFile.toFile()).use { keyStore.load(it, password.toCharArray()) }

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, password.toCharArray())

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(kmf.keyManagers, tmf.trustManagers, null)

    // Clean up temp keystore on exit
    keystoreFile.toFile().deleteOnExit()

    return sslContext
}

private fun findKeytool(): String {
    val javaHome = System.getProperty("java.home")
    val candidates = listOf(
        "$javaHome/bin/keytool",
        "$javaHome/bin/keytool.exe",
        "$javaHome/../bin/keytool",
        "$javaHome/../bin/keytool.exe"
    )
    for (path in candidates) {
        if (java.io.File(path).exists()) return path
    }
    // Fall back to PATH
    return "keytool"
}
