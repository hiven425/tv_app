package com.tvcast.app.airplay

import android.util.Log
import com.tvcast.app.event.CastEvent
import com.tvcast.app.event.CastEventBus
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
import java.util.regex.Pattern

/**
 * AirPlay HTTP + RTSP server on port 7000.
 *
 * Multiplexes two protocols on the same socket:
 *  - AirPlay video URL push   — HTTP/1.1 (POST /play, /scrub, /rate, /stop, PUT /photo, GET /playback-info)
 *  - AirPlay audio (RAOP)     — RTSP/1.0 (ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, SET_PARAMETER)
 *
 * Bodies are read as raw bytes via DataInputStream so we can handle binary payloads (binary plists,
 * JPEG photos, DMAP-tagged metadata blobs, cover-art images).
 */
class AirPlayServer(
    private val httpPort: Int,
    private val raopAudio: RaopAudioReceiver,
) {
    companion object {
        private const val TAG = "AirPlayServer"
        // 0x527FFFF7 = AirPlay 1 audio + video, no enc
        private const val AIRPLAY_FEATURES: Long = 0x527FFFF7L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    @Volatile private var currentSession: String? = null
    @Volatile private var lastSender: String = "AirPlay"

    fun start() {
        scope.launch {
            try {
                val s = ServerSocket(httpPort)
                serverSocket = s
                Log.i(TAG, "AirPlay listening on $httpPort")
                while (scope.isActive) {
                    val client = try { s.accept() } catch (_: Exception) { break }
                    scope.launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AirPlay listener failed", e)
            }
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
            while (!client.isClosed) {
                val req = readRequest(input) ?: break
                Log.d(TAG, "${req.method} ${req.uri} (proto=${req.protocol}, body=${req.body.size})")
                routeRequest(req, out)
                out.flush()
                if (req.headers["connection"]?.lowercase() == "close") break
            }
        } catch (e: Exception) {
            Log.w(TAG, "AirPlay client error", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private data class Request(
        val method: String,
        val uri: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

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
            if (idx > 0) {
                headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)
        return Request(parts[0], parts[1], parts[2], headers, body)
    }

    /** Read one CRLF-terminated line from the stream, returning the line without the terminator. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0 && prev == -1) null else buf.toString(Charsets.ISO_8859_1.name())
            if (prev == '\r'.code && b == '\n'.code) {
                val s = buf.toByteArray()
                return String(s, 0, s.size - 1, Charsets.ISO_8859_1)
            }
            buf.write(b)
            prev = b
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) break
            read += r
        }
        return out
    }

    // ────────────────────── routing ──────────────────────

    private fun routeRequest(req: Request, out: OutputStream) {
        val isRtsp = req.protocol.startsWith("RTSP", ignoreCase = true)
        if (isRtsp) handleRtsp(req, out) else handleHttp(req, out)
    }

    // ────────────────────── AirPlay video (HTTP) ──────────────────────

    private fun handleHttp(req: Request, out: OutputStream) {
        val uri = req.uri.substringBefore('?').lowercase()
        when {
            req.method.equals("POST", true) && uri == "/reverse" -> {
                writeStatus(out, "HTTP/1.1", 200, "OK", emptyMap(), null)
            }
            // ─── AirPlay 2 introspection & pairing scaffolding (v1.0 will fill these in) ───
            (req.method.equals("GET", true) || req.method.equals("POST", true)) && uri == "/info" -> {
                serveAirPlay2Info(out)
            }
            req.method.equals("POST", true) && uri == "/auth-setup" -> {
                // iOS sends a 33-byte (curve25519 pubkey + flag) blob. We echo a 32-byte zero key
                // so the client treats us as "transient" auth — sufficient for non-mirroring use.
                val body = ByteArray(32 + 4)
                writeStatus(out, "HTTP/1.1", 200, "OK", mapOf("Content-Type" to "application/octet-stream"), body)
            }
            req.method.equals("POST", true) && (uri == "/pair-setup" || uri == "/pair-verify") -> {
                // Real implementation requires SRP/Curve25519 — declare not-implemented so iOS
                // falls back to AirPlay 1. 470 is what real Apple TVs return when un-paired.
                writeStatus(out, "HTTP/1.1", 470, "Connection Authorization Required", emptyMap(), null)
            }
            req.method.equals("POST", true) && uri == "/pair-pin-start" -> {
                writeStatus(out, "HTTP/1.1", 200, "OK", emptyMap(), null)
            }
            req.method.equals("POST", true) && uri == "/fp-setup" -> {
                // FairPlay handshake — we don't carry Apple's FP keys, so reply 501 to abort the
                // mirror attempt cleanly instead of timing out.
                writeStatus(out, "HTTP/1.1", 501, "Not Implemented", emptyMap(), null)
            }
            req.method.equals("GET", true) && uri == "/server-info" -> {
                val deviceId = com.tvcast.app.util.NetworkUtils.macHex().chunked(2).joinToString(":")
                val body = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
 <key>deviceid</key><string>$deviceId</string>
 <key>features</key><integer>$AIRPLAY_FEATURES</integer>
 <key>model</key><string>AppleTV3,2</string>
 <key>protovers</key><string>1.0</string>
 <key>srcvers</key><string>220.68</string>
 <key>vv</key><integer>2</integer>
</dict>
</plist>""".toByteArray(Charsets.UTF_8)
                writeStatus(out, "HTTP/1.1", 200, "OK", mapOf(
                    "Content-Type" to "text/x-apple-plist+xml",
                    "Content-Length" to body.size.toString(),
                ), body)
            }
            req.method.equals("GET", true) && uri == "/playback-info" -> {
                val body = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
 <key>duration</key><real>0</real>
 <key>position</key><real>0</real>
 <key>rate</key><real>1</real>
 <key>readyToPlay</key><true/>
</dict>
</plist>""".toByteArray(Charsets.UTF_8)
                writeStatus(out, "HTTP/1.1", 200, "OK", mapOf(
                    "Content-Type" to "text/x-apple-plist+xml",
                    "Content-Length" to body.size.toString(),
                ), body)
            }
            req.method.equals("POST", true) && uri == "/play" -> {
                val info = parsePlay(req)
                if (!info.url.isNullOrBlank()) {
                    val sender = req.headers["user-agent"] ?: "AirPlay"
                    lastSender = sender
                    CastEventBus.tryEmit(CastEvent.SenderConnected(CastEvent.Source.AIRPLAY, sender))
                    CastEventBus.tryEmit(
                        CastEvent.PlayMedia(
                            source = CastEvent.Source.AIRPLAY,
                            url = info.url,
                            mimeType = null,
                            title = null,
                            senderName = sender,
                            startPosition = info.startPosition ?: 0.0,
                        )
                    )
                }
                writeStatus(out, "HTTP/1.1", 200, "OK", emptyMap(), null)
            }
            req.method.equals("POST", true) && uri == "/scrub" -> {
                val target = req.uri.substringAfter("position=", "").substringBefore('&').toDoubleOrNull()
                CastEventBus.tryEmit(
                    CastEvent.Control(CastEvent.Source.AIRPLAY, CastEvent.ControlAction.SEEK, target)
                )
                writeStatus(out, "HTTP/1.1", 200, "OK", emptyMap(), null)
            }
            req.method.equals("POST", true) && uri == "/rate" -> {
                val rate = req.uri.substringAfter("value=", "1").substringBefore('&').toFloatOrNull() ?: 1f
                val action = if (rate > 0f) CastEvent.ControlAction.PLAY else CastEvent.ControlAction.PAUSE
                CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.AIRPLAY, action))
                writeStatus(out, "HTTP/1.1", 200, "OK", emptyMap(), null)
            }
            req.method.equals("POST", true) && uri == "/stop" -> {
                CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.AIRPLAY, CastEvent.ControlAction.STOP))
                CastEventBus.tryEmit(CastEvent.SenderDisconnected(CastEvent.Source.AIRPLAY))
                writeStatus(out, "HTTP/1.1", 200, "OK", emptyMap(), null)
            }
            req.method.equals("PUT", true) && uri == "/photo" -> {
                if (req.body.isNotEmpty()) {
                    val sender = req.headers["user-agent"] ?: "AirPlay"
                    lastSender = sender
                    CastEventBus.tryEmit(CastEvent.SenderConnected(CastEvent.Source.AIRPLAY, sender))
                    CastEventBus.tryEmit(
                        CastEvent.ShowPhoto(
                            source = CastEvent.Source.AIRPLAY,
                            bytes = req.body,
                            senderName = sender,
                            assetKey = req.headers["x-apple-assetkey"],
                            transition = req.headers["x-apple-transition"],
                        )
                    )
                }
                writeStatus(out, "HTTP/1.1", 200, "OK", emptyMap(), null)
            }
            else -> writeStatus(out, "HTTP/1.1", 404, "Not Found", emptyMap(), null)
        }
    }

    private data class PlayInfo(val url: String?, val startPosition: Double?)

    private fun parsePlay(req: Request): PlayInfo {
        val ct = req.headers["content-type"].orEmpty().lowercase()
        // Modern iOS sends binary plist.
        if (ct.contains("apple-binary-plist") || BinaryPlist.isBinaryPlist(req.body)) {
            return try {
                @Suppress("UNCHECKED_CAST")
                val root = BinaryPlist.parse(req.body) as? Map<String, Any?>
                val url = (root?.get("Content-Location") as? String)
                    ?: (root?.get("contentLocation") as? String)
                val pos = (root?.get("Start-Position") as? Number)?.toDouble()
                PlayInfo(url, pos)
            } catch (e: Exception) {
                Log.w(TAG, "binary plist /play parse failed, falling back to text", e)
                parsePlayText(req.body)
            }
        }
        return parsePlayText(req.body)
    }

    private fun parsePlayText(body: ByteArray): PlayInfo {
        val text = String(body, Charsets.UTF_8)
        if (text.contains("Content-Location:", ignoreCase = true)) {
            val url = text.lineSequence()
                .firstOrNull { it.trim().startsWith("Content-Location:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            val pos = text.lineSequence()
                .firstOrNull { it.trim().startsWith("Start-Position:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toDoubleOrNull()
            return PlayInfo(url, pos)
        }
        val match = Pattern.compile("https?://[^\\s<>\"]+").matcher(text)
        return PlayInfo(if (match.find()) match.group() else null, null)
    }

    private fun serveAirPlay2Info(out: OutputStream) {
        val deviceId = com.tvcast.app.util.NetworkUtils.macHex().chunked(2).joinToString(":")
        // pi (public id) is the device UUID; pk is the Ed25519 public key — we don't actually
        // pair so we send 32 zero bytes hex-encoded (the same approach RPiPlay uses pre-keygen).
        val pi = com.tvcast.app.util.NetworkUtils.stableUdn().removePrefix("uuid:")
        val pk = "0".repeat(64)
        val body = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
 <key>features</key><integer>$AIRPLAY_FEATURES</integer>
 <key>statusFlags</key><integer>4</integer>
 <key>deviceID</key><string>$deviceId</string>
 <key>pi</key><string>$pi</string>
 <key>pk</key><data>$pk</data>
 <key>name</key><string>${lastSender.xmlEscape()}</string>
 <key>model</key><string>AppleTV3,2</string>
 <key>protocolVersion</key><string>1.1</string>
 <key>sourceVersion</key><string>220.68</string>
 <key>macAddress</key><string>$deviceId</string>
 <key>audioFormats</key>
 <array>
  <dict>
   <key>type</key><integer>100</integer>
   <key>audioInputFormats</key><integer>67108860</integer>
   <key>audioOutputFormats</key><integer>67108860</integer>
  </dict>
 </array>
 <key>keepAliveLowPower</key><false/>
 <key>keepAliveSendStatsAsBody</key><false/>
</dict>
</plist>""".toByteArray(Charsets.UTF_8)
        writeStatus(out, "HTTP/1.1", 200, "OK", mapOf(
            "Content-Type" to "text/x-apple-plist+xml",
            "Content-Length" to body.size.toString(),
        ), body)
    }

    private fun String.xmlEscape(): String = this
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    // ────────────────────── AirPlay audio (RTSP) ──────────────────────

    private fun handleRtsp(req: Request, out: OutputStream) {
        val cseq = req.headers["cseq"] ?: "0"
        val session = req.headers["session"] ?: currentSession
        val baseHeaders = linkedMapOf(
            "CSeq" to cseq,
            "Server" to "AirTunes/220.68",
        )
        if (!session.isNullOrEmpty()) baseHeaders["Session"] = session

        when (req.method.uppercase()) {
            "OPTIONS" -> {
                baseHeaders["Public"] =
                    "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST"
                writeStatus(out, "RTSP/1.0", 200, "OK", baseHeaders, null)
            }
            "ANNOUNCE" -> {
                // SDP body carries codec/sample-rate info — parse `a=fmtp:<pt> ...` so we can hand
                // the ALAC config to RaopAudioReceiver for AppleLossless streams.
                val sdp = String(req.body, Charsets.UTF_8)
                val fmtp = sdp.lineSequence()
                    .firstOrNull { it.startsWith("a=fmtp:", ignoreCase = true) }
                if (fmtp != null) raopAudio.configureFromFmtp(fmtp.substringAfter(' '))
                writeStatus(out, "RTSP/1.0", 200, "OK", baseHeaders, null)
            }
            "SETUP" -> {
                currentSession = "1"
                baseHeaders["Session"] = "1"
                val transport = req.headers["transport"].orEmpty()
                val (dataPort, controlPort, timingPort) = raopAudio.allocatePorts()
                val resp = buildString {
                    append(transport)
                    if (!contains("server_port", ignoreCase = true)) append(";server_port=$dataPort")
                    if (!contains("control_port", ignoreCase = true)) append(";control_port=$controlPort")
                    if (!contains("timing_port", ignoreCase = true)) append(";timing_port=$timingPort")
                }
                baseHeaders["Transport"] = resp

                val sender = req.headers["user-agent"] ?: "AirPlay Audio"
                lastSender = sender
                CastEventBus.tryEmit(CastEvent.SenderConnected(CastEvent.Source.AIRPLAY, sender))
                writeStatus(out, "RTSP/1.0", 200, "OK", baseHeaders, null)
            }
            "RECORD" -> {
                baseHeaders["Audio-Latency"] = "11025"
                writeStatus(out, "RTSP/1.0", 200, "OK", baseHeaders, null)
                raopAudio.startPlayback()
            }
            "PAUSE", "FLUSH" -> {
                raopAudio.flush()
                writeStatus(out, "RTSP/1.0", 200, "OK", baseHeaders, null)
            }
            "TEARDOWN" -> {
                writeStatus(out, "RTSP/1.0", 200, "OK", baseHeaders, null)
                raopAudio.stop()
                CastEventBus.tryEmit(CastEvent.SenderDisconnected(CastEvent.Source.AIRPLAY))
                currentSession = null
            }
            "SET_PARAMETER" -> {
                handleSetParameter(req)
                writeStatus(out, "RTSP/1.0", 200, "OK", baseHeaders, null)
            }
            "GET_PARAMETER" -> writeStatus(out, "RTSP/1.0", 200, "OK", baseHeaders, null)
            else -> writeStatus(out, "RTSP/1.0", 501, "Not Implemented", baseHeaders, null)
        }
    }

    private fun handleSetParameter(req: Request) {
        val ct = req.headers["content-type"].orEmpty().lowercase()
        when {
            ct.startsWith("text/parameters") -> {
                val text = String(req.body, Charsets.UTF_8)
                for (line in text.lineSequence()) {
                    val kv = line.split(':', limit = 2).map { it.trim() }
                    if (kv.size != 2) continue
                    when (kv[0].lowercase()) {
                        "volume" -> kv[1].toFloatOrNull()?.let {
                            // AirPlay volume is in [-30, 0] dB or -144 for mute.
                            val level = if (it <= -144f) 0f else ((it + 30f) / 30f).coerceIn(0f, 1f)
                            CastEventBus.tryEmit(CastEvent.Volume(CastEvent.Source.AIRPLAY, level))
                        }
                        "progress" -> {
                            // progress: start/cur/end (rtp timestamps)
                            val parts = kv[1].split('/').mapNotNull { it.trim().toLongOrNull() }
                            if (parts.size == 3 && parts[2] > parts[0]) {
                                val durationSec = (parts[2] - parts[0]) / 44100.0
                                CastEventBus.tryEmit(
                                    CastEvent.Metadata(
                                        source = CastEvent.Source.AIRPLAY,
                                        durationSeconds = durationSec,
                                    )
                                )
                            }
                        }
                    }
                }
            }
            ct.startsWith("application/x-dmap-tagged") -> {
                val tags = DmapParser.parse(req.body)
                if (tags.isNotEmpty()) {
                    CastEventBus.tryEmit(
                        CastEvent.Metadata(
                            source = CastEvent.Source.AIRPLAY,
                            title = tags["minm"] as? String,
                            artist = tags["asar"] as? String,
                            album = tags["asal"] as? String,
                        )
                    )
                }
            }
            ct.startsWith("image/") -> {
                CastEventBus.tryEmit(
                    CastEvent.Metadata(source = CastEvent.Source.AIRPLAY, coverArt = req.body)
                )
            }
        }
    }

    private fun writeStatus(
        out: OutputStream,
        protocol: String,
        code: Int,
        reason: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ) {
        val writer = PrintWriter(out, false, Charsets.ISO_8859_1)
        writer.print("$protocol $code $reason\r\n")
        for ((k, v) in headers) writer.print("$k: $v\r\n")
        if (body != null) {
            if (!headers.containsKey("Content-Length")) writer.print("Content-Length: ${body.size}\r\n")
        } else {
            if (!headers.containsKey("Content-Length")) writer.print("Content-Length: 0\r\n")
        }
        writer.print("\r\n")
        writer.flush()
        if (body != null && body.isNotEmpty()) {
            try { out.write(body) } catch (_: IOException) {}
        }
    }
}
