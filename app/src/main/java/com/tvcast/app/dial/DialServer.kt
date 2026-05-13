package com.tvcast.app.dial

import android.util.Log
import com.tvcast.app.event.CastEvent
import com.tvcast.app.event.CastEventBus
import com.tvcast.app.util.NetworkUtils
import com.tvcast.app.util.xmlEscape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * DIAL REST endpoint. Handles:
 *   GET    /dd.xml                  — DIAL device description (echoes Application-URL)
 *   GET    /apps/<App>              — current state of named app (running/stopped)
 *   POST   /apps/<App>              — launch named app with body payload (e.g. `v=dQw4w9WgXcQ`)
 *   DELETE /apps/<App>/run          — stop the named app instance
 *
 * Known apps are mapped to package launch intents via [CastEvent.LaunchApp]; the receiver
 * (CastService / MainActivity) decides whether to deep-link into YouTube/Netflix or fall back to
 * a generic ExoPlayer player.
 */
class DialServer(
    private val httpPort: Int,
    private val friendlyName: () -> String,
    private val baseUrl: () -> String,
) {
    companion object {
        private const val TAG = "DialServer"
        // Apps we advertise as "installed" — the TV will report these to phones for the launch UI.
        private val KNOWN_APPS = listOf("YouTube", "Netflix", "Spotify", "AmazonInstantVideo")
        private val APP_PATH_REGEX = Regex("^/apps/([A-Za-z0-9_]+)(?:/run)?/?$")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val running = ConcurrentHashMap<String, String>() // appName → instance URL fragment

    fun start() {
        scope.launch {
            try {
                val s = ServerSocket(httpPort)
                serverSocket = s
                while (scope.isActive) {
                    val client = try { s.accept() } catch (_: Exception) { break }
                    scope.launch { handleClient(client) }
                }
            } catch (e: Exception) { Log.e(TAG, "DIAL listener failed", e) }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val req = readRequest(input) ?: return
            Log.d(TAG, "${req.method} ${req.uri}")
            route(req, out)
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "DIAL client error", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private data class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: ByteArray)

    private fun readRequest(input: BufferedInputStream): Request? {
        val statusLine = readLine(input) ?: return null
        if (statusLine.isEmpty()) return null
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 3) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) readExactly(input, len) else ByteArray(0)
        return Request(parts[0], parts[1], headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128); var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0 && prev == -1) null else buf.toString(Charsets.ISO_8859_1.name())
            if (prev == '\r'.code && b == '\n'.code) {
                val s = buf.toByteArray(); return String(s, 0, s.size - 1, Charsets.ISO_8859_1)
            }
            buf.write(b); prev = b
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n); var read = 0
        while (read < n) { val r = input.read(out, read, n - read); if (r < 0) break; read += r }
        return out
    }

    private fun route(req: Request, out: OutputStream) {
        val uri = req.uri.substringBefore('?')
        val appPath = APP_PATH_REGEX.find(uri)?.groupValues
        when {
            req.method == "GET" && uri == "/dd.xml" -> serveDd(out)
            req.method == "GET" && uri == "/apps" -> serveAppList(out)
            appPath != null && req.method == "GET" -> serveAppState(out, appPath[1])
            appPath != null && req.method == "POST" -> launchApp(out, appPath[1], req.body, req.headers)
            appPath != null && req.method == "DELETE" -> stopApp(out, appPath[1])
            else -> writeStatus(out, 404, "Not Found", emptyMap(), null)
        }
    }

    private fun serveDd(out: OutputStream) {
        val deviceId = "uuid:${NetworkUtils.stableUdn().removePrefix("uuid:")}"
        val body = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:r="urn:restful-tv-org:schemas:upnp-dd">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
    <friendlyName>${friendlyName().xmlEscape()}</friendlyName>
    <manufacturer>TvCast</manufacturer>
    <modelName>TvCast</modelName>
    <UDN>$deviceId</UDN>
  </device>
</root>""".toByteArray(Charsets.UTF_8)
        writeStatus(out, 200, "OK", mapOf(
            "Content-Type" to "application/xml",
            "Application-URL" to "${baseUrl()}/apps/",
            "Cache-Control" to "max-age=1800",
        ), body)
    }

    private fun serveAppList(out: OutputStream) {
        val body = buildString {
            append("<?xml version=\"1.0\"?>")
            append("<service xmlns=\"urn:dial-multiscreen-org:schemas:dial\">")
            for (app in KNOWN_APPS) append("<app><name>$app</name><state>stopped</state></app>")
            append("</service>")
        }.toByteArray(Charsets.UTF_8)
        writeStatus(out, 200, "OK", mapOf("Content-Type" to "application/xml"), body)
    }

    private fun serveAppState(out: OutputStream, app: String) {
        val running = running.containsKey(app)
        val body = """<?xml version="1.0"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="2.1">
  <name>$app</name>
  <options allowStop="true"/>
  <state>${if (running) "running" else "stopped"}</state>
</service>""".toByteArray(Charsets.UTF_8)
        writeStatus(out, 200, "OK", mapOf("Content-Type" to "application/xml"), body)
    }

    private fun launchApp(out: OutputStream, app: String, body: ByteArray, headers: Map<String, String>) {
        val payload = String(body, Charsets.UTF_8)
        val params = parseFormBody(payload)
        val originHost = headers["origin"]
            ?.removePrefix("http://")
            ?.removePrefix("https://")
            ?.substringBefore('/')
            ?: headers["host"]
        Log.i(TAG, "DIAL launch app=$app params=$params from=$originHost")

        running[app] = "/apps/$app/run"
        CastEventBus.tryEmit(
            CastEvent.SenderConnected(CastEvent.Source.DIAL, "DIAL · $app${originHost?.let { " · $it" } ?: ""}")
        )
        CastEventBus.tryEmit(
            CastEvent.LaunchApp(
                source = CastEvent.Source.DIAL,
                app = app,
                params = params,
                senderHost = originHost,
            )
        )

        writeStatus(out, 201, "Created", mapOf(
            "Location" to "${baseUrl()}/apps/$app/run",
        ), null)
    }

    private fun stopApp(out: OutputStream, app: String) {
        running.remove(app)
        CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.DIAL, CastEvent.ControlAction.STOP))
        CastEventBus.tryEmit(CastEvent.SenderDisconnected(CastEvent.Source.DIAL))
        writeStatus(out, 200, "OK", emptyMap(), null)
    }

    private fun parseFormBody(text: String): Map<String, String> = text
        .split('&')
        .mapNotNull {
            val eq = it.indexOf('='); if (eq <= 0) return@mapNotNull null
            val k = runCatching { URLDecoder.decode(it.substring(0, eq), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val v = runCatching { URLDecoder.decode(it.substring(eq + 1), "UTF-8") }.getOrNull().orEmpty()
            k to v
        }
        .toMap()

    private fun writeStatus(
        out: OutputStream, code: Int, reason: String, headers: Map<String, String>, body: ByteArray?,
    ) {
        val writer = PrintWriter(out, false, Charsets.ISO_8859_1)
        writer.print("HTTP/1.1 $code $reason\r\n")
        for ((k, v) in headers) writer.print("$k: $v\r\n")
        val len = body?.size ?: 0
        if (!headers.containsKey("Content-Length")) writer.print("Content-Length: $len\r\n")
        if (!headers.containsKey("Connection")) writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
        if (body != null && body.isNotEmpty()) {
            try { out.write(body) } catch (_: IOException) {}
        }
    }
}
