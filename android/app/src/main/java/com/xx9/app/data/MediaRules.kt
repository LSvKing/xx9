package com.xx9.app.data

/** 与 Web `SHORT_MAX=300` / server `SHORT_MAX_SEC` 同源：duration>0 且 <300 为短。 */
object MediaRules {
    const val SHORT_MAX_SEC = 300

    fun isShort(durationSec: Int?): Boolean =
        durationSec != null && durationSec > 0 && durationSec < SHORT_MAX_SEC

    fun isShort(item: VideoItem): Boolean = isShort(item.duration)

    fun isShort(detail: VideoDetail): Boolean = isShort(detail.duration)

    fun isShortOrNull(detail: VideoDetail?): Boolean = isShort(detail?.duration)

    /** 封面：短 9:16，长 16:10 */
    fun coverAspect(durationSec: Int?): Float =
        if (isShort(durationSec)) 9f / 16f else 16f / 10f

    /** 播放区：短竖 9:16，长 ~16:9 */
    fun playerAspect(durationSec: Int?): Float =
        if (isShort(durationSec)) 9f / 16f else 16f / 9f
}
