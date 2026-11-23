@file:OptIn(ExperimentalMaterial3Api::class)

package com.formatswitcher.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.formatswitcher.app.MainViewModel
import com.formatswitcher.app.MediaKind
import com.formatswitcher.app.Mode
import com.formatswitcher.app.OutputFormat
import com.formatswitcher.app.SelectedFile
import com.formatswitcher.app.SizePreset
import com.formatswitcher.app.Status
import com.formatswitcher.app.formatBytes
import com.formatswitcher.app.formatDuration
import com.formatswitcher.app.isAlready

@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.select(uri)
    }
    val pickFile: () -> Unit = { picker.launch(arrayOf("image/*", "video/*", "audio/*")) }
    val status = vm.status
    val working = status is Status.Working

    // Keep the screen awake while a long video is processing.
    val view = LocalView.current
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Format Switcher", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val file = vm.selected
            if (file == null) {
                WelcomeCard(onPick = pickFile)
                if (status is Status.Failed) ErrorCard(status.message)
                SafetyCard()
            } else {
                FileCard(file, enabled = !working, onChange = pickFile, onClear = vm::clearFile)
                when (status) {
                    is Status.Working -> WorkingCard(status, onCancel = vm::cancel)
                    is Status.Done -> DoneCard(
                        status,
                        onOpen = { openFile(context, status.uri, status.mimeType) },
                        onShare = { shareFile(context, status.uri, status.mimeType) },
                        onAnother = vm::startOver,
                        onNewFile = pickFile,
                    )
                    else -> {
                        if (status is Status.Failed) ErrorCard(status.message)
                        ChooseAction(vm, file)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// --- Cards -----------------------------------------------------------------------------------

@Composable
private fun WelcomeCard(onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🖼️  🎬  🎵", fontSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Change a file's format or shrink it",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick a photo, video or audio file to get started. You can also tap Share in your Gallery and choose Format Switcher.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Choose a file", fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun SafetyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔒 Private & safe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SafetyLine("Works 100% offline – the app has no internet access at all")
            SafetyLine("Your original file is never changed or deleted")
            SafetyLine("Results are saved as new copies in a \"Format Switcher\" folder")
            SafetyLine("Photos have location (GPS) info removed when re-saved")
            SafetyLine("No ads, no accounts, no tracking, no storage permission needed")
        }
    }
}

@Composable
private fun SafetyLine(text: String) {
    Row {
        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FileCard(file: SelectedFile, enabled: Boolean, onChange: () -> Unit, onClear: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(file.kind.emoji, fontSize = 36.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = buildList {
                    add(file.kind.label)
                    add(formatBytes(file.sizeBytes))
                    file.durationMs?.let { add(formatDuration(it)) }
                }.joinToString("  •  ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled) {
                TextButton(onClick = onChange) { Text("Change") }
                androidx.compose.material3.IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove file")
                }
            }
        }
    }
}

@Composable
private fun ChooseAction(vm: MainViewModel, file: SelectedFile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = vm.mode == Mode.CONVERT,
                    onClick = { vm.mode = Mode.CONVERT },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Change format") }
                SegmentedButton(
                    selected = vm.mode == Mode.COMPRESS,
                    onClick = { vm.mode = Mode.COMPRESS },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Compress") }
            }
            Spacer(Modifier.height(16.dp))

            if (vm.mode == Mode.CONVERT) {
                Text("Convert to:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                OutputFormat.forKind(file.kind).forEach { fmt ->
                    val same = file.isAlready(fmt)
                    OptionRow(
                        title = fmt.title,
                        subtitle = if (same) "This is already the file's format" else fmt.subtitle,
                        selected = vm.chosenFormat == fmt,
                        enabled = !same,
                        onClick = { vm.chosenFormat = fmt },
                    )
                }
            } else {
                Text("Make it smaller than:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                SizePreset.entries.forEach { preset ->
                    val already = vm.isAlreadyUnder(file, preset)
                    OptionRow(
                        title = preset.label,
                        subtitle = if (already) "Already smaller than this – no need!" else preset.hint,
                        selected = vm.chosenPreset == preset,
                        enabled = !already,
                        onClick = { vm.chosenPreset = preset },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    compressNote(file.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            val canStart = if (vm.mode == Mode.CONVERT) vm.chosenFormat != null else vm.chosenPreset != null
            Button(
                onClick = vm::start,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (vm.mode == Mode.CONVERT) "Convert" else "Compress", fontSize = 18.sp)
            }
            if (!canStart && vm.mode == Mode.COMPRESS) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This file is already under 8 MB – it's small enough for all presets.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun compressNote(kind: MediaKind): String = when (kind) {
    MediaKind.IMAGE -> "Photos are saved as JPG (or WEBP if they already were). Quality is kept as high as possible."
    MediaKind.VIDEO -> "Saved as MP4 (H.264) so it plays anywhere. Resolution is lowered only if needed to fit."
    MediaKind.AUDIO -> "Saved as M4A. Very long recordings may switch to mono to fit."
}

@Composable
private fun OptionRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

@Composable
private fun WorkingCard(status: Status.Working, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(status.label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { status.progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text("${(status.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Keep the app open until it finishes. Your original file is safe either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun DoneCard(
    status: Status.Done,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onAnother: () -> Unit,
    onNewFile: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("✅ Done!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(status.fileName, style = MaterialTheme.typography.titleMedium)
            val sizeLine = if (status.originalBytes > 0) {
                "${formatBytes(status.originalBytes)}  →  ${formatBytes(status.sizeBytes)}"
            } else {
                formatBytes(status.sizeBytes)
            }
            Text(sizeLine, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Saved in: ${status.folder}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            status.note?.let {
                Spacer(Modifier.height(8.dp))
                Text("⚠️ $it", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open") }
                FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onAnother, modifier = Modifier.weight(1f)) { Text("Same file again") }
                OutlinedButton(onClick = onNewFile, modifier = Modifier.weight(1f)) { Text("New file") }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp)) {
            Text("⚠️", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

// --- Intents ---------------------------------------------------------------------------------

private fun openFile(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this file type.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share file"))
}
