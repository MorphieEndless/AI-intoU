package com.yingti.app.relay

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RelayClientTest {
    @Test fun commandsExecuteInArrivalOrder() {
        val server = MockWebServer()
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(20)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                if (JSONObject(text).optString("type") == "phone_auth") {
                    socket.send("{\"type\":\"auth_ok\"}")
                    repeat(20) { socket.send("{\"type\":\"command\",\"index\":$it}") }
                }
            }
        }))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commands = object : RelayCommands {
            override suspend fun dispatch(command: JSONObject): JSONObject {
                delay(5)
                received.add(command.getInt("index"))
                done.countDown()
                return JSONObject().put("type", "command_ack").put("success", true)
            }
            override fun deviceList() = JSONObject()
        }
        val client = RelayClient(scope, commands) {}
        try {
            client.start(server.url("/").toString(), "test-credential")
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals((0 until 20).toList(), received.toList())
        } finally { client.stop(); scope.cancel(); server.shutdown() }
    }

    @Test fun stopDiscardsQueuedCommandsAndDoesNotCauseDisconnectFailsafe() {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socketReady = CompletableDeferred<WebSocket>()
        val gate = CompletableDeferred<Unit>()
        val firstStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val rejected = CountDownLatch(70)
        val processed = Collections.synchronizedList(mutableListOf<String>())
        val failures = AtomicInteger()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                val message = JSONObject(text)
                if (message.optString("type") == "phone_auth") socketReady.complete(socket)
                if (message.optString("type") == "command_ack" && !message.optBoolean("success")) rejected.countDown()
            }
        }))
        server.start()
        val commands = object : RelayCommands {
            override suspend fun dispatch(command: JSONObject): JSONObject {
                val type = command.getString("type")
                if (command.optInt("index", -1) == 0) { firstStarted.countDown(); gate.await() }
                processed.add(type)
                if (type == "stop") stopped.countDown()
                return JSONObject().put("type", "command_ack").put("success", true)
            }
            override fun deviceList() = JSONObject()
        }
        val client = RelayClient(scope, commands) { failures.incrementAndGet(); Unit }
        try {
            client.start(server.url("/").toString(), "test-credential")
            val socket = runBlocking { withTimeout(5_000) { socketReady.await() } }
            socket.send("{\"type\":\"command\",\"index\":0}")
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            repeat(70) { socket.send("{\"type\":\"command\",\"index\":${it + 1},\"request_id\":\"$it\"}") }
            socket.send("{\"type\":\"stop\"}")
            assertTrue(rejected.await(5, TimeUnit.SECONDS))
            gate.complete(Unit)
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("command", "stop"), processed.toList())
            client.stop()
            Thread.sleep(100)
            assertEquals(0, failures.get())
        } finally { client.stop(); scope.cancel(); server.shutdown() }
    }

    @Test fun disconnectCancelsOldWorkerBeforeFailsafe() {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socketReady = CompletableDeferred<WebSocket>()
        val firstStarted = CountDownLatch(1)
        val cancelled = CompletableDeferred<Unit>()
        val failsafe = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                if (JSONObject(text).optString("type") == "phone_auth") socketReady.complete(socket)
            }
        }))
        server.start()
        val commands = object : RelayCommands {
            override suspend fun dispatch(command: JSONObject): JSONObject {
                firstStarted.countDown()
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            override fun deviceList() = JSONObject()
        }
        val client = RelayClient(scope, commands) {
            assertTrue(cancelled.isCompleted)
            failsafe.countDown()
        }
        try {
            client.start(server.url("/").toString(), "test-credential")
            val socket = runBlocking { withTimeout(5_000) { socketReady.await() } }
            socket.send("{\"type\":\"command\"}")
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            socket.close(1001, "test disconnect")
            assertTrue(failsafe.await(5, TimeUnit.SECONDS))
        } finally { client.stop(); scope.cancel(); server.shutdown() }
    }
}
