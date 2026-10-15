package com.example.mediascout.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.example.mediascout.model.MediaInfo
import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.Locale

class YtDlpMediaRepository(private val appContext: Context) {
    @Volatile
    private var initialized = false

    suspend fun inspect(url: String): MediaInfo = withContext(Dispatchers.IO) {
        require(isHttpUrl(url)) {
            "أدخل رابطًا يبدأ بـ http:// أو https://"
        }

        initializeIfNeeded()

        val json = try {
            probeWithPython(url)
        } catch (error: Throwable) {
            throw IllegalStateException(friendlyError(rootMessage(error)), error)
        }

        MediaInfoParser.parse(json)
    }

    /**
     * ينزّل الصيغة المحددة إلى مجلد التنزيلات ويعيد مكان الملف.
     * formatSelector مثل: "140" أو "best" أو "bestaudio".
     */
    suspend fun download(
        url: String,
        formatSelector: String,
        fileBase: String,
        onProgress: (Float, Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        require(isHttpUrl(url)) {
            "أدخل رابطًا يبدأ بـ http:// أو https://"
        }

        initializeIfNeeded()

        val workDir = File(appContext.cacheDir, "downloads/${System.nanoTime()}")
        workDir.mkdirs()
        try {
            val request = YtDlpRequest(url)
                .addOption("-f", formatSelector)
                .addOption("--no-playlist")
                .setOutputTemplate(File(workDir, "media.%(ext)s").absolutePath)

            val callback = DownloadProgressCallback { progress, eta, _ ->
                onProgress(progress, eta)
            }

            val response = try {
                YtDlp.execute(request, callback)
            } catch (error: YtDlpException) {
                throw IllegalStateException(friendlyError(error.message ?: ""), error)
            }
            if (!response.isSuccess()) {
                throw IllegalStateException("انتهى yt-dlp برمز ${response.getExitCode()}")
            }

            val produced = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("لم يُنتج التنزيل أي ملف.")

            val extension = produced.extension.ifBlank { "bin" }
            saveToDownloads(produced, "${safeFileName(fileBase)}.$extension")
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun saveToDownloads(source: File, fileName: String): String {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(source.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MediaScout")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذر إنشاء الملف في مجلد التنزيلات.")
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("تعذر فتح الملف للكتابة.")
            stream.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Download/MediaScout/$fileName"
        } else {
            val folder = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.filesDir
            val target = File(folder, fileName)
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }
    }

    private fun safeFileName(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.take(100).ifBlank { "media" }
    }

    // المكتبة لا تدعم وضع "الفحص فقط" ولا تعيد المخرجات، لذلك نستدعي yt-dlp مباشرة
    // عبر بيئة Python المدمجة (تم تشغيلها في YtDlp.init).
    private fun probeWithPython(url: String): String {
        val pythonClass = Class.forName("com.chaquo.python.Python")
        val pyObjectClass = Class.forName("com.chaquo.python.PyObject")
        val callAttr = pyObjectClass.getMethod(
            "callAttr", String::class.java, Array<Any?>::class.java
        )

        fun call(target: Any, name: String, vararg args: Any?): Any? =
            callAttr.invoke(target, name, arrayOf<Any?>(*args))

        val python = pythonClass.getMethod("getInstance").invoke(null)!!
        val builtins = pythonClass.getMethod("getBuiltins").invoke(python)!!
        val namespace = call(builtins, "dict")!!
        call(builtins, "exec", PROBE_CODE, namespace)
        val probe = call(namespace, "get", "probe")!!
        return call(probe, "__call__", url).toString()
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

    private fun rootMessage(error: Throwable): String {
        var current: Throwable = error
        if (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current.message ?: current.toString()
    }

    private fun friendlyError(raw: String): String = when {
        "Sign in to confirm" in raw || "403" in raw || "impersonat" in raw ->
            "رفض الموقع الطلب (403 أو تحقق ضد الروبوتات). بعض المواقع، ومنها يوتيوب، قد تحتاج ملف كوكيز أو نسخة مكتبة تدعم تقليد المتصفح.\n\nالتفاصيل: ${raw.take(400)}"
        "ffmpeg" in raw.lowercase(Locale.ROOT) ->
            "هذه الصيغة تحتاج FFmpeg للدمج أو التحويل وهو غير متوفر في التطبيق. جرّب صيغة «فيديو مع صوت» أو «صوت فقط».\n\nالتفاصيل: ${raw.take(300)}"
        "Unsupported URL" in raw ->
            "الرابط غير مدعوم أو لا يحتوي على وسائط يمكن فحصها."
        "urlopen error" in raw || "timed out" in raw || "Unable to download" in raw ->
            "تعذر الاتصال بالموقع. تأكد من الإنترنت وحاول مرة أخرى.\n\nالتفاصيل: ${raw.take(400)}"
        "No space left" in raw ->
            "لا توجد مساحة كافية على الجهاز."
        else -> raw.take(700)
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)

    private companion object {
        val PROBE_CODE = """
import json
import yt_dlp

def probe(url):
    opts = {
        'quiet': True,
        'no_warnings': True,
        'noprogress': True,
        'skip_download': True,
        'extract_flat': 'in_playlist',
        'playlistend': 50,
        'socket_timeout': 30,
        'cachedir': False,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
        return json.dumps(ydl.sanitize_info(info), ensure_ascii=False)
""".trimIndent()
    }
}
