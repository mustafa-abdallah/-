package com.example.mediascout.data

import android.content.Context
import com.example.mediascout.model.MediaInfo
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.InvocationTargetException

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
        "Unsupported URL" in raw ->
            "الرابط غير مدعوم أو لا يحتوي على وسائط يمكن فحصها."
        "urlopen error" in raw || "timed out" in raw || "Unable to download" in raw ->
            "تعذر الاتصال بالموقع. تأكد من الإنترنت وحاول مرة أخرى.\n\nالتفاصيل: ${raw.take(400)}"
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
