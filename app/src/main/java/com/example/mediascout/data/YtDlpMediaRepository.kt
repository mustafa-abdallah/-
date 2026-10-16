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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
            callPython("probe", url)
        } catch (error: Throwable) {
            throw IllegalStateException(friendlyError(rootMessage(error)), error)
        }

        MediaInfoParser.parse(json)
    }

    /**
     * ينزّل الصيغة المحددة إلى مجلد التنزيلات ويعيد مكان الملف.
     * إن لم تتوفر الصيغة المحددة يجرّب fallbackSelector، وعلى يوتيوب يجرّب عدة عملاء.
     */
    suspend fun download(
        url: String,
        formatSelector: String,
        fallbackSelector: String,
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
            val callback = DownloadProgressCallback { progress, eta, _ ->
                onProgress(progress, eta)
            }

            val resultJson = try {
                callPython(
                    "download", url, formatSelector, fallbackSelector,
                    workDir.absolutePath, callback
                )
            } catch (error: Throwable) {
                throw IllegalStateException(friendlyError(rootMessage(error)), error)
            }

            val result = JSONObject(resultJson)
            val reported = result.optString("path")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { File(it) }

            val produced = reported?.takeIf { it.isFile }
                ?: workDir.walkTopDown()
                    .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    .maxByOrNull { it.length() }
                ?: throw IllegalStateException("لم يُنتج التنزيل أي ملف.")

            val formatId = result.optString("format_id")
                .takeIf { it.isNotBlank() && it != "null" }
            val baseName = if (formatId != null) "$fileBase [$formatId]" else fileBase
            val extension = produced.extension.ifBlank { "bin" }
            saveToDownloads(produced, "${safeFileName(baseName)}.$extension")
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
        return cleaned.take(120).ifBlank { "media" }
    }

    // المكتبة لا تدعم وضع "الفحص فقط" ولا اختيار عميل يوتيوب، لذلك نستدعي yt-dlp مباشرة
    // عبر بيئة Python المدمجة (تم تشغيلها في YtDlp.init).
    private fun callPython(function: String, vararg args: Any?): String {
        val pythonClass = Class.forName("com.chaquo.python.Python")
        val pyObjectClass = Class.forName("com.chaquo.python.PyObject")
        val callAttr = pyObjectClass.getMethod(
            "callAttr", String::class.java, Array<Any?>::class.java
        )

        fun call(target: Any, name: String, vararg callArgs: Any?): Any? =
            callAttr.invoke(target, name, arrayOf<Any?>(*callArgs))

        val python = pythonClass.getMethod("getInstance").invoke(null)!!
        val builtins = pythonClass.getMethod("getBuiltins").invoke(python)!!
        val namespace = call(builtins, "dict")!!
        call(builtins, "exec", PYTHON_CODE, namespace)
        val target = call(namespace, "get", function)!!
        return call(target, "__call__", *args).toString()
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
        "403" in raw || "Sign in to confirm" in raw ->
            "رفض الموقع التنزيل (403) بعد تجربة عدة طرق. يوتيوب يشترط أحيانًا بيئة JavaScript أو PO Token غير متوفرين في هذه المكتبة. جرّب فيديو آخر أو لاحقًا.\n\nالتفاصيل: ${raw.take(300)}"
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
        val PYTHON_CODE = """
import json
import os
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

def download(url, selector, fallback, outdir, cb):
    def hook(d):
        if cb is None or d.get('status') != 'downloading':
            return
        try:
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            done = d.get('downloaded_bytes') or 0
            pct = (done * 100.0 / total) if total else 0.0
            eta = int(d.get('eta') or 0)
            cb.onProgressUpdate(float(pct), eta, '')
        except Exception:
            pass

    is_yt = 'youtube.com' in url or 'youtu.be' in url
    clients = [['android_vr'], ['android'], ['tv'], None] if is_yt else [None]
    selectors = [selector]
    if fallback and fallback != selector:
        selectors.append(fallback)

    last = None
    n = 0
    for client in clients:
        for sel in selectors:
            n += 1
            opts = {
                'quiet': True,
                'no_warnings': True,
                'noprogress': True,
                'noplaylist': True,
                'cachedir': False,
                'retries': 3,
                'fragment_retries': 3,
                'socket_timeout': 30,
                'format': sel,
                'outtmpl': os.path.join(outdir, str(n), 'media.%(ext)s'),
                'progress_hooks': [hook],
            }
            if client:
                opts['extractor_args'] = {'youtube': {'player_client': client}}
            try:
                with yt_dlp.YoutubeDL(opts) as ydl:
                    info = ydl.extract_info(url, download=True)
                    items = info.get('requested_downloads') or []
                    path = items[0].get('filepath') if items else None
                    return json.dumps({
                        'path': path,
                        'format_id': info.get('format_id'),
                        'client': ','.join(client) if client else 'default',
                    })
            except Exception as e:
                last = e
                if 'Requested format is not available' in str(e):
                    continue
                break
    raise last
""".trimIndent()
    }
}
