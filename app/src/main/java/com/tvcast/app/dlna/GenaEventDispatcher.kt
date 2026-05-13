package com.tvcast.app.dlna

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.io.PrintWriter
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * GENA event dispatcher.
 *
 * Tracks a list of subscriber callback URLs per UPnP service (`avtransport`, `renderingcontrol`,
 * `connectionmanager`) and pushes NOTIFY messages to them when state changes.
 *
 * Many DLNA controllers (BubbleUPnP, AllConnect, Hi8 — pretty much anything serious) require a
 * successful SUBSCRIBE before they accept the renderer; without it they fall back to "renderer
 * found but unusable".
 */
class GenaEventDispatcher {
    companion object { private const val TAG = "GenaEvent" }

    data class Subscriber(
        val sid: String,
        val service: String,
        val callbackUrl: String,
        val seq: AtomicInteger = AtomicInteger(0),
    )

    private val subscribers = ConcurrentHashMap<String, Subscriber>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun subscribe(service: String, callbackUrl: String): String {
        val sid = "uuid:" + UUID.randomUUID()
        if (callbackUrl.isNotBlank()) {
            subscribers[sid] = Subscriber(sid, service.lowercase(), callbackUrl)
            Log.d(TAG, "subscribe $sid → $callbackUrl (service=$service)")
        }
        return sid
    }

    fun unsubscribe(sid: String) {
        subscribers.remove(sid)?.also { Log.d(TAG, "unsubscribe $sid") }
    }

    fun stop() {
        subscribers.clear()
        scope.cancel()
    }

    fun sendInitial(sid: String, service: String, state: RendererState) {
        val sub = subscribers[sid] ?: return
        val body = when (service.lowercase()) {
            "avtransport" -> avTransportPropertySet(state)
            "renderingcontrol" -> renderingControlPropertySet(state)
            "connectionmanager" -> connectionManagerPropertySet()
            else -> return
        }
        scope.launch { post(sub, body) }
    }

    fun notifyAv(state: RendererState) {
        val body = avTransportPropertySet(state)
        for (sub in subscribers.values.filter { it.service == "avtransport" }) {
            scope.launch { post(sub, body) }
        }
    }

    fun notifyRendering(state: RendererState) {
        val body = renderingControlPropertySet(state)
        for (sub in subscribers.values.filter { it.service == "renderingcontrol" }) {
            scope.launch { post(sub, body) }
        }
    }

    private fun avTransportPropertySet(state: RendererState): String {
        val inner = """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0">
<TransportState val="${state.transportState}"/>
<TransportStatus val="OK"/>
<CurrentTrackURI val="${state.currentUri.xmlEscape()}"/>
<CurrentTrackMetaData val="${state.metadataXml.xmlEscape()}"/>
<CurrentTrackDuration val="${state.duration}"/>
<AVTransportURI val="${state.currentUri.xmlEscape()}"/>
</InstanceID></Event>""".trimIndent()
        return propertySet("LastChange", inner.xmlEscape())
    }

    private fun renderingControlPropertySet(state: RendererState): String {
        val inner = """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0">
<Volume channel="Master" val="${state.volume}"/>
<Mute channel="Master" val="${if (state.muted) "1" else "0"}"/>
</InstanceID></Event>""".trimIndent()
        return propertySet("LastChange", inner.xmlEscape())
    }

    private fun connectionManagerPropertySet(): String = propertySet("SinkProtocolInfo", UpnpDescriptors.SINK_PROTOCOL_INFO)

    private fun propertySet(prop: String, value: String): String = """<?xml version="1.0"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
 <e:property><$prop>$value</$prop></e:property>
</e:propertyset>""".trimIndent()

    private fun post(sub: Subscriber, body: String) {
        val uri = try { URI(sub.callbackUrl) } catch (_: Exception) { return }
        val host = uri.host ?: return
        val port = if (uri.port > 0) uri.port else 80
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        runCatching {
            Socket(host, port).use { sock ->
                sock.soTimeout = 5000
                val out: OutputStream = sock.getOutputStream()
                val writer = PrintWriter(out, false, Charsets.ISO_8859_1)
                writer.print("NOTIFY $path HTTP/1.1\r\n")
                writer.print("HOST: $host:$port\r\n")
                writer.print("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
                writer.print("NT: upnp:event\r\n")
                writer.print("NTS: upnp:propchange\r\n")
                writer.print("SID: ${sub.sid}\r\n")
                writer.print("SEQ: ${sub.seq.getAndIncrement()}\r\n")
                val bytes = body.toByteArray(Charsets.UTF_8)
                writer.print("Content-Length: ${bytes.size}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                out.write(bytes)
                out.flush()
                // Drain response so the remote doesn't see RST.
                runCatching { sock.getInputStream().read(ByteArray(256)) }
            }
        }.onFailure { Log.w(TAG, "NOTIFY → ${sub.callbackUrl} failed: ${it.message}") }
    }
}
