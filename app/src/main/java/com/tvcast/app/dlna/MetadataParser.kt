package com.tvcast.app.dlna

import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Parse the DIDL-Lite metadata XML embedded in SetAVTransportURI calls. */
object MetadataParser {

    data class Subtitle(val url: String, val mime: String?, val language: String?)

    data class Meta(
        val title: String? = null,
        val mime: String? = null,
        val raw: String = "",
        val subtitles: List<Subtitle> = emptyList(),
    )

    fun parse(didl: String): Meta {
        if (didl.isBlank()) return Meta(raw = didl)
        return try {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                .newDocumentBuilder().parse(InputSource(StringReader(didl)))
            val title = doc.getElementsByTagName("dc:title").item(0)?.textContent?.takeUnless { it.isBlank() }
                ?: doc.getElementsByTagName("title").item(0)?.textContent
            val resNodes = doc.getElementsByTagName("res")
            var mainMime: String? = null
            val subs = mutableListOf<Subtitle>()
            for (i in 0 until resNodes.length) {
                val node = resNodes.item(i)
                val protocolInfo = node.attributes?.getNamedItem("protocolInfo")?.nodeValue ?: continue
                val mime = protocolInfo.split(":").getOrNull(2)
                if (mime?.startsWith("text/") == true || mime?.contains("subtitle", true) == true
                    || mime?.contains("ssa", true) == true || mime?.contains("ass", true) == true
                    || mime?.contains("srt", true) == true || mime?.contains("vtt", true) == true) {
                    subs += Subtitle(
                        url = node.textContent.trim(),
                        mime = mime,
                        language = null,
                    )
                } else if (mainMime == null) {
                    mainMime = mime
                }
            }

            // Samsung SEC namespace extension
            val secCaption = doc.getElementsByTagName("sec:CaptionInfoEx")
            for (i in 0 until secCaption.length) {
                val node = secCaption.item(i)
                val type = node.attributes?.getNamedItem("sec:type")?.nodeValue
                val url = node.textContent.trim()
                if (url.isNotEmpty()) {
                    subs += Subtitle(url = url, mime = type?.let { "application/$it" }, language = null)
                }
            }
            val secCaptionShort = doc.getElementsByTagName("sec:CaptionInfo")
            for (i in 0 until secCaptionShort.length) {
                val url = secCaptionShort.item(i).textContent.trim()
                if (url.isNotEmpty()) subs += Subtitle(url = url, mime = null, language = null)
            }

            // upnp:caption
            val upnpCaption = doc.getElementsByTagName("upnp:caption")
            for (i in 0 until upnpCaption.length) {
                val url = upnpCaption.item(i).textContent.trim()
                if (url.isNotEmpty()) subs += Subtitle(url = url, mime = null, language = null)
            }

            Meta(title = title, mime = mainMime, raw = didl, subtitles = subs)
        } catch (_: Exception) {
            Meta(raw = didl)
        }
    }
}
