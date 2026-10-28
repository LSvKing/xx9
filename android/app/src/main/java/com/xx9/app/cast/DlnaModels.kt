package com.xx9.app.cast

/** DLNA MediaRenderer（对标 hongguo DlnaDevice）。 */
data class DlnaDevice(
    val id: String,
    val friendlyName: String,
    val controlUrl: String,
    val host: String,
)

data class TransportPosition(val positionMs: Long, val durationMs: Long)

/** 投屏状态机（对标 hongguo CastState）。 */
sealed interface CastState {
    data object Idle : CastState
    data object Discovering : CastState
    data class Connecting(val deviceName: String) : CastState
    data class Playing(val deviceName: String) : CastState
    data class Failed(val message: String) : CastState

    fun label(): String = when (this) {
        Idle -> "idle"
        Discovering -> "discovering"
        is Connecting -> "connecting"
        is Playing -> "playing"
        is Failed -> "failed"
    }

    fun deviceNameOrNull(): String? = when (this) {
        is Connecting -> deviceName
        is Playing -> deviceName
        else -> null
    }

    fun messageOrNull(): String? = when (this) {
        is Failed -> message
        else -> null
    }
}
