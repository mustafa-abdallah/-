package com.example.mediascout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.mediascout.data.YtDlpMediaRepository
import com.example.mediascout.model.FormatKind
import com.example.mediascout.model.MediaFormat
import com.example.mediascout.model.MediaInfo
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = YtDlpMediaRepository(applicationContext)
        setContent {
            var arabic by remember { mutableStateOf(true) }
            CompositionLocalProvider(
                LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MediaScoutScreen(
                            arabic = arabic,
                            onLanguageChange = { arabic = it },
                            repository = repository
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaScoutScreen(
    arabic: Boolean,
    onLanguageChange: (Boolean) -> Unit,
    repository: YtDlpMediaRepository
) {
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val t: (String, String) -> String = { ar, en -> if (arabic) ar else en }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("MediaScout", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(t("فحص معلومات الوسائط والصيغ", "Inspect media information and formats"),
                    style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onLanguageChange(!arabic) }) {
                Text(if (arabic) "English" else "العربية")
            }
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(t("رابط المحتوى", "Media URL")) },
            placeholder = { Text("https://…") },
            singleLine = true
        )

        Button(
            onClick = {
                media = null
                error = null
                loading = true
                scope.launch {
                    try {
                        media = repository.inspect(url.trim())
                    } catch (e: Exception) {
                        error = e.message ?: t("حدث خطأ غير معروف", "An unknown error occurred")
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (loading) t("جارٍ الفحص…", "Inspecting…") else t("فحص الرابط", "Inspect URL"))
        }

        Text(
            t(
                "استخدم روابط لمحتوى تملك حق الوصول إليه وتنزيله. هذه المرحلة تعرض المعلومات فقط ولا تبدأ تنزيل الملفات.",
                "Use URLs for content you are allowed to access and download. This phase only inspects metadata; it does not download files."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        error?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${t("تعذّر الفحص", "Inspection failed")}: $it",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        media?.let { result ->
            MediaSummary(result, arabic)
            if (result.isPlaylist) {
                SectionTitle(t("عناصر قائمة التشغيل", "Playlist entries"))
                Text(
                    t("العناصر المعروضة تعتمد على ما أعاده المصدر؛ بعض القوائم لا تعرض كل بيانات الصيغ لكل عنصر في عملية فحص واحدة.",
                        "Entries depend on what the source returns; some playlists do not include full per-item format data in one inspection."),
                    style = MaterialTheme.typography.bodySmall
                )
                result.entries.forEachIndexed { index, entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${index + 1}. ${entry.title}", fontWeight = FontWeight.SemiBold)
                            Text(formatDuration(entry.durationSeconds, arabic))
                            if (entry.formats.isNotEmpty()) {
                                Text(t("${entry.formats.size} صيغة", "${entry.formats.size} formats"))
                            }
                        }
                    }
                }
            }

            val audio = result.formats.filter { it.kind == FormatKind.AUDIO_ONLY }
            val combined = result.formats.filter { it.kind == FormatKind.VIDEO_AND_AUDIO }
            val videoOnly = result.formats.filter { it.kind == FormatKind.VIDEO_ONLY }

            FormatSection(
                title = t("1. الصوت فقط", "1. Audio only"),
                emptyText = t("لم تُرجع البيانات صيغًا صوتية منفصلة.", "No separate audio-only formats were returned."),
                formats = audio,
                arabic = arabic
            )
            FormatSection(
                title = t("2. فيديو مع صوت", "2. Video with audio"),
                emptyText = t("لم تُرجع البيانات صيغًا تحتوي على الفيديو والصوت معًا.", "No combined video-and-audio formats were returned."),
                formats = combined,
                arabic = arabic
            )
            if (videoOnly.isNotEmpty()) {
                FormatSection(
                    title = t("فيديو فقط (يحتاج غالبًا إلى مسار صوت منفصل)", "Video only (usually needs a separate audio stream)"),
                    emptyText = "",
                    formats = videoOnly,
                    arabic = arabic
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MediaSummary(media: MediaInfo, arabic: Boolean) {
    val t: (String, String) -> String = { ar, en -> if (arabic) ar else en }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(media.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (media.isPlaylist) t("النوع: قائمة تشغيل", "Type: playlist")
                else t("النوع: فيديو/عنصر مفرد", "Type: single video/item")
            )
            media.durationSeconds?.let { Text(t("المدة: ", "Duration: ") + formatDuration(it, arabic)) }
            if (media.isPlaylist) {
                Text(t("عدد العناصر التي أعادها المصدر: ", "Entries returned: ") + media.entries.size)
            }
            Text(t("عدد الصيغ: ", "Formats: ") + media.formats.size)
        }
    }
}

@Composable
private fun FormatSection(
    title: String,
    emptyText: String,
    formats: List<MediaFormat>,
    arabic: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        if (formats.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            formats.sortedWith(
                compareByDescending<MediaFormat> { it.height ?: -1 }
                    .thenByDescending { it.bitrateKbps ?: -1.0 }
            ).forEach { FormatRow(it, arabic) }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Divider()
}

@Composable
private fun FormatRow(format: MediaFormat, arabic: Boolean) {
    val t: (String, String) -> String = { ar, en -> if (arabic) ar else en }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    format.height?.let { "${it}p" } ?: format.resolution ?: format.description,
                    fontWeight = FontWeight.SemiBold
                )
                Text(formatSize(format.sizeBytes, format.sizeIsApproximate, arabic))
            }
            Text(
                "${format.extension.uppercase(Locale.ROOT)} • ${t("المعرّف", "ID")}: ${format.id}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(format.description, style = MaterialTheme.typography.bodySmall)
            val codecs = listOfNotNull(
                format.videoCodec?.takeIf { it != "none" }?.let { "V: $it" },
                format.audioCodec?.takeIf { it != "none" }?.let { "A: $it" }
            ).joinToString(" • ")
            if (codecs.isNotBlank()) {
                Text(codecs, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            format.bitrateKbps?.let {
                Text(
                    t("معدل البت: ", "Bitrate: ") + "%.0f kb/s".format(Locale.ROOT, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSize(bytes: Long?, approximate: Boolean, arabic: Boolean): String {
    if (bytes == null) return if (arabic) "الحجم غير معروف" else "Size unknown"
    val mib = bytes / (1024.0 * 1024.0)
    val value = if (mib >= 1024) "%.2f GiB".format(Locale.ROOT, mib / 1024.0)
        else "%.1f MiB".format(Locale.ROOT, mib)
    return when {
        approximate && arabic -> "≈ $value"
        approximate -> "≈ $value"
        else -> value
    }
}

private fun formatDuration(seconds: Long?, arabic: Boolean): String {
    if (seconds == null) return if (arabic) "المدة غير معروفة" else "Duration unknown"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}
