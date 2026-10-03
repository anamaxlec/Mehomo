package dev.memoh.feature.bots

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.File

data class AudioPreview(val mime: String, val bytes: ByteArray)

@Composable
internal fun AudioPreviewDialog(audio: AudioPreview, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember(audio) { mutableStateOf<File?>(null) }
    val player = remember(audio) { MediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(audio.mime)) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(audio.bytes) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "音频保存失败" }
        }
    }
    LaunchedEffect(audio) {
        try {
            val temp = withContext(Dispatchers.IO) { File.createTempFile("speech-", ".audio", context.cacheDir).apply { writeBytes(audio.bytes) } }
            file = temp
            player.setDataSource(temp.path)
            player.setOnPreparedListener { prepared = true; duration = it.duration }
            player.setOnCompletionListener { playing = false; position = duration }
            player.setOnErrorListener { _, _, _ -> error = "此音频暂时无法播放"; playing = false; true }
            player.prepareAsync()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "此音频暂时无法播放" }
    }
    LaunchedEffect(playing) { while (playing) { position = player.currentPosition; delay(300) } }
    DisposableEffect(player) { onDispose { player.release(); file?.delete() } }
    MemohFormDialog("语音试听", onDismissRequest = onDismiss, confirmButton = {
        MemohActionButton("保存音频", Icons.Filled.SaveAlt, { exporter.launch("Mehomo-speech.${android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(audio.mime.substringBefore(';')) ?: "audio"}") })
    }, dismissButton = { TextButton(onDismiss) { Text("关闭") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MemohActionButton(if (playing) "暂停" else "播放", if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, {
                if (playing) player.pause() else { if (position >= duration) player.seekTo(0); player.start() }; playing = !playing
            }, enabled = prepared && error == null, primary = true)
            if (prepared) { Slider(position.toFloat(), { position = it.toInt(); player.seekTo(position) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat()); Text("${position / 1000} / ${duration / 1000} 秒", style = MaterialTheme.typography.labelSmall) }
            else if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    })
}
