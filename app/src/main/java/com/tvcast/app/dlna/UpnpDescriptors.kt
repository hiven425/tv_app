package com.tvcast.app.dlna

import com.tvcast.app.util.xmlEscape

/** UPnP MediaRenderer XML descriptors. Kept minimal but compliant with UPnP/AV 1.0. */
object UpnpDescriptors {

    fun deviceXml(friendlyName: String, udn: String, baseUrl: String): String = """
<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <URLBase>$baseUrl</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>${friendlyName.xmlEscape()}</friendlyName>
    <manufacturer>TvCast</manufacturer>
    <manufacturerURL>https://github.com/</manufacturerURL>
    <modelDescription>TvCast DLNA Media Renderer</modelDescription>
    <modelName>TvCast</modelName>
    <modelNumber>0.1</modelNumber>
    <modelURL>https://github.com/</modelURL>
    <serialNumber>1</serialNumber>
    <UDN>$udn</UDN>
    <iconList>
      <icon>
        <mimetype>image/png</mimetype>
        <width>120</width><height>120</height><depth>24</depth>
        <url>/icon.png</url>
      </icon>
    </iconList>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/AVTransport.xml</SCPDURL>
        <controlURL>/AVTransport/control</controlURL>
        <eventSubURL>/AVTransport/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/RenderingControl.xml</SCPDURL>
        <controlURL>/RenderingControl/control</controlURL>
        <eventSubURL>/RenderingControl/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/ConnectionManager.xml</SCPDURL>
        <controlURL>/ConnectionManager/control</controlURL>
        <eventSubURL>/ConnectionManager/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
""".trimIndent()

    val avTransportScpd: String = """
<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Play</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Pause</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Stop</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Seek</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
        <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetPositionInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
        <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
        <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
        <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
        <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
        <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
        <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
        <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetTransportInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
        <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
        <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetMediaInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
        <argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
        <argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
        <argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
        <argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument>
        <argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument>
        <argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType>
      <allowedValueList><allowedValue>STOPPED</allowedValue><allowedValue>PLAYING</allowedValue><allowedValue>PAUSED_PLAYBACK</allowedValue><allowedValue>TRANSITIONING</allowedValue><allowedValue>NO_MEDIA_PRESENT</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>PlaybackStorageMedium</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RecordStorageMedium</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RecordMediumWriteStatus</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>
""".trimIndent()

    val renderingControlScpd: String = """
<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>SetVolume</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetMute</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>SetMute</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType><allowedValueList><allowedValue>Master</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>
    <stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
  </serviceStateTable>
</scpd>
""".trimIndent()

    val connectionManagerScpd: String = """
<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name>
      <argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>
""".trimIndent()

    /** Sink ProtocolInfo string — what mimetypes this renderer claims to accept. */
    const val SINK_PROTOCOL_INFO: String =
        "http-get:*:video/mp4:*," +
        "http-get:*:video/mpeg:*," +
        "http-get:*:video/x-matroska:*," +
        "http-get:*:video/x-msvideo:*," +
        "http-get:*:video/quicktime:*," +
        "http-get:*:video/webm:*," +
        "http-get:*:application/x-mpegURL:*," +
        "http-get:*:application/vnd.apple.mpegurl:*," +
        "http-get:*:audio/mpeg:*," +
        "http-get:*:audio/mp4:*," +
        "http-get:*:audio/x-wav:*," +
        "http-get:*:audio/flac:*," +
        "http-get:*:audio/ogg:*," +
        "http-get:*:image/jpeg:*," +
        "http-get:*:image/png:*," +
        "http-get:*:image/gif:*"
}
