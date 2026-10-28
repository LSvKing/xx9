package com.xx9.app.cast

/** 发现到的 DLNA 渲染设备（AVTransport）。 */
data class DlnaDevice(
    val id: String,
    val name: String,
    val location: String,
    val controlUrl: String,
    val avTransportServiceType: String = "urn:schemas-upnp-org:service:AVTransport:1",
)
