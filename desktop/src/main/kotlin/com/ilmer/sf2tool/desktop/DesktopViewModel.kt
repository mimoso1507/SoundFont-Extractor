package com.ilmer.sf2tool.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ilmer.sf2tool.core.Category
import com.ilmer.sf2tool.core.ExportOptions
import com.ilmer.sf2tool.core.RandomSource
import com.ilmer.sf2tool.core.Selection
import com.ilmer.sf2tool.core.Sf2Exception
import com.ilmer.sf2tool.core.Sf2Parser
import com.ilmer.sf2tool.core.Sf2Writer
import com.ilmer.sf2tool.core.SoundFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File

class LoadedFile(
    val id: Int,
    val displayName: String,
    val file: File,
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

/** Same behaviour as the Android MainViewModel, but working with plain files on disk. */
class DesktopViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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

    /** Folder of the last opened/saved file, so dialogs start there. */
    var lastDir: File? = null

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

    fun addFiles(paths: List<File>) {
        if (paths.isEmpty()) return
        lastDir = paths.first().parentFile
        scope.launch {
            val errors = ArrayList<String>()
            paths.forEachIndexed { n, file ->
                val name = file.name
                busy = Busy("Leyendo $name (${n + 1}/${paths.size})…")
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        RandomSource.of(file).use { src ->
                            val font = Sf2Parser.parse(src, name.substringBeforeLast('.'))
                            LoadedFile(nextId++, name, file, font, src.size)
                        }
                    }
                    files += loaded
                    if (outName == "Mi SoundFont" && files.size == 1) outName = name.substringBeforeLast('.') + " (editado)"
                } catch (e: Throwable) {
                    errors += "$name: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            rebuild()
            busy = null
            if (errors.isNotEmpty()) message = errors.joinToString("\n")
        }
    }

    fun removeFile(f: LoadedFile) {
        files.remove(f)
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
                val src = sources.getOrPut(p.file.id) { RandomSource.of(p.file.file) }
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
        val m = mode
        estimateJob = scope.launch {
            estimate = withContext(Dispatchers.IO) {
                try {
                    if (m == ExportMode.MERGE) withSources(sel) { Sf2Writer(it, opts).estimateSize() }
                    else sel.sumOf { p -> withSources(listOf(p)) { Sf2Writer(it, opts).estimateSize() } }
                } catch (_: Exception) { null }
            }
        }
    }

    fun suggestedFileName(): String =
        outName.ifBlank { "soundfont" }.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".sf2"

    fun exportMerged(target: File) {
        val sel = selectedItems()
        if (sel.isEmpty()) { message = "No hay instrumentos marcados"; return }
        if (sel.any { it.file.file.canonicalFile == target.canonicalFile }) {
            message = "No se puede sobrescribir un archivo que está abierto. Elige otro nombre."
            return
        }
        lastDir = target.parentFile
        val opts = options()
        scope.launch {
            busy = Busy("Creando SF2…", 0f)
            try {
                withContext(Dispatchers.IO) {
                    withSources(sel) { selections ->
                        BufferedOutputStream(target.outputStream(), 1 shl 20).use { bos ->
                            Sf2Writer(selections, opts).write(bos) { busy = Busy("Creando SF2…", it) }
                        }
                    }
                }
                message = "Listo: se guardó un SF2 con ${sel.size} instrumentos en\n${target.absolutePath}"
            } catch (e: Throwable) {
                runCatching { target.delete() }
                message = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifBlank { "instrumento" }.take(80)

    fun exportSplit(dir: File) {
        val sel = selectedItems()
        if (sel.isEmpty()) { message = "No hay instrumentos marcados"; return }
        lastDir = dir
        val opts = options()
        scope.launch {
            busy = Busy("Separando…", 0f)
            var ok = 0
            val errors = ArrayList<String>()
            try {
                withContext(Dispatchers.IO) {
                    if (!dir.isDirectory && !dir.mkdirs()) throw Sf2Exception("Carpeta no válida")
                    val existing = (dir.list() ?: emptyArray()).mapTo(HashSet()) { it.lowercase() }
                    val multiFile = sel.map { it.file.id }.distinct().size > 1
                    sel.forEachIndexed { i, p ->
                        busy = Busy("Separando ${i + 1}/${sel.size}: ${p.name}", i.toFloat() / sel.size)
                        var base = safeName(p.name)
                        if (multiFile || (nameCounts[p.nameKey] ?: 0) > 1) base = "$base - ${safeName(p.file.displayName.substringBeforeLast('.'))}"
                        var name = "$base.sf2"; var n = 2
                        while (name.lowercase() in existing) { name = "$base ($n).sf2"; n++ }
                        existing += name.lowercase()
                        val out = File(dir, name)
                        try {
                            withSources(listOf(p)) { selections ->
                                BufferedOutputStream(out.outputStream(), 1 shl 20).use { bos ->
                                    Sf2Writer(selections, opts.copy(name = p.name)).write(bos) { f ->
                                        busy = Busy("Separando ${i + 1}/${sel.size}: ${p.name}", (i + f) / sel.size)
                                    }
                                }
                            }
                            ok++
                        } catch (e: Throwable) {
                            runCatching { out.delete() }
                            errors += "${p.name}: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }
                message = buildString {
                    append("Listo: se crearon $ok archivos SF2 en\n${dir.absolutePath}")
                    if (errors.isNotEmpty()) append("\nErrores:\n").append(errors.take(10).joinToString("\n"))
                }
            } catch (e: Throwable) {
                message = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }
}
