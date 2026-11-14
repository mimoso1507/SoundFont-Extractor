package com.ilmer.sf2tool.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilmer.sf2tool.core.Category
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.io.File
import java.util.Locale
import javax.swing.JFileChooser

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

// ---- native file dialogs ----

private fun chooseSf2Files(window: ComposeWindow, startDir: File?): List<File> {
    val d = FileDialog(window, "Abrir archivos SF2", FileDialog.LOAD)
    d.isMultipleMode = true
    d.file = "*.sf2"
    d.setFilenameFilter { _, name -> name.lowercase().endsWith(".sf2") }
    startDir?.let { d.directory = it.absolutePath }
    d.isVisible = true
    return d.files.toList()
}

private fun chooseSaveFile(window: ComposeWindow, startDir: File?, suggested: String): File? {
    val d = FileDialog(window, "Guardar SF2", FileDialog.SAVE)
    d.file = suggested
    startDir?.let { d.directory = it.absolutePath }
    d.isVisible = true
    val name = d.file ?: return null
    val f = File(d.directory, name)
    return if (f.name.lowercase().endsWith(".sf2")) f else File(f.parentFile, f.name + ".sf2")
}

private fun chooseFolder(window: ComposeWindow, startDir: File?): File? {
    val c = JFileChooser(startDir)
    c.dialogTitle = "Elegir carpeta para los SF2"
    c.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    c.isAcceptAllFileFilterUsed = false
    return if (c.showDialog(window, "Usar esta carpeta") == JFileChooser.APPROVE_OPTION) c.selectedFile else null
}

// ---- screen ----

@Composable
fun DesktopScreen(
    vm: DesktopViewModel,
    window: ComposeWindow,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onExit: () -> Unit,
) {
    var showExport by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PresetItem?>(null) }
    val openFiles = { vm.addFiles(chooseSf2Files(window, vm.lastDir)) }

    val filtered = vm.filtered()
    val selectedCount = vm.selected.size
    val selectedBytes = vm.items.filter { it.key in vm.selected }.sumOf { it.bytes }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // top bar
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("SF2 Extractor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = openFiles) {
                        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("Abrir SF2", color = MaterialTheme.colorScheme.onPrimary)
                    }
                    TextButton(onClick = onToggleFullscreen) {
                        Text(
                            if (fullscreen) "Salir de pantalla completa (Esc)" else "Pantalla completa (F11)",
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    if (fullscreen) {
                        IconButton(onClick = onExit) { Icon(Icons.Filled.Close, "Cerrar", tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
            }

            if (vm.files.isEmpty()) {
                EmptyState(onOpen = openFiles)
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(vm, filtered, Modifier.width(300.dp).fillMaxHeight())
                    VerticalDivider()
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = vm.query, onValueChange = { vm.query = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Buscar por nombre, instrumento interno o archivo…") }, singleLine = true,
                                leadingIcon = { Icon(Icons.Filled.Search, null) },
                                trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Filled.Close, "Borrar") } },
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("${filtered.size} en lista", style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val listState = rememberLazyListState()
                            LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState) {
                                items(filtered, key = { it.key }) { p ->
                                    PresetRow(p, checked = p.key in vm.selected, dup = vm.nameCounts[p.nameKey] ?: 1,
                                        showFile = vm.files.size > 1, onToggle = { vm.toggle(p.key) }, onDetails = { details = p })
                                }
                            }
                            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                        }
                    }
                }
                // bottom bar
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$selectedCount de ${vm.items.size} marcados", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(16.dp))
                        Text("Aprox. ${formatSize(selectedBytes)} de muestras", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Button(enabled = selectedCount > 0, onClick = { vm.refreshEstimate(); showExport = true }) { Text("Exportar…") }
                    }
                }
            }
        }

        if (fullscreen) FullscreenHint()
    }

    if (showExport) ExportDialog(vm, onDismiss = { showExport = false }, onExport = {
        showExport = false
        if (vm.mode == ExportMode.MERGE) chooseSaveFile(window, vm.lastDir, vm.suggestedFileName())?.let { vm.exportMerged(it) }
        else chooseFolder(window, vm.lastDir)?.let { vm.exportSplit(it) }
    })

    details?.let { p ->
        AlertDialog(
            onDismissRequest = { details = null },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Cerrar") } },
            title = { Text(p.name) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
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
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, modifier = Modifier.widthIn(min = 360.dp, max = 520.dp)) {
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
            text = { Text(m, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        )
    }
}

/** Small banner shown for a few seconds when entering full screen. */
@Composable
private fun FullscreenHint() {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(3500); visible = false }
    if (!visible) return
    Box(Modifier.fillMaxSize().padding(top = 72.dp), contentAlignment = Alignment.TopCenter) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Text("Pulsa Esc para salir de pantalla completa", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun Sidebar(vm: DesktopViewModel, filtered: List<PresetItem>, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Archivos abiertos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        for (f in vm.files) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(f.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("${f.font.presets.size} instrumentos · ${formatSize(f.sizeBytes)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.removeFile(f) }) { Icon(Icons.Filled.Close, "Quitar archivo", Modifier.size(18.dp)) }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Filtrar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        val counts = vm.items.groupingBy { it.category }.eachCount()
        val dupCount = vm.items.count { (vm.nameCounts[it.nameKey] ?: 0) > 1 }
        FilterItem("Todos", vm.items.size, vm.category == null && !vm.onlyDuplicates) { vm.category = null; vm.onlyDuplicates = false }
        if (dupCount > 0) FilterItem("Repetidos", dupCount, vm.onlyDuplicates) { vm.onlyDuplicates = !vm.onlyDuplicates }
        for (c in Category.values()) {
            val n = counts[c] ?: continue
            FilterItem(c.label, n, vm.category == c) { vm.category = if (vm.category == c) null else c }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Selección", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        SideButton("Marcar los ${filtered.size} visibles") { vm.selectAll(filtered) }
        SideButton("Desmarcar los visibles") { vm.deselectAll(filtered) }
        SideButton("Invertir selección visible") { vm.invert(filtered) }
        SideButton("Desmarcar todo") { vm.deselectAll(vm.items) }
        SideButton("Desmarcar duplicados (dejar 1 por nombre)") { vm.dropDuplicateNames() }
    }
}

@Composable
private fun FilterItem(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        badge = { Text("$count") },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(40.dp),
    )
}

@Composable
private fun SideButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Abre uno o varios archivos SF2", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
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
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(String.format(Locale.US, "%03d:%03d", p.bank, p.program), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (dup > 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "×$dup", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(p.category.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(130.dp))
        Text(formatSize(p.bytes), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
        if (showFile) {
            Spacer(Modifier.width(12.dp))
            Text(p.file.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(200.dp))
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
private fun ExportDialog(vm: DesktopViewModel, onDismiss: () -> Unit, onExport: () -> Unit) {
    LaunchedEffect(vm.mode, vm.removeModulators, vm.removeEffects, vm.force16Bit, vm.stereoToMono, vm.maxRate) { vm.refreshEstimate() }
    val has24 = vm.selectedItems().any { it.file.font.has24Bit }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, modifier = Modifier.width(780.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text("Exportar ${vm.selected.size} instrumentos", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.heightIn(max = 460.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
                        Spacer(Modifier.height(10.dp))
                        val est = vm.estimate
                        Text(if (est == null) "Calculando tamaño…" else "Tamaño final aproximado: ${formatSize(est)}", fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onExport) { Text(if (vm.mode == ExportMode.MERGE) "Guardar SF2…" else "Elegir carpeta…") }
                }
            }
        }
    }
}
