package com.ilmer.sf2tool.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ilmer.sf2tool.core.Category
import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private val SF2_MIME = arrayOf("*/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val openFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.addFiles(uris)
    }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) vm.exportMerged(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.exportSplit(uri)
    }
    var showExport by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PresetItem?>(null) }

    val filtered = vm.filtered()
    val selectedCount = vm.selected.size
    val selectedBytes = vm.items.filter { it.key in vm.selected }.sumOf { it.bytes }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SF2 Extractor") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = { openFiles.launch(SF2_MIME) }) { Icon(Icons.Filled.Add, "Agregar archivos SF2") }
                    if (vm.items.isNotEmpty()) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Más opciones") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Marcar los ${filtered.size} visibles") }, onClick = { vm.selectAll(filtered); menuOpen = false })
                            DropdownMenuItem(text = { Text("Desmarcar los visibles") }, onClick = { vm.deselectAll(filtered); menuOpen = false })
                            DropdownMenuItem(text = { Text("Invertir selección visible") }, onClick = { vm.invert(filtered); menuOpen = false })
                            DropdownMenuItem(text = { Text("Desmarcar todo") }, onClick = { vm.deselectAll(vm.items); menuOpen = false })
                            DropdownMenuItem(text = { Text("Desmarcar duplicados (dejar 1 por nombre)") }, onClick = { vm.dropDuplicateNames(); menuOpen = false })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (vm.items.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$selectedCount de ${vm.items.size} marcados", fontWeight = FontWeight.SemiBold)
                            Text("Aprox. ${formatSize(selectedBytes)} de muestras", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(enabled = selectedCount > 0, onClick = { vm.refreshEstimate(); showExport = true }) { Text("Exportar…") }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.files.isEmpty()) {
                EmptyState(onOpen = { openFiles.launch(SF2_MIME) })
            } else {
                // loaded files
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in vm.files) {
                        InputChip(
                            selected = false, onClick = {},
                            label = { Text("${f.displayName} · ${f.font.presets.size} · ${formatSize(f.sizeBytes)}", maxLines = 1) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, "Quitar archivo", Modifier.size(18.dp).clickable { vm.removeFile(f) })
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = vm.query, onValueChange = { vm.query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    placeholder = { Text("Buscar por nombre…") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Filled.Close, "Borrar") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                val counts = vm.items.groupingBy { it.category }.eachCount()
                val dupCount = vm.items.count { (vm.nameCounts[it.nameKey] ?: 0) > 1 }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = vm.category == null && !vm.onlyDuplicates, onClick = { vm.category = null; vm.onlyDuplicates = false }, label = { Text("Todos (${vm.items.size})") })
                    if (dupCount > 0) FilterChip(selected = vm.onlyDuplicates, onClick = { vm.onlyDuplicates = !vm.onlyDuplicates }, label = { Text("Repetidos ($dupCount)") })
                    for (c in Category.values()) {
                        val n = counts[c] ?: continue
                        FilterChip(selected = vm.category == c, onClick = { vm.category = if (vm.category == c) null else c }, label = { Text("${c.label} ($n)") })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.selectAll(filtered) }) { Text("Marcar visibles") }
                    TextButton(onClick = { vm.deselectAll(filtered) }) { Text("Desmarcar") }
                    Spacer(Modifier.weight(1f))
                    Text("${filtered.size} en lista", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.key }) { p ->
                        PresetRow(p, checked = p.key in vm.selected, dup = vm.nameCounts[p.nameKey] ?: 1,
                            showFile = vm.files.size > 1, onToggle = { vm.toggle(p.key) }, onDetails = { details = p })
                    }
                }
            }
        }
    }

    if (showExport) ExportDialog(vm, onDismiss = { showExport = false }, onExport = {
        showExport = false
        if (vm.mode == ExportMode.MERGE) createFile.launch(vm.outName.ifBlank { "soundfont" }.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".sf2")
        else pickFolder.launch(null)
    })

    details?.let { p ->
        AlertDialog(
            onDismissRequest = { details = null },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Cerrar") } },
            title = { Text(p.name) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text("Archivo: ${p.file.displayName}")
                    Text("Banco ${p.bank}, programa ${p.program} · ${p.category.label}")
                    Text("${p.sampleCount} muestras · ${formatSize(p.bytes)}")
                    Text("Aparece ${vm.nameCounts[p.nameKey] ?: 1} vez/veces con este nombre")
                    Spacer(Modifier.height(8.dp))
                    Text("Instrumentos internos (${p.instrumentNames.size}):", fontWeight = FontWeight.SemiBold)
                    for (n in p.instrumentNames) Text("• $n")
                }
            },
        )
    }

    vm.busy?.let { b ->
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(b.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    val pr = b.progress
                    if (pr == null) CircularProgressIndicator()
                    else {
                        LinearProgressIndicator(progress = { pr.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${(pr * 100).toInt()} %")
                    }
                }
            }
        }
    }

    vm.message?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("Aceptar") } },
            text = { Text(m, Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) },
        )
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Abre uno o varios archivos SF2", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "Verás todos sus instrumentos, cuáles están repetidos y cuánto pesan. " +
                    "Marca los que quieras conservar y expórtalos en un solo SF2 o en un SF2 por instrumento.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpen) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Abrir archivos SF2")
            }
        }
    }
}

@Composable
private fun PresetRow(p: PresetItem, checked: Boolean, dup: Int, showFile: Boolean, onToggle: () -> Unit, onDetails: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (dup > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "×$dup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val sub = buildString {
                append(String.format(Locale.US, "%03d:%03d", p.bank, p.program))
                append(" · ").append(p.category.label).append(" · ").append(formatSize(p.bytes))
                if (showFile) append(" · ").append(p.file.displayName)
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onDetails) { Text("Info") }
    }
}

@Composable
private fun CheckRow(label: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(label)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExportDialog(vm: MainViewModel, onDismiss: () -> Unit, onExport: () -> Unit) {
    LaunchedEffect(vm.mode, vm.removeModulators, vm.removeEffects, vm.force16Bit, vm.stereoToMono, vm.maxRate) { vm.refreshEstimate() }
    val has24 = vm.selectedItems().any { it.file.font.has24Bit }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onExport) { Text(if (vm.mode == ExportMode.MERGE) "Guardar SF2" else "Elegir carpeta") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Exportar ${vm.selected.size} instrumentos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().clickable { vm.mode = ExportMode.MERGE }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = vm.mode == ExportMode.MERGE, onClick = { vm.mode = ExportMode.MERGE })
                    Text("Un solo SF2 con todos los marcados")
                }
                Row(Modifier.fillMaxWidth().clickable { vm.mode = ExportMode.SPLIT }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = vm.mode == ExportMode.SPLIT, onClick = { vm.mode = ExportMode.SPLIT })
                    Text("Un SF2 por cada instrumento")
                }
                if (vm.mode == ExportMode.MERGE) {
                    OutlinedTextField(value = vm.outName, onValueChange = { vm.outName = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CheckRow("Renumerar si chocan", "Si dos instrumentos usan el mismo banco y programa, mueve uno al siguiente número libre", vm.renumber) { vm.renumber = it }
                }
                Spacer(Modifier.height(8.dp))
                Text("Comprimir", fontWeight = FontWeight.SemiBold)
                CheckRow("Quitar efectos", "Elimina reverb y chorus", vm.removeEffects) { vm.removeEffects = it }
                CheckRow("Quitar moduladores", "Elimina los moduladores personalizados", vm.removeModulators) { vm.removeModulators = it }
                CheckRow("Estéreo a mono", "Deja un solo canal; reduce casi a la mitad las muestras estéreo", vm.stereoToMono) { vm.stereoToMono = it }
                if (has24) CheckRow("Convertir a 16 bits", "Quita los datos extra de 24 bits", vm.force16Bit) { vm.force16Bit = it }
                Spacer(Modifier.height(4.dp))
                Text("Calidad de las muestras")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((rate, label) in listOf(0 to "Original", 32000 to "32 kHz", 22050 to "22 kHz", 16000 to "16 kHz", 11025 to "11 kHz")) {
                        FilterChip(selected = vm.maxRate == rate, onClick = { vm.maxRate = rate }, label = { Text(label) })
                    }
                }
                if (vm.maxRate != 0) Text("Bajar la frecuencia reduce el tamaño pero quita brillo al sonido.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                val est = vm.estimate
                Text(if (est == null) "Calculando tamaño…" else "Tamaño final aproximado: ${formatSize(est)}", fontWeight = FontWeight.SemiBold)
            }
        },
    )
}
