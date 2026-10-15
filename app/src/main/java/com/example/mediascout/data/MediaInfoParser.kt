package com.example.mediascout.data

import com.example.mediascout.model.FormatKind
import com.example.mediascout.model.MediaFormat
import com.example.mediascout.model.MediaInfo
import com.example.mediascout.model.PlaylistEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts yt-dlp --dump-single-json output into app models.
 * Missing sizes remain null; the UI must not invent a file size.
 */
object MediaInfoParser {
    fun parse(rawOutput: String): MediaInfo {
        val start = rawOutput.indexOf('{')
        val end = rawOutput.lastIndexOf('}')
        require(start >= 0 && end > start) {
            "لم أجد بيانات JSON في نتيجة yt-dlp. جرّب رابطًا آخر أو راجع سجل الخطأ."
        }

        val json = JSONObject(rawOutput.substring(start, end + 1))
        val entriesArray = json.optJSONArray("entries")
        val isPlaylist = entriesArray != null

        val entries = entriesArray?.toEntries().orEmpty()
        val formats = json.optJSONArray("formats")?.toFormats().orEmpty()

        return MediaInfo(
            title = json.optString("title").takeUnless { it.isBlank() || it == "null" }
                ?: if (isPlaylist) "Playlist" else "Untitled",
            webpageUrl = json.optString("webpage_url").takeUnless { it.isBlank() || it == "null" },
            thumbnailUrl = json.optString("thumbnail").takeUnless { it.isBlank() || it == "null" },
            durationSeconds = json.optNullableLong("duration"),
            isPlaylist = isPlaylist,
            entries = entries,
            formats = formats
        )
    }

    private fun JSONArray.toEntries(): List<PlaylistEntry> = buildList {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            add(
                PlaylistEntry(
                    title = item.optString("title").takeUnless { it.isBlank() || it == "null" }
                        ?: "Video ${i + 1}",
                    durationSeconds = item.optNullableLong("duration"),
                    formats = item.optJSONArray("formats")?.toFormats().orEmpty()
                )
            )
        }
    }

    private fun JSONArray.toFormats(): List<MediaFormat> = buildList {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            val videoCodec = item.optString("vcodec").cleanNullable()
            val audioCodec = item.optString("acodec").cleanNullable()
            val hasVideo = videoCodec != null && videoCodec != "none"
            val hasAudio = audioCodec != null && audioCodec != "none"

            val kind = when {
                hasAudio && !hasVideo -> FormatKind.AUDIO_ONLY
                hasVideo && hasAudio -> FormatKind.VIDEO_AND_AUDIO
                hasVideo && !hasAudio -> FormatKind.VIDEO_ONLY
                else -> FormatKind.OTHER
            }

            val size = item.optNullableLong("filesize")
                ?: item.optNullableLong("filesize_approx")
            val sizeIsApproximate = item.optNullableLong("filesize") == null && size != null

            add(
                MediaFormat(
                    id = item.optString("format_id").cleanNullable() ?: "format-$i",
                    extension = item.optString("ext").cleanNullable() ?: "?",
                    description = item.optString("format_note").cleanNullable()
                        ?: item.optString("format").cleanNullable()
                        ?: "Format",
                    height = item.optNullableInt("height"),
                    resolution = item.optString("resolution").cleanNullable(),
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                    sizeBytes = size,
                    sizeIsApproximate = sizeIsApproximate,
                    bitrateKbps = item.optNullableDouble("tbr") ?: item.optNullableDouble("abr"),
                    kind = kind
                )
            )
        }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return try {
            getDouble(key).takeIf { it >= 0 }?.toLong()
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optNullableInt(key: String): Int? =
        optNullableLong(key)?.takeIf { it <= Int.MAX_VALUE }?.toInt()

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return try {
            getDouble(key).takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.cleanNullable(): String? =
        this?.takeUnless { it.isBlank() || it == "null" || it == "none" }
}
