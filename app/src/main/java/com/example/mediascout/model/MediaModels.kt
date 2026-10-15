package com.example.mediascout.model

data class MediaFormat(
    val id: String,
    val extension: String,
    val description: String,
    val height: Int?,
    val resolution: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val sizeBytes: Long?,
    val sizeIsApproximate: Boolean,
    val bitrateKbps: Double?,
    val kind: FormatKind
)

enum class FormatKind {
    AUDIO_ONLY,
    VIDEO_AND_AUDIO,
    VIDEO_ONLY,
    OTHER
}

data class PlaylistEntry(
    val title: String,
    val url: String?,
    val durationSeconds: Long?,
    val formats: List<MediaFormat>
)

data class MediaInfo(
    val title: String,
    val webpageUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val isPlaylist: Boolean,
    val entries: List<PlaylistEntry>,
    val formats: List<MediaFormat>
)
