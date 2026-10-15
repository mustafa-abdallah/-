package com.example.mediascout.data

import android.content.Context
import com.example.mediascout.model.MediaInfo
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YtDlpMediaRepository(private val appContext: Context) {
    @Volatile
    private var initialized = false

    suspend fun inspect(url: String): MediaInfo = withContext(Dispatchers.IO) {
        require(isHttpUrl(url)) {
            "أدخل رابطًا يبدأ بـ http:// أو https://"
        }

        initializeIfNeeded()

        val request = YtDlpRequest(url)
            .addOption("--dump-single-json")
            .addOption("--skip-download")
            .addOption("--no-warnings")

        val response = YtDlp.execute(request, null)
        if (!response.isSuccess()) {
            val details = response.getErrorOutput().take(700)
            throw IllegalStateException(
                details.ifBlank { "yt-dlp انتهى برمز ${response.getExitCode()}" }
            )
        }

        MediaInfoParser.parse(response.getOutput())
    }

    private fun initializeIfNeeded() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                YtDlp.init(appContext)
                initialized = true
            } catch (error: YtDlpException) {
                throw IllegalStateException(
                    "تعذر تهيئة yt-dlp: ${error.message ?: "خطأ غير معروف"}",
                    error
                )
            }
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
}
