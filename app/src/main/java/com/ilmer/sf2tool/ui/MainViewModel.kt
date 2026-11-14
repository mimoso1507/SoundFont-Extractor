package com.ilmer.sf2tool.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilmer.sf2tool.core.Category
import com.ilmer.sf2tool.core.ExportOptions
import com.ilmer.sf2tool.core.RandomSource
import com.ilmer.sf2tool.core.Selection
import com.ilmer.sf2tool.core.Sf2Exception
import com.ilmer.sf2tool.core.Sf2Parser
import com.ilmer.sf2tool.core.Sf2Writer
import com.ilmer.sf2tool.core.SoundFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream

class LoadedFile(
    val id: Int,
    val displayName: String,
    val uri: Uri,
    val cacheFile: File?,
    val font: SoundFont,
    val sizeBytes: Long,
)

class PresetItem(
    val key: String,
    val file: LoadedFile,
    val index: Int,
    val name: String,
    val bank: Int,
    val program: Int,
    val category: Category,
    val bytes: Long,
    val instrumentNames: List<String>,
    val sampleCount: Int,
) {
    val nameKey: String = name.trim().lowercase()
}

enum class ExportMode { MERGE, SPLIT }

data class Busy(val message: String, val progress: Float? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cr get() = getApplication<Application>().contentResolver
    private var nextId = 1

    val files = mutableStateListOf<LoadedFile>()
    var items by mutableStateOf<List<PresetItem>>(emptyList()); private set
    var nameCounts by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set

    var query by mutableStateOf("")
    var category by mutableStateOf<Category?>(null)
    var onlyDuplicates by mutableStateOf(false)

    var busy by mutableStateOf<Busy?>(null); private set
    var message by mutableStateOf<String?>(null)

    // export options
    var mode by mutableStateOf(ExportMode.MERGE)
    var outName by mutableStateOf("Mi SoundFont")
    var removeModulators by mutableStateOf(false)
    var removeEffects by mutableStateOf(false)
    var force16Bit by mutableStateOf(false)
    var stereoToMono by mutableStateOf(false)
    var maxRate by mutableStateOf(0)
    var renumber by mutableStateOf(true)
    var estimate by mutableStateOf<Long?>(null); private set
    private var estimateJob: Job? = null

    fun filtered(): List<PresetItem> {
        val q = query.trim().lowercase()
        return items.filter { p ->
            (category == null || p.category == category) &&
                (!onlyDuplicates || (nameCounts[p.nameKey] ?: 0) > 1) &&
                (q.isEmpty() || p.nameKey.contains(q) || p.instrumentNames.any { it.lowercase().contains(q) } ||
                    p.file.displayName.lowercase().contains(q))
        }
    }

    private fun rebuild() {
        val list = ArrayList<PresetItem>()
        for (f in files) {
            val font = f.font
            val sorted = font.presets.indices.sortedWith(compareBy({ font.presets[it].bank }, { font.presets[it].program }))
            for (i in sorted) {
                val p = font.presets[i]
                val insts = font.instrumentsOf(p)
                list += PresetItem(
                    key = "${f.id}:$i", file = f, index = i, name = p.name, bank = p.bank, program = p.program,
                    category = Category.of(p.bank, p.program, p.name), bytes = font.sampleBytesOf(p),
                    instrumentNames = insts.map { font.instruments[it].name },
                    sampleCount = font.samplesOfPreset(p).size,
                )
            }
        }
        items = list
        nameCounts = list.groupingBy { it.nameKey }.eachCount()
        val keys = list.mapTo(HashSet()) { it.key }
        selected = selected.filterTo(HashSet()) { it in keys }
    }

    private fun displayName(uri: Uri): String {
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo.sf2"
    }

    private fun openDirect(uri: Uri): RandomSource {
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw Sf2Exception("No se pudo abrir el archivo")
        if (pfd.statSize <= 0) { pfd.close(); throw java.io.IOException("not seekable") }
        val fis = FileInputStream(pfd.fileDescriptor)
        return RandomSource(fis.channel, Closeable { fis.close(); pfd.close() })
    }

    fun openSource(f: LoadedFile): RandomSource =
        if (f.cacheFile != null) RandomSource.of(f.cacheFile) else openDirect(f.uri)

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val errors = ArrayList<String>()
            uris.forEachIndexed { n, uri ->
                val name = displayName(uri)
                busy = Busy("Leyendo $name (${n + 1}/${uris.size})…")
                try {
                    val loaded = withContext(Dispatchers.IO) { load(uri, name) }
                    files += loaded
                    if (outName == "Mi SoundFont" && files.size == 1) outName = name.substringBeforeLast('.') + " (editado)"
                } catch (e: Sf2Exception) {
                    errors += "$name: ${e.message}"
                } catch (e: Throwable) {
                    errors += "$name: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            withContext(Dispatchers.Default) { }
            rebuild()
            busy = null
            if (errors.isNotEmpty()) message = errors.joinToString("\n")
        }
    }

    private fun load(uri: Uri, name: String): LoadedFile {
        try {
            openDirect(uri).use { src ->
                val font = Sf2Parser.parse(src, name.substringBeforeLast('.'))
                return LoadedFile(nextId++, name, uri, null, font, src.size)
            }
        } catch (e: Sf2Exception) {
            throw e
        } catch (_: Exception) {
            // Provider does not give a seekable file (e.g. some cloud apps): copy it to the cache
        }
        val dir = File(getApplication<Application>().cacheDir, "sf2").apply { mkdirs() }
        val f = File(dir, "src_${System.nanoTime()}.sf2")
        cr.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ?: throw Sf2Exception("No se pudo leer el archivo")
        try {
            RandomSource.of(f).use { src ->
                val font = Sf2Parser.parse(src, name.substringBeforeLast('.'))
                return LoadedFile(nextId++, name, uri, f, font, src.size)
            }
        } catch (e: Exception) {
            f.delete(); throw e
        }
    }

    fun removeFile(f: LoadedFile) {
        files.remove(f)
        f.cacheFile?.delete()
        rebuild()
    }

    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    fun selectAll(list: List<PresetItem>) { selected = selected + list.map { it.key } }
    fun deselectAll(list: List<PresetItem>) { selected = selected - list.map { it.key }.toSet() }
    fun invert(list: List<PresetItem>) {
        val s = selected.toMutableSet()
        for (p in list) if (!s.add(p.key)) s.remove(p.key)
        selected = s
    }

    /** Among the selected presets keep only the first one of each name. */
    fun dropDuplicateNames() {
        val seen = HashSet<String>()
        val keep = HashSet<String>()
        for (p in items) if (p.key in selected && seen.add(p.nameKey)) keep += p.key
        val removed = selected.size - keep.size
        selected = keep
        message = if (removed > 0) "Se desmarcaron $removed duplicados" else "No hay duplicados marcados"
    }

    fun selectedItems(): List<PresetItem> = items.filter { it.key in selected }

    private fun options() = ExportOptions(
        name = outName.ifBlank { "SF2 Extractor" },
        removeModulators = removeModulators, removeEffects = removeEffects, force16Bit = force16Bit,
        stereoToMono = stereoToMono, maxSampleRate = maxRate, renumberConflicts = renumber,
    )

    private inline fun <T> withSources(sel: List<PresetItem>, block: (List<Selection>) -> T): T {
        val sources = HashMap<Int, RandomSource>()
        try {
            val selections = sel.map { p ->
                val src = sources.getOrPut(p.file.id) { openSource(p.file) }
                Selection(p.file.font, src, p.index)
            }
            return block(selections)
        } finally {
            sources.values.forEach { runCatching { it.close() } }
        }
    }

    fun refreshEstimate() {
        estimateJob?.cancel()
        val sel = selectedItems()
        if (sel.isEmpty()) { estimate = 0; return }
        estimate = null
        val opts = options()
        estimateJob = viewModelScope.launch {
            estimate = withContext(Dispatchers.IO) {
                try {
                    if (mode == ExportMode.MERGE) withSources(sel) { Sf2Writer(it, opts).estimateSize() }
                    else sel.sumOf { p -> withSources(listOf(p)) { Sf2Writer(it, opts).estimateSize() } }
                } catch (_: Exception) { null }
            }
        }
    }

    fun exportMerged(target: Uri) {
        val sel = selectedItems()
        if (sel.isEmpty()) { message = "No hay instrumentos marcados"; return }
        val opts = options()
        viewModelScope.launch {
            busy = Busy("Creando SF2…", 0f)
            try {
                withContext(Dispatchers.IO) {
                    withSources(sel) { selections ->
                        val out = cr.openOutputStream(target, "wt") ?: throw Sf2Exception("No se pudo crear el archivo")
                        BufferedOutputStream(out, 1 shl 20).use { bos ->
                            Sf2Writer(selections, opts).write(bos) { busy = Busy("Creando SF2…", it) }
                        }
                    }
                }
                message = "Listo: se guardó un SF2 con ${sel.size} instrumentos"
            } catch (e: Throwable) {
                runCatching { DocumentFile.fromSingleUri(getApplication(), target)?.delete() }
                message = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifBlank { "instrumento" }.take(80)

    fun exportSplit(treeUri: Uri) {
        val sel = selectedItems()
        if (sel.isEmpty()) { message = "No hay instrumentos marcados"; return }
        val opts = options()
        viewModelScope.launch {
            busy = Busy("Separando…", 0f)
            var ok = 0
            val errors = ArrayList<String>()
            try {
                withContext(Dispatchers.IO) {
                    val dir = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: throw Sf2Exception("Carpeta no válida")
                    val existing = dir.listFiles().mapNotNullTo(HashSet()) { it.name?.lowercase() }
                    val multiFile = sel.map { it.file.id }.distinct().size > 1
                    sel.forEachIndexed { i, p ->
                        busy = Busy("Separando ${i + 1}/${sel.size}: ${p.name}", i.toFloat() / sel.size)
                        var base = safeName(p.name)
                        if (multiFile || (nameCounts[p.nameKey] ?: 0) > 1) base = "$base - ${safeName(p.file.displayName.substringBeforeLast('.'))}"
                        var name = "$base.sf2"; var n = 2
                        while (name.lowercase() in existing) { name = "$base ($n).sf2"; n++ }
                        existing += name.lowercase()
                        try {
                            val doc = dir.createFile("application/octet-stream", name) ?: throw Sf2Exception("No se pudo crear $name")
                            try {
                                withSources(listOf(p)) { selections ->
                                    val out = cr.openOutputStream(doc.uri, "wt") ?: throw Sf2Exception("No se pudo escribir $name")
                                    BufferedOutputStream(out, 1 shl 20).use { bos ->
                                        Sf2Writer(selections, opts.copy(name = p.name)).write(bos) { f ->
                                            busy = Busy("Separando ${i + 1}/${sel.size}: ${p.name}", (i + f) / sel.size)
                                        }
                                    }
                                }
                                ok++
                            } catch (e: Throwable) {
                                runCatching { doc.delete() }; throw e
                            }
                        } catch (e: Throwable) {
                            errors += "${p.name}: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }
                message = buildString {
                    append("Listo: se crearon $ok archivos SF2")
                    if (errors.isNotEmpty()) append("\nErrores:\n").append(errors.take(10).joinToString("\n"))
                }
            } catch (e: Throwable) {
                message = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    override fun onCleared() {
        files.forEach { it.cacheFile?.delete() }
    }
}
