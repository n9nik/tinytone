package com.n9nik.audiocutter.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.n9nik.audiocutter.ads.BannerAd
import com.n9nik.audiocutter.audio.AudioTrimmer
import com.n9nik.audiocutter.audio.RingtoneHelper
import com.n9nik.audiocutter.audio.TrimResult
import com.n9nik.audiocutter.audio.Waveform
import com.n9nik.audiocutter.audio.WaveformExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class Stage { PICK, LOADING, EDITOR, TRIMMING, DONE }

@Composable
fun ToneApp(
    adsReady: Boolean,
    privacyOptionsAvailable: Boolean,
    onPrivacyOptions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(Stage.PICK) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var waveform by remember { mutableStateOf<Waveform?>(null) }
    var trimResult by remember { mutableStateOf<TrimResult?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* best effort */ }
        sourceUri = uri
        sourceName = queryDisplayName(context, uri)
        waveform = null
        trimResult = null
        stage = Stage.LOADING
        scope.launch(Dispatchers.Default) {
            val wf = WaveformExtractor.extract(context, uri)
            withContext(Dispatchers.Main) {
                if (wf == null || wf.durationMs <= 0) {
                    Toast.makeText(
                        context, "Couldn't read that audio file", Toast.LENGTH_LONG
                    ).show()
                    stage = Stage.PICK
                } else {
                    waveform = wf
                    stage = Stage.EDITOR
                }
            }
        }
    }

    fun pickAudio() = pickLauncher.launch(arrayOf("audio/*"))

    fun resetAll() {
        sourceUri = null
        sourceName = ""
        waveform = null
        trimResult = null
        stage = Stage.PICK
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (stage) {
                Stage.PICK -> PickScreen(onPick = ::pickAudio)
                Stage.LOADING -> LoadingScreen(fileName = sourceName)
                Stage.EDITOR -> {
                    val wf = waveform
                    val uri = sourceUri
                    if (wf != null && uri != null) {
                        EditorScreen(
                            uri = uri,
                            fileName = sourceName,
                            waveform = wf,
                            onBack = ::resetAll,
                            onTrimmed = { result ->
                                trimResult = result
                                stage = Stage.DONE
                            },
                            onTrimStart = { stage = Stage.TRIMMING },
                            onTrimFailed = { stage = Stage.EDITOR }
                        )
                    }
                }
                Stage.TRIMMING -> TrimmingScreen()
                Stage.DONE -> {
                    val result = trimResult
                    if (result != null) {
                        DoneScreen(
                            result = result,
                            baseName = sourceName.substringBeforeLast('.'),
                            onAgain = ::resetAll
                        )
                    }
                }
            }
        }
        if (privacyOptionsAvailable) {
            TextButton(
                onClick = onPrivacyOptions,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Privacy options") }
        }
        if (adsReady) BannerAd(Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------------------
// Pick
// ---------------------------------------------------------------------------

@Composable
private fun PickScreen(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Audiotrack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "TinyTone",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Cut ringtones from your music.\n100% offline. No account. No contacts access.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose audio file")
        }
        Spacer(Modifier.height(20.dp))
        listOf(
            "Trim MP3 and AAC without re-encoding",
            "Drag on the waveform to pick the perfect part",
            "Set as ringtone, alarm, or notification sound",
            "Optional fade in / fade out"
        ).forEach { bullet ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(bullet, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LoadingScreen(fileName: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Reading $fileName…", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Drawing the waveform",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------

@Composable
private fun EditorScreen(
    uri: Uri,
    fileName: String,
    waveform: Waveform,
    onBack: () -> Unit,
    onTrimStart: () -> Unit,
    onTrimFailed: () -> Unit,
    onTrimmed: (TrimResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val durationMs = waveform.durationMs

    var startMs by remember(waveform) { mutableStateOf(0L) }
    var endMs by remember(waveform) { mutableStateOf(durationMs) }
    var fadeIn by remember { mutableStateOf(false) }
    var fadeOut by remember { mutableStateOf(false) }
    var isTrimming by remember { mutableStateOf(false) }

    // Preview playback of the selected region.
    val player = remember { MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {}
            player.release()
        }
    }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(200)
            val pos = try { player.currentPosition.toLong() } catch (_: Exception) { 0L }
            if (pos >= endMs) {
                try { player.pause() } catch (_: Exception) {}
                isPlaying = false
            }
        }
    }
    fun togglePreview() {
        if (isPlaying) {
            try { player.pause() } catch (_: Exception) {}
            isPlaying = false
            return
        }
        try {
            player.reset()
            player.setDataSource(context, uri)
            player.prepare()
            player.seekTo(startMs.toInt())
            player.start()
            isPlaying = true
        } catch (_: Exception) {
            Toast.makeText(context, "Couldn't preview audio", Toast.LENGTH_SHORT).show()
        }
    }

    fun doTrim() {
        val range = AudioTrimmer.coerceRange(startMs, endMs, durationMs)
        if (range == null) {
            Toast.makeText(context, "Pick at least 1 second", Toast.LENGTH_SHORT).show()
            return
        }
        isTrimming = true
        onTrimStart()
        scope.launch(Dispatchers.Default) {
            val result = AudioTrimmer.trim(
                context, uri, range,
                fadeInMs = if (fadeIn) 2000 else 0,
                fadeOutMs = if (fadeOut) 2000 else 0
            )
            withContext(Dispatchers.Main) {
                isTrimming = false
                if (result == null) {
                    Toast.makeText(context, "Trim failed for this file", Toast.LENGTH_LONG).show()
                    // Back to the editor so the "Cutting your audio..." spinner is
                    // always dismissed, even on failure. User can retry or pick
                    // another file.
                    onTrimFailed()
                } else {
                    onTrimmed(result)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
                isPlaying = false
                onBack()
            }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Drag the handles to choose your ringtone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            WaveformCanvas(
                peaks = waveform.peaks,
                durationMs = durationMs,
                startMs = startMs,
                endMs = endMs,
                onRangeChange = { s, e -> startMs = s; endMs = e },
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatMs(startMs), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatMs(endMs - startMs)} selected",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(formatMs(endMs), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = ::togglePreview,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isPlaying) "Stop" else "Preview")
            }
            Button(
                onClick = ::doTrim,
                enabled = !isTrimming,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.ContentCut, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save trim")
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Fade",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = fadeIn,
                onClick = { fadeIn = !fadeIn },
                label = { Text("Fade in (2s)") }
            )
            FilterChip(
                selected = fadeOut,
                onClick = { fadeOut = !fadeOut },
                label = { Text("Fade out (2s)") }
            )
        }
        if (fadeIn || fadeOut) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Fades re-encode the clip to AAC; plain trims stay lossless.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isTrimming) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WaveformCanvas(
    peaks: FloatArray,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeHandle by remember { mutableStateOf(0) } // 1 = start, 2 = end
    // The drag gesture block is keyed on (durationMs, peaks.size) so it survives
    // recompositions mid-drag. That means startMs/endMs/onRangeChange captured
    // directly would go stale after the first drag (dragging one handle would
    // then snap the other handle back). rememberUpdatedState keeps them fresh
    // without restarting the gesture.
    val currentStartMs by rememberUpdatedState(startMs)
    val currentEndMs by rememberUpdatedState(endMs)
    val currentOnRangeChange by rememberUpdatedState(onRangeChange)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(durationMs, peaks.size) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat()
                        val sx = currentStartMs.toFloat() / durationMs * w
                        val ex = currentEndMs.toFloat() / durationMs * w
                        activeHandle =
                            if (abs(offset.x - sx) <= abs(offset.x - ex)) 1 else 2
                    },
                    onDragEnd = { activeHandle = 0 },
                    onDragCancel = { activeHandle = 0 },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat()
                        if (w <= 0) return@detectDragGestures
                        val ms = (change.position.x / w * durationMs)
                            .toLong().coerceIn(0, durationMs)
                        if (activeHandle == 1) {
                            currentOnRangeChange(
                                ms.coerceIn(0, currentEndMs - 1000), currentEndMs)
                        } else if (activeHandle == 2) {
                            currentOnRangeChange(
                                currentStartMs, ms.coerceIn(currentStartMs + 1000, durationMs))
                        }
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0 || durationMs <= 0) return@Canvas
        val barW = w / peaks.size
        peaks.forEachIndexed { i, peak ->
            val cx = i * barW + barW / 2f
            val barH = (peak * h * 0.84f).coerceAtLeast(2f)
            val ms = i.toFloat() / peaks.size * durationMs
            val inSel = ms >= startMs && ms <= endMs
            drawRoundRect(
                color = if (inSel) Color(0xFF4F46E5)
                else Color(0xFFC7D2FE).copy(alpha = 0.55f),
                topLeft = Offset(cx - barW * 0.30f, h / 2f - barH / 2f),
                size = Size(barW * 0.60f, barH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )
        }
        val handleW = 14.dp.toPx()
        val sx = startMs.toFloat() / durationMs * w
        val ex = endMs.toFloat() / durationMs * w
        drawRoundRect(
            color = Color(0xFF312E81),
            topLeft = Offset(sx - handleW / 2f, 0f),
            size = Size(handleW, h),
            cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
        )
        drawRoundRect(
            color = Color(0xFF312E81),
            topLeft = Offset(ex - handleW / 2f, 0f),
            size = Size(handleW, h),
            cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
        )
    }
}

@Composable
private fun TrimmingScreen() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Cutting your audio…", style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------
// Done
// ---------------------------------------------------------------------------

@Composable
private fun DoneScreen(
    result: TrimResult,
    baseName: String,
    onAgain: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettingsGuide by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf(RingtoneHelper.TYPE_RINGTONE) }
    var workingType by remember { mutableStateOf<Int?>(null) }

    fun share() {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", result.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = result.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share trim"))
        } catch (_: Exception) {
            Toast.makeText(context, "Couldn't share", Toast.LENGTH_SHORT).show()
        }
    }

    fun setAs(type: Int) {
        val activity = context as? Activity
        if (activity == null) return
        if (!RingtoneHelper.canWriteSettings(context)) {
            pendingType = type
            showSettingsGuide = true
            return
        }
        if (workingType != null) return
        workingType = type
        val title = "$baseName TinyTone"
        scope.launch(Dispatchers.IO) {
            val uri = RingtoneHelper.saveToMediaStore(
                context, result.file, result.mimeType, title, type
            )
            val ok = uri != null && RingtoneHelper.setAsDefault(context, uri, type)
            withContext(Dispatchers.Main) {
                workingType = null
                Toast.makeText(
                    context,
                    if (ok) "Set as ${RingtoneHelper.typeLabel(type)} ✓"
                    else "Couldn't set ${RingtoneHelper.typeLabel(type)}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    if (showSettingsGuide) {
        AlertDialog(
            onDismissRequest = { showSettingsGuide = false },
            title = { Text("One system step") },
            text = {
                Text(
                    "Android asks you to allow this manually:\n\n" +
                        "1. Tap Open Settings below\n" +
                        "2. Turn on “Allow modifying system settings” for TinyTone\n" +
                        "3. Come back and tap “Set as” again\n\n" +
                        "TinyTone never touches your contacts."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showSettingsGuide = false
                    (context as? Activity)?.let { RingtoneHelper.openWriteSettings(it) }
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsGuide = false }) { Text("Later") }
            }
        )
    }

    val sizeKb = result.file.length() / 1024
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = Color(0xFF16A34A),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Trim saved!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${result.file.name} • $sizeKb KB",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        val busy = workingType != null
        @Composable
        fun setButton(type: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
            Button(
                onClick = { setAs(type) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (workingType == type) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(icon, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
            Spacer(Modifier.height(8.dp))
        }

        setButton(RingtoneHelper.TYPE_RINGTONE, Icons.Filled.Audiotrack, "Set as ringtone")
        setButton(RingtoneHelper.TYPE_ALARM, Icons.Filled.Alarm, "Set as alarm")
        setButton(RingtoneHelper.TYPE_NOTIFICATION, Icons.Filled.Notifications, "Set as notification")

        OutlinedButton(
            onClick = ::share,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAgain) { Text("Trim another file") }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = "audio"
    var cursor: Cursor? = null
    try {
        cursor = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )
        if (cursor != null && cursor.moveToFirst()) {
            name = cursor.getString(0) ?: name
        }
    } catch (_: Exception) {
    } finally {
        cursor?.close()
    }
    return name
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
