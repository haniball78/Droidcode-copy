package com.droiddeck.launcher.core.privileged

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DaemonAuthTest {
    private val token = "batch-d-test-token"
    private var port = 0
    private var serverThread: Thread? = null

    @Before
    fun startDaemon() {
        port = ServerSocket(0).use { it.localPort }
        System.setProperty("droiddeck.daemon.test.port", port.toString())
        serverThread = Thread({ DroidDeckPrivilegedServer.main(arrayOf(token)) }, "daemon-auth-test").apply {
            isDaemon = true
            start()
        }
        val deadline = System.currentTimeMillis() + 10000L
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val reply = sendLines("PING")
                if (reply != null && reply.startsWith("PONG")) return
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(100L)
        }
        fail("daemon did not answer PING on port $port: $last")
    }

    @After
    fun stopDaemon() {
        DroidDeckPrivilegedServer.stopForTest()
        serverThread?.join(5000L)
        System.clearProperty("droiddeck.daemon.test.port")
    }

    private fun sendLines(vararg lines: String): String? {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            for (line in lines) writer.println(line)
            return reader.readLine()
        }
    }

    @Test
    fun tokenlessExecIsUnauthorized() {
        val exec = Base64.getEncoder().encodeToString("echo hi".toByteArray(StandardCharsets.UTF_8))
        assertEquals("UNAUTHORIZED", sendLines("EXEC:$exec"))
    }

    @Test
    fun wrongTokenIsUnauthorized() {
        assertEquals("UNAUTHORIZED", sendLines("AUTH:wrong-token"))
    }

    @Test
    fun correctTokenPassesAuthGate() {
        val exec = Base64.getEncoder().encodeToString("echo hi".toByteArray(StandardCharsets.UTF_8))
        val reply = sendLines("AUTH:$token", "EXEC:$exec")
        assertTrue("expected an EXIT reply, got: $reply", reply != null && reply.startsWith("EXIT:"))
    }
}
