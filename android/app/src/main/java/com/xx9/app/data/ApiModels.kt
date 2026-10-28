package com.xx9.app.data

data class VideoItem(
    val id: Int,
    val title: String,
    val coverLocal: String?,
    val cover: String?,
    val duration: Int?,
    val author: String?,
    val readNumber: Long?,
)

data class PlayLine(val name: String, val url: String)

data class VideoDetail(
    val id: Int,
    val title: String,
    val cover: String?,
    val duration: Int?,
    val author: String?,
    val lines: List<PlayLine>,
    val fav: Boolean,
    val tags: List<String>,
)

data class HomeBlock(
    val id: String?,
    val title: String,
    val source: String?,
    val items: List<VideoItem>,
)

data class GroupItem(val id: Int, val name: String)

data class VideoPage(
    val page: Int,
    val pageSize: Int,
    val items: List<VideoItem>,
    val hasMore: Boolean,
)
