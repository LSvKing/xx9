package com.xx9.app.cover

/** Coil 请求模型：优先 CDN XOR 解密，失败回退 /api/cover。 */
data class CoverRequest(
    val videoId: Int,
    val cdnUrl: String?,
    val proxyUrl: String?,
    val imgSecret: String,
)
