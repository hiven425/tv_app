package com.tvcast.app.dlna

import android.util.Log
import com.tvcast.app.event.CastEvent
import com.tvcast.app.event.CastEventBus
import com.tvcast.app.util.xmlEscape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.xml.sax.InputSource
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringReader
import java.net.ServerSocket
import java.net.Socket
import javax.xml.parsers.DocumentBuilderFactory

/**
 * UPnP MediaRenderer HTTP endpoint — implemented as a raw socket server so we can support
 * non-standard HTTP verbs (SUBSCRIBE / UNSUBSCRIBE / NOTIFY) used by UPnP GENA eventing.
 *
 * Endpoints:
 *  GET   /device.xml                       — device description
 *  GET   /AVTransport.xml                  — SCPD for AVTransport
 *  GET   /RenderingControl.xml             — SCPD for RenderingControl
 *  GET   /ConnectionManager.xml            — SCPD for ConnectionManager
 *  POST  /<service>/control                — SOAP actions
 *  SUBSCRIBE/UNSUBSCRIBE /<service>/event  — GENA event subscription
 */
class UpnpHttpServer(
    private val httpPort: Int,
    private val friendlyName: () -> String,
    private val udn: String,
    private val baseUrl: () -> String,
    private val state: RendererState,
) {
    companion object {
        private const val TAG = "UpnpHttpServer"
        private const val SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val URN_AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val URN_RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val URN_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val events = GenaEventDispatcher()

    fun start() {
        scope.launch {
            try {
                val s = ServerSocket(httpPort)
                serverSocket = s
                Log.i(TAG, "UPnP HTTP listening on $httpPort")
                while (scope.isActive) {
                    val client = try { s.accept() } catch (_: Exception) { break }
                    scope.launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UPnP listener failed", e)
            }
        }
    }

    fun stop() {
        runCatching { events.stop() }
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000   // close idle keep-alive connections after 15s
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            while (!client.isClosed) {
                val req = try { readRequest(input) } catch (_: java.net.SocketTimeoutException) {
                    break
                } ?: break
                Log.d(TAG, "${req.method} ${req.uri} from ${client.remoteSocketAddress}")
                route(req, out, client)
                out.flush()
                // Honor explicit "Connection: close"; otherwise reuse the connection.
                if (req.headers["connection"]?.lowercase() == "close") break
            }
        } catch (e: Exception) {
            Log.w(TAG, "UPnP client error", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private data class Request(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val remoteAddr: String,
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
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) readExactly(input, len) else ByteArray(0)
        return Request(parts[0], parts[1], headers, body, headers["host"] ?: "")
    }

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
            buf.write(b); prev = b
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n); var read = 0
        while (read < n) { val r = input.read(out, read, n - read); if (r < 0) break; read += r }
        return out
    }

    private fun route(req: Request, out: OutputStream, client: Socket) {
        val uri = req.uri.lowercase()
        val isHead = req.method == "HEAD"
        val method = if (isHead) "GET" else req.method
        when {
            method == "GET" && uri == "/device.xml" ->
                xml(out, UpnpDescriptors.deviceXml(friendlyName(), udn, baseUrl()), headOnly = isHead)
            method == "GET" && uri == "/avtransport.xml" -> xml(out, UpnpDescriptors.avTransportScpd, headOnly = isHead)
            method == "GET" && uri == "/renderingcontrol.xml" -> xml(out, UpnpDescriptors.renderingControlScpd, headOnly = isHead)
            method == "GET" && uri == "/connectionmanager.xml" -> xml(out, UpnpDescriptors.connectionManagerScpd, headOnly = isHead)
            method == "GET" && uri == "/icon.png" -> writeStatus(out, 200, "OK", mapOf("Content-Type" to "image/png"), if (isHead) null else ByteArray(0))
            method == "GET" && (uri == "/" || uri == "/web") -> serveWebUi(out, isHead, client)
            req.method == "POST" && uri == "/web/cast" -> handleWebCast(req, out, client)
            req.method == "POST" && uri.endsWith("/control") -> handleSoap(req, out, client)
            req.method == "SUBSCRIBE" -> handleSubscribe(req, out)
            req.method == "UNSUBSCRIBE" -> handleUnsubscribe(req, out)
            else -> writeStatus(out, 404, "Not Found", emptyMap(), null)
        }
    }

    private fun serveWebUi(out: OutputStream, headOnly: Boolean, client: Socket) {
        val name = friendlyName()
        val html = """<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${name.xmlEscape()} · 投屏</title>
<style>
  :root { color-scheme: dark; }
  body { font-family: -apple-system, system-ui, "Helvetica Neue", Arial, sans-serif;
    background: #0F1115; color: #FFFFFF; margin: 0; padding: 32px;
    display: flex; flex-direction: column; align-items: center; min-height: 100vh; }
  .card { background: #1A1D24; border-radius: 16px; padding: 32px; max-width: 560px; width: 100%; }
  h1 { font-size: 24px; margin: 0 0 8px; }
  p.sub { color: #B0B7C3; margin: 0 0 24px; font-size: 14px; }
  label { display: block; color: #B0B7C3; font-size: 13px; margin-bottom: 6px; }
  input[type=url], input[type=text] {
    width: 100%; box-sizing: border-box; padding: 12px 14px; font-size: 16px;
    background: #0F1115; color: #FFFFFF; border: 1px solid #2A2D34; border-radius: 8px; }
  input:focus { outline: none; border-color: #3DA9FC; }
  button { margin-top: 16px; width: 100%; padding: 12px 16px; font-size: 16px; font-weight: 600;
    background: #3DA9FC; color: #0F1115; border: none; border-radius: 8px; cursor: pointer; }
  button:hover { filter: brightness(1.1); }
  .ok { color: #34C759; margin-top: 12px; }
  .err { color: #FF453A; margin-top: 12px; }
  .examples { margin-top: 24px; font-size: 13px; color: #6A7280; }
  .examples code { color: #B0B7C3; }
</style>
</head><body>
<div class="card">
  <h1>${name.xmlEscape()}</h1>
  <p class="sub">粘贴任意视频 / 音频 / 图片 URL，按"投屏"在电视上播放。</p>
  <form id="f">
    <label for="url">媒体 URL</label>
    <input type="url" id="url" name="url" required placeholder="https://example.com/video.mp4" autofocus>
    <label for="title" style="margin-top:16px">可选标题</label>
    <input type="text" id="title" name="title" placeholder="留空使用文件名">
    <button type="submit">投屏到电视</button>
    <div id="msg"></div>
  </form>
  <div class="examples">
    支持 <code>.mp4</code> · <code>.mkv</code> · <code>.m3u8</code> · <code>.mpd</code> · <code>.mp3</code> · <code>.flac</code> · <code>.jpg</code> · <code>.png</code> 等
  </div>
</div>
<script>
document.getElementById('f').addEventListener('submit', async (e) => {
  e.preventDefault();
  const msg = document.getElementById('msg');
  msg.textContent = '推送中…'; msg.className = '';
  const data = new URLSearchParams();
  data.append('url', document.getElementById('url').value);
  data.append('title', document.getElementById('title').value);
  try {
    const r = await fetch('/web/cast', { method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: data.toString() });
    if (r.ok) { msg.textContent = '✓ 已投屏'; msg.className = 'ok'; }
    else { msg.textContent = '✗ 服务器返回 ' + r.status; msg.className = 'err'; }
  } catch (err) { msg.textContent = '✗ ' + err.message; msg.className = 'err'; }
});
</script>
</body></html>""".toByteArray(Charsets.UTF_8)
        writeStatus(out, 200, "OK", mapOf("Content-Type" to "text/html; charset=utf-8"),
            if (headOnly) null else html, declaredLength = html.size)
    }

    private fun handleWebCast(req: Request, out: OutputStream, client: Socket) {
        val params = String(req.body, Charsets.UTF_8).split('&')
            .mapNotNull {
                val eq = it.indexOf('='); if (eq <= 0) return@mapNotNull null
                runCatching {
                    val k = java.net.URLDecoder.decode(it.substring(0, eq), "UTF-8")
                    val v = java.net.URLDecoder.decode(it.substring(eq + 1), "UTF-8")
                    k to v
                }.getOrNull()
            }.toMap()
        val url = params["url"].orEmpty()
        if (url.isBlank()) {
            writeStatus(out, 400, "Bad Request", emptyMap(), "missing or malformed url".toByteArray())
            return
        }
        val sender = "WebUI · ${client.remoteSocketAddress}"
        state.setCurrent(url, MetadataParser.Meta(title = params["title"], raw = ""))
        CastEventBus.tryEmit(CastEvent.SenderConnected(CastEvent.Source.WEBUI, sender))
        CastEventBus.tryEmit(
            CastEvent.PlayMedia(
                source = CastEvent.Source.WEBUI,
                url = url,
                mimeType = null,
                title = params["title"]?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/'),
                senderName = sender,
            )
        )
        events.notifyAv(state)
        writeStatus(out, 200, "OK", emptyMap(), "ok".toByteArray())
    }

    // ────────────────────── SOAP ──────────────────────

    private fun handleSoap(req: Request, out: OutputStream, client: Socket) {
        val soapAction = req.headers["soapaction"]?.trim('"')?.substringAfterLast('#')
            ?: return run { soapFault(out, "Missing SOAPACTION") }
        try {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(InputSource(StringReader(String(req.body, Charsets.UTF_8))))
            val args = extractActionArgs(doc, soapAction)
            Log.d(TAG, "SOAP $soapAction args=$args")
            when (req.uri.lowercase()) {
                "/avtransport/control" -> handleAvTransport(out, client, soapAction, args)
                "/renderingcontrol/control" -> handleRenderingControl(out, soapAction, args)
                "/connectionmanager/control" -> handleConnectionManager(out, soapAction)
                else -> soapFault(out, "Unknown control endpoint")
            }
        } catch (e: Exception) {
            Log.w(TAG, "SOAP parse failed", e)
            soapFault(out, "Bad SOAP body")
        }
    }

    private fun handleAvTransport(out: OutputStream, client: Socket, action: String, args: Map<String, String>) {
        fun ok(act: String, outArgs: Map<String, String>) = soapOk(out, URN_AVT, act, outArgs)
        when (action) {
            "SetAVTransportURI" -> {
                val uri = args["CurrentURI"].orEmpty()
                val meta = MetadataParser.parse(args["CurrentURIMetaData"].orEmpty())
                state.setCurrent(uri, meta)
                val sender = "DLNA · ${client.remoteSocketAddress}"
                CastEventBus.tryEmit(CastEvent.SenderConnected(CastEvent.Source.DLNA, sender))
                CastEventBus.tryEmit(
                    CastEvent.PlayMedia(
                        source = CastEvent.Source.DLNA,
                        url = uri,
                        mimeType = meta.mime,
                        title = meta.title,
                        senderName = sender,
                        subtitles = meta.subtitles.map {
                            CastEvent.Subtitle(it.url, it.mime, it.language)
                        },
                    )
                )
                events.notifyAv(state)
                ok("SetAVTransportURI", emptyMap())
            }
            "SetNextAVTransportURI" -> {
                val uri = args["NextURI"].orEmpty()
                val meta = MetadataParser.parse(args["NextURIMetaData"].orEmpty())
                state.setNext(uri, meta)
                ok("SetNextAVTransportURI", emptyMap())
            }
            "Play" -> { state.transportState = "PLAYING"; CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.PLAY)); events.notifyAv(state); ok("Play", emptyMap()) }
            "Pause" -> { state.transportState = "PAUSED_PLAYBACK"; CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.PAUSE)); events.notifyAv(state); ok("Pause", emptyMap()) }
            "Stop" -> {
                state.transportState = "STOPPED"
                CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.STOP))
                CastEventBus.tryEmit(CastEvent.SenderDisconnected(CastEvent.Source.DLNA))
                events.notifyAv(state); ok("Stop", emptyMap())
            }
            "Seek" -> {
                val target = args["Target"].orEmpty()
                state.seekTarget = target
                val secs = parseTimeToSeconds(target)
                CastEventBus.tryEmit(CastEvent.Control(CastEvent.Source.DLNA, CastEvent.ControlAction.SEEK, secs))
                ok("Seek", emptyMap())
            }
            "GetPositionInfo" -> {
                val posMs = CastEventBus.positionMs.value
                val durMs = CastEventBus.durationMs.value.takeIf { it > 0 } ?: 0L
                val posStr = formatHms(posMs)
                val durStr = formatHms(durMs)
                state.position = posStr
                if (durMs > 0) state.duration = durStr
                ok("GetPositionInfo", mapOf(
                    "Track" to "1",
                    "TrackDuration" to durStr,
                    "TrackMetaData" to state.metadataXml,
                    "TrackURI" to state.currentUri,
                    "RelTime" to posStr,
                    "AbsTime" to posStr,
                    "RelCount" to "0",
                    "AbsCount" to "0",
                ))
            }
            "GetTransportInfo" -> ok("GetTransportInfo", mapOf(
                "CurrentTransportState" to state.transportState,
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1",
            ))
            "GetMediaInfo" -> {
                val durStr = formatHms(CastEventBus.durationMs.value)
                if (CastEventBus.durationMs.value > 0) state.duration = durStr
                ok("GetMediaInfo", mapOf(
                    "NrTracks" to "1",
                    "MediaDuration" to durStr,
                    "CurrentURI" to state.currentUri,
                    "CurrentURIMetaData" to state.metadataXml,
                    "NextURI" to state.nextUri,
                    "NextURIMetaData" to state.nextMetadataXml,
                    "PlayMedium" to "NETWORK",
                    "RecordMedium" to "NOT_IMPLEMENTED",
                    "WriteStatus" to "NOT_IMPLEMENTED",
                ))
            }
            else -> soapFault(out, "Unsupported action: $action")
        }
    }

    private fun handleRenderingControl(out: OutputStream, action: String, args: Map<String, String>) {
        fun ok(act: String, outArgs: Map<String, String>) = soapOk(out, URN_RCS, act, outArgs)
        when (action) {
            "GetVolume" -> ok("GetVolume", mapOf("CurrentVolume" to state.volume.toString()))
            "SetVolume" -> {
                state.volume = args["DesiredVolume"]?.toIntOrNull()?.coerceIn(0, 100) ?: state.volume
                CastEventBus.tryEmit(CastEvent.Volume(CastEvent.Source.DLNA, state.volume / 100f))
                ok("SetVolume", emptyMap())
            }
            "GetMute" -> ok("GetMute", mapOf("CurrentMute" to if (state.muted) "1" else "0"))
            "SetMute" -> {
                state.muted = (args["DesiredMute"] == "1" || args["DesiredMute"]?.equals("true", true) == true)
                ok("SetMute", emptyMap())
            }
            else -> soapFault(out, "Unsupported action: $action")
        }
    }

    private fun handleConnectionManager(out: OutputStream, action: String) {
        when (action) {
            "GetProtocolInfo" -> soapOk(out, URN_CM, "GetProtocolInfo", mapOf(
                "Source" to "",
                "Sink" to UpnpDescriptors.SINK_PROTOCOL_INFO,
            ))
            else -> soapFault(out, "Unsupported action: $action")
        }
    }

    private fun extractActionArgs(doc: org.w3c.dom.Document, actionName: String): Map<String, String> {
        val body = doc.getElementsByTagNameNS(SOAP_ENV, "Body").item(0) ?: return emptyMap()
        val actionNode = (0 until body.childNodes.length).asSequence()
            .map { body.childNodes.item(it) }
            .firstOrNull { it.localName == actionName }
            ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        val children = actionNode.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            val name = node.localName ?: node.nodeName
            if (!name.isNullOrEmpty()) out[name] = node.textContent ?: ""
        }
        return out
    }

    private fun parseTimeToSeconds(t: String): Double? {
        // HH:MM:SS(.fff)
        val parts = t.split(':')
        if (parts.size != 3) return t.toDoubleOrNull()
        return try {
            parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()
        } catch (_: Exception) { null }
    }

    private fun formatHms(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    // ────────────────────── GENA ──────────────────────

    private fun handleSubscribe(req: Request, out: OutputStream) {
        val service = req.uri.lowercase().removeSuffix("/event").removePrefix("/")
        val callback = req.headers["callback"].orEmpty()
            .substringAfter('<', "")
            .substringBefore('>', "")
        val sid = events.subscribe(service, callback)
        val headers = linkedMapOf(
            "SID" to sid,
            "TIMEOUT" to "Second-1800",
            "Server" to "Android/9.0 UPnP/1.0 TvCast/0.2",
        )
        writeStatus(out, 200, "OK", headers, ByteArray(0))
        events.sendInitial(sid, service, state)
    }

    private fun handleUnsubscribe(req: Request, out: OutputStream) {
        val sid = req.headers["sid"].orEmpty()
        events.unsubscribe(sid)
        writeStatus(out, 200, "OK", emptyMap(), ByteArray(0))
    }

    // ────────────────────── HTTP write helpers ──────────────────────

    private fun xml(out: OutputStream, content: String, headOnly: Boolean = false) {
        val body = content.toByteArray(Charsets.UTF_8)
        writeStatus(out, 200, "OK", mapOf("Content-Type" to "text/xml; charset=\"utf-8\""), if (headOnly) null else body, declaredLength = body.size)
    }

    private fun soapOk(out: OutputStream, serviceUrn: String, action: String, outArgs: Map<String, String>) {
        val args = outArgs.entries.joinToString("") { (k, v) -> "<$k>${v.xmlEscape()}</$k>" }
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="$SOAP_ENV" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
 <s:Body>
  <u:${action}Response xmlns:u="$serviceUrn">$args</u:${action}Response>
 </s:Body>
</s:Envelope>""".toByteArray(Charsets.UTF_8)
        writeStatus(out, 200, "OK", mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "EXT" to ""), body)
    }

    private fun soapFault(out: OutputStream, reason: String) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="$SOAP_ENV" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
 <s:Body><s:Fault>
  <faultcode>s:Client</faultcode>
  <faultstring>UPnPError</faultstring>
  <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
   <errorCode>402</errorCode><errorDescription>${reason.xmlEscape()}</errorDescription>
  </UPnPError></detail>
 </s:Fault></s:Body>
</s:Envelope>""".toByteArray(Charsets.UTF_8)
        writeStatus(out, 500, "Internal Server Error", mapOf("Content-Type" to "text/xml; charset=\"utf-8\""), body)
    }

    private fun writeStatus(
        out: OutputStream,
        code: Int,
        reason: String,
        headers: Map<String, String>,
        body: ByteArray?,
        declaredLength: Int? = null,
    ) {
        val writer = PrintWriter(out, false, Charsets.ISO_8859_1)
        writer.print("HTTP/1.1 $code $reason\r\n")
        for ((k, v) in headers) writer.print("$k: $v\r\n")
        val len = declaredLength ?: body?.size ?: 0
        if (!headers.containsKey("Content-Length")) writer.print("Content-Length: $len\r\n")
        if (!headers.containsKey("Connection")) writer.print("Connection: keep-alive\r\n")
        writer.print("\r\n")
        writer.flush()
        if (body != null && body.isNotEmpty()) {
            try { out.write(body) } catch (_: IOException) {}
        }
    }
}

/** Mutable state shared between the SOAP server and the player. */
class RendererState {
    @Volatile var currentUri: String = ""
    @Volatile var metadataXml: String = ""
    @Volatile var nextUri: String = ""
    @Volatile var nextMetadataXml: String = ""
    @Volatile var transportState: String = "NO_MEDIA_PRESENT"
    @Volatile var volume: Int = 50
    @Volatile var muted: Boolean = false
    @Volatile var position: String = "00:00:00"
    @Volatile var duration: String = "00:00:00"
    @Volatile var seekTarget: String = "00:00:00"

    fun setCurrent(uri: String, meta: MetadataParser.Meta) {
        currentUri = uri
        metadataXml = meta.raw
    }

    fun setNext(uri: String, meta: MetadataParser.Meta) {
        nextUri = uri
        nextMetadataXml = meta.raw
    }

    fun consumeNext(): Pair<String, String>? {
        if (nextUri.isBlank()) return null
        val pair = nextUri to nextMetadataXml
        nextUri = ""; nextMetadataXml = ""
        return pair
    }
}
