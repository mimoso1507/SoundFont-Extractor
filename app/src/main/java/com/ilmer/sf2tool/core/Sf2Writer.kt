package com.ilmer.sf2tool.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.roundToLong

data class ExportOptions(
    val name: String = "SF2 Extractor",
    /** Drop all modulators (pmod/imod). Synths still apply their default modulators. */
    val removeModulators: Boolean = false,
    /** Drop reverb and chorus send generators. */
    val removeEffects: Boolean = false,
    /** Drop the 24-bit extension (sm24). */
    val force16Bit: Boolean = false,
    /** Keep only the left channel of stereo samples. */
    val stereoToMono: Boolean = false,
    /** Downsample samples above this rate (0 = keep original). */
    val maxSampleRate: Int = 0,
    /** Move presets whose bank/program collide to a free program number. */
    val renumberConflicts: Boolean = true,
)

/** One preset to copy, with an open reader on the file it comes from. */
class Selection(val font: SoundFont, val source: RandomSource, val presetIndex: Int)

class Sf2Writer(private val selections: List<Selection>, private val opts: ExportOptions) {

    private class NewSample(
        val font: SoundFont, val source: RandomSource, val hdr: SampleHeader,
        val srcStart: Long, val srcLen: Int, val ratio: Double,
    ) {
        var newLen = 0
        /** Zero points after the sample (>= 46, keeps every block even-sized). */
        val pad: Int get() = 46 + (newLen and 1)
        var newStart = 0L
        var newRate = 0L
        var loopStartRel = 0L
        var loopEndRel = 0L
        var type = SampleType.MONO
        var link = 0
    }

    private class NewZone(val gens: List<Gen>, val mods: List<Mod>)
    private class NewPreset(val name: String, var program: Int, var bank: Int, val src: Preset, val zones: List<NewZone>)
    private class NewInst(val name: String, val zones: List<NewZone>)

    private val fontIds = IdentityHashMap<SoundFont, Int>()
    private val instMap = HashMap<Long, Int>()
    private val instSrc = ArrayList<Pair<Selection, Int>>()
    private val sampleMap = HashMap<Long, Int>()
    private val samples = ArrayList<NewSample>()
    private val insts = ArrayList<NewInst>()
    private val presets = ArrayList<NewPreset>()
    private var use24 = false

    private fun key(font: SoundFont, idx: Int): Long =
        (fontIds.getOrPut(font) { fontIds.size }.toLong() shl 32) or idx.toLong()

    /** Number of bytes of sample data that will be written (for progress and size estimate). */
    var sampleDataBytes = 0L
        private set

    private fun filterGens(gens: List<Gen>): List<Gen> =
        if (opts.removeEffects) gens.filter { it.oper != GenOp.CHORUS_SEND && it.oper != GenOp.REVERB_SEND } else gens

    private fun orderGens(gens: List<Gen>): List<Gen> {
        val key = gens.filter { it.oper == GenOp.KEY_RANGE }.take(1)
        val vel = gens.filter { it.oper == GenOp.VEL_RANGE }.take(1)
        val last = gens.filter { it.oper == GenOp.INSTRUMENT || it.oper == GenOp.SAMPLE_ID }.take(1)
        val mid = gens.filter { it.oper != GenOp.KEY_RANGE && it.oper != GenOp.VEL_RANGE && it.oper != GenOp.INSTRUMENT && it.oper != GenOp.SAMPLE_ID }
        return key + vel + mid + last
    }

    private fun mods(m: List<Mod>) = if (opts.removeModulators) emptyList() else m

    private fun addSample(sel: Selection, idx: Int): Int {
        val k = key(sel.font, idx)
        sampleMap[k]?.let { return it }
        val font = sel.font
        val h = font.samples[idx]
        val avail = font.totalSamplePoints
        val start = h.start.coerceIn(0, avail)
        val end = h.end.coerceIn(start, avail)
        val len = (end - start).coerceAtMost(Int.MAX_VALUE.toLong() - 64).toInt()
        val ratio = if (opts.maxSampleRate > 0 && h.sampleRate > opts.maxSampleRate && len > 0)
            opts.maxSampleRate.toDouble() / h.sampleRate else 1.0
        val ns = NewSample(font, sel.source, h, start, len, ratio)
        ns.newLen = if (ratio < 1.0) ceil(len * ratio).toInt().coerceAtLeast(1) else len
        ns.newRate = if (ratio < 1.0) opts.maxSampleRate.toLong() else h.sampleRate
        ns.loopStartRel = ((h.startLoop - start) * ratio).roundToLong().coerceIn(0, ns.newLen.toLong())
        ns.loopEndRel = ((h.endLoop - start) * ratio).roundToLong().coerceIn(0, ns.newLen.toLong())
        val newIdx = samples.size
        samples += ns
        sampleMap[k] = newIdx
        if (font.has24Bit && !opts.force16Bit) use24 = true
        return newIdx
    }

    private fun scaleOffsets(gens: List<Gen>, ratio: Double): List<Gen> {
        if (ratio >= 1.0) return gens
        val pairs = listOf(
            GenOp.START_ADDRS_OFFSET to GenOp.START_ADDRS_COARSE,
            GenOp.END_ADDRS_OFFSET to GenOp.END_ADDRS_COARSE,
            GenOp.STARTLOOP_ADDRS_OFFSET to GenOp.STARTLOOP_ADDRS_COARSE,
            GenOp.ENDLOOP_ADDRS_OFFSET to GenOp.ENDLOOP_ADDRS_COARSE,
        )
        var out = gens
        for ((fine, coarse) in pairs) {
            val f = out.firstOrNull { it.oper == fine }
            val c = out.firstOrNull { it.oper == coarse }
            if (f == null && c == null) continue
            val total = (f?.signed ?: 0).toLong() + (c?.signed ?: 0).toLong() * 32768L
            val scaled = (total * ratio).roundToLong()
            val nc = (scaled / 32768L).toInt()
            val nf = (scaled - nc * 32768L).toInt()
            out = out.filter { it.oper != fine && it.oper != coarse }
            if (nf != 0) out = out + Gen(fine, nf and 0xFFFF)
            if (nc != 0) out = out + Gen(coarse, nc and 0xFFFF)
        }
        return out
    }

    private fun buildInstrument(sel: Selection, instIdx: Int): NewInst {
        val font = sel.font
        val inst = font.instruments[instIdx]
        val referenced = inst.zones.mapNotNull { it.gen(GenOp.SAMPLE_ID)?.unsigned }.filter { it in font.samples.indices }.toSet()
        val zones = ArrayList<NewZone>()
        inst.zones.forEachIndexed { zi, z ->
            val sid = z.gen(GenOp.SAMPLE_ID)?.unsigned
            if (sid == null) {
                if (zi == 0) zones += NewZone(orderGens(filterGens(z.gens)), mods(z.mods)) // global zone
                return@forEachIndexed
            }
            if (sid !in font.samples.indices) return@forEachIndexed
            val s = font.samples[sid]
            if (opts.stereoToMono && s.isRight && s.sampleLink in referenced && font.samples.getOrNull(s.sampleLink)?.isLeft == true) {
                return@forEachIndexed // the left channel zone covers this one
            }
            val newSid = addSample(sel, sid)
            if (!opts.stereoToMono && s.isStereoPart && s.sampleLink in font.samples.indices) addSample(sel, s.sampleLink)
            var gens = filterGens(z.gens).filter { it.oper != GenOp.SAMPLE_ID }
            if (opts.stereoToMono && s.isStereoPart) gens = gens.filter { it.oper != GenOp.PAN }
            gens = scaleOffsets(gens, samples[newSid].ratio)
            zones += NewZone(orderGens(gens + Gen(GenOp.SAMPLE_ID, newSid)), mods(z.mods))
        }
        return NewInst(inst.name, zones)
    }

    private fun prepare() {
        val used = HashSet<Int>()
        for (sel in selections) {
            val p = sel.font.presets[sel.presetIndex]
            val zones = ArrayList<NewZone>()
            p.zones.forEachIndexed { zi, z ->
                val ii = z.gen(GenOp.INSTRUMENT)?.unsigned
                if (ii == null) {
                    if (zi == 0) zones += NewZone(orderGens(filterGens(z.gens)), mods(z.mods))
                    return@forEachIndexed
                }
                if (ii !in sel.font.instruments.indices) return@forEachIndexed
                val k = key(sel.font, ii)
                val newIi = instMap.getOrPut(k) {
                    instSrc += sel to ii
                    instSrc.size - 1
                }
                val gens = filterGens(z.gens).filter { it.oper != GenOp.INSTRUMENT } + Gen(GenOp.INSTRUMENT, newIi)
                zones += NewZone(orderGens(gens), mods(z.mods))
            }
            var bank = p.bank; var prog = p.program
            if (opts.renumberConflicts) {
                var tries = 0
                while ((bank shl 8 or prog) in used && tries < 128 * 64) {
                    prog++
                    if (prog > 127) { prog = 0; if (bank != 128) bank++ }
                    tries++
                }
            }
            used += (bank shl 8 or prog)
            presets += NewPreset(p.name, prog, bank, p, zones)
        }
        for ((sel, ii) in instSrc) insts += buildInstrument(sel, ii)

        // sample types / links and layout
        val back = HashMap<NewSample, Int>()
        samples.forEachIndexed { i, s -> back[s] = i }
        var offset = 0L
        for (s in samples) {
            val h = s.hdr
            val partner = if (h.isStereoPart && !opts.stereoToMono) sampleMap[key(s.font, h.sampleLink)] else null
            if (partner != null) { s.type = h.sampleType and 0x7FFF; s.link = partner }
            else { s.type = SampleType.MONO; s.link = 0 }
            s.newStart = offset
            offset += s.newLen + s.pad
        }
        val totalPoints = offset
        sampleDataBytes = totalPoints * 2 + if (use24) totalPoints else 0
        if (samples.size > 65534) throw Sf2Exception("Demasiadas muestras (${samples.size}); el formato SF2 admite hasta 65534")
        if (insts.size > 65534) throw Sf2Exception("Demasiados instrumentos (${insts.size})")
        presets.sortWith(compareBy({ it.bank }, { it.program }))
    }

    fun estimateSize(): Long {
        if (presets.isEmpty()) prepare()
        return sampleDataBytes + buildPdta().size + 512
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun name20(b: ByteBuffer, s: String) {
        val bytes = s.toByteArray(Charsets.ISO_8859_1).copyOf(20)
        b.put(bytes)
    }

    private fun chunk(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        val h = le(8); h.put(id.toByteArray(Charsets.ISO_8859_1)); h.putInt(data.size)
        out.write(h.array()); out.write(data)
        if (data.size % 2 == 1) out.write(0)
    }

    private fun buildPdta(): ByteArray {
        // presets
        val phdr = ByteArrayOutputStream(); val pbag = ByteArrayOutputStream()
        val pmod = ByteArrayOutputStream(); val pgen = ByteArrayOutputStream()
        var bagI = 0; var genI = 0; var modI = 0
        fun bagRec(o: ByteArrayOutputStream, g: Int, m: Int) {
            if (g > 0xFFFF || m > 0xFFFF) throw Sf2Exception("El archivo resultante supera el límite de generadores del formato SF2")
            o.write(le(4).putShort(g.toShort()).putShort(m.toShort()).array())
        }
        fun genRec(o: ByteArrayOutputStream, g: Gen) = o.write(le(4).putShort(g.oper.toShort()).putShort(g.amount.toShort()).array())
        fun modRec(o: ByteArrayOutputStream, m: Mod) = o.write(le(10).putShort(m.src.toShort()).putShort(m.dest.toShort())
            .putShort(m.amount.toShort()).putShort(m.amtSrc.toShort()).putShort(m.trans.toShort()).array())

        for (p in presets) {
            val b = le(38); name20(b, p.name)
            b.putShort(p.program.toShort()); b.putShort(p.bank.toShort()); b.putShort(bagI.toShort())
            b.putInt(p.src.library.toInt()); b.putInt(p.src.genre.toInt()); b.putInt(p.src.morphology.toInt())
            phdr.write(b.array())
            if (bagI > 0xFFFF) throw Sf2Exception("Demasiadas zonas de presets")
            for (z in p.zones) {
                bagRec(pbag, genI, modI)
                z.gens.forEach { genRec(pgen, it) }; z.mods.forEach { modRec(pmod, it) }
                genI += z.gens.size; modI += z.mods.size; bagI++
            }
        }
        run { val b = le(38); name20(b, "EOP"); b.putShort(0); b.putShort(0); b.putShort(bagI.toShort()); b.putInt(0); b.putInt(0); b.putInt(0); phdr.write(b.array()) }
        bagRec(pbag, genI, modI)
        pmod.write(ByteArray(10)); pgen.write(ByteArray(4))

        // instruments
        val inst = ByteArrayOutputStream(); val ibag = ByteArrayOutputStream()
        val imod = ByteArrayOutputStream(); val igen = ByteArrayOutputStream()
        bagI = 0; genI = 0; modI = 0
        for (ins in insts) {
            val b = le(22); name20(b, ins.name); b.putShort(bagI.toShort()); inst.write(b.array())
            if (bagI > 0xFFFF) throw Sf2Exception("Demasiadas zonas de instrumentos")
            for (z in ins.zones) {
                bagRec(ibag, genI, modI)
                z.gens.forEach { genRec(igen, it) }; z.mods.forEach { modRec(imod, it) }
                genI += z.gens.size; modI += z.mods.size; bagI++
            }
        }
        run { val b = le(22); name20(b, "EOI"); b.putShort(bagI.toShort()); inst.write(b.array()) }
        bagRec(ibag, genI, modI)
        imod.write(ByteArray(10)); igen.write(ByteArray(4))

        // samples
        val shdr = ByteArrayOutputStream()
        for (s in samples) {
            val b = le(46); name20(b, s.hdr.name)
            b.putInt(s.newStart.toInt()); b.putInt((s.newStart + s.newLen).toInt())
            b.putInt((s.newStart + s.loopStartRel).toInt()); b.putInt((s.newStart + s.loopEndRel).toInt())
            b.putInt(s.newRate.toInt()); b.put(s.hdr.originalPitch.toByte()); b.put(s.hdr.pitchCorrection.toByte())
            b.putShort(s.link.toShort()); b.putShort(s.type.toShort())
            shdr.write(b.array())
        }
        run { val b = le(46); name20(b, "EOS"); shdr.write(b.array()) }

        val body = ByteArrayOutputStream()
        body.write("pdta".toByteArray())
        chunk(body, "phdr", phdr.toByteArray()); chunk(body, "pbag", pbag.toByteArray())
        chunk(body, "pmod", pmod.toByteArray()); chunk(body, "pgen", pgen.toByteArray())
        chunk(body, "inst", inst.toByteArray()); chunk(body, "ibag", ibag.toByteArray())
        chunk(body, "imod", imod.toByteArray()); chunk(body, "igen", igen.toByteArray())
        chunk(body, "shdr", shdr.toByteArray())
        return body.toByteArray()
    }

    private fun zstr(s: String): ByteArray {
        val b = s.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray()
        val n = b.size + 1
        return b.copyOf(if (n % 2 == 1) n + 1 else n)
    }

    private fun buildInfo(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("INFO".toByteArray())
        chunk(body, "ifil", le(4).putShort(2).putShort(if (use24) 4 else 1).array())
        chunk(body, "isng", zstr("EMU8000"))
        chunk(body, "INAM", zstr(opts.name.ifBlank { "SF2 Extractor" }))
        chunk(body, "ISFT", zstr("SF2 Extractor (Android)"))
        return body.toByteArray()
    }

    /** Reads one sample as 24-bit integer values (16-bit samples are shifted left by 8). */
    private fun readSample(s: NewSample): IntArray {
        val n = s.srcLen
        val out = IntArray(n)
        if (n == 0) return out
        val font = s.font
        val buf = s.source.read(font.smplOffset + s.srcStart * 2, n * 2)
        val sb = buf.asShortBuffer()
        for (i in 0 until n) out[i] = sb.get(i).toInt() shl 8
        if (use24 && font.has24Bit) {
            val lo = s.source.read(font.sm24Offset + s.srcStart, n)
            for (i in 0 until n) out[i] = out[i] or (lo.get(i).toInt() and 0xFF)
        }
        return out
    }

    private fun resample(src: IntArray, s: NewSample): IntArray {
        if (s.ratio >= 1.0) return src
        val out = IntArray(s.newLen)
        val step = 1.0 / s.ratio
        for (n in 0 until s.newLen) {
            val a = (n * step).toInt().coerceAtMost(src.size - 1)
            val b = ((n + 1) * step).toInt().coerceIn(a + 1, src.size)
            var sum = 0L
            for (i in a until b) sum += src[i]
            out[n] = (sum / (b - a)).toInt()
        }
        return out
    }

    private fun writeLE32(out: OutputStream, v: Long) {
        out.write(le(4).putInt(v.toInt()).array())
    }

    /**
     * Writes the SoundFont. [progress] receives values 0..1.
     */
    fun write(out: OutputStream, progress: (Float) -> Unit = {}) {
        if (presets.isEmpty()) prepare()
        if (presets.isEmpty()) throw Sf2Exception("No hay instrumentos seleccionados")
        val info = buildInfo()
        val pdta = buildPdta()
        val totalPoints = samples.sumOf { it.newLen.toLong() + it.pad }
        val smplBytes = totalPoints * 2
        val sm24Bytes = if (use24) totalPoints else 0L
        val sm24Padded = sm24Bytes + (sm24Bytes and 1)
        val sdtaSize = 4 + 8 + smplBytes + if (use24) 8 + sm24Padded else 0
        val riffSize = 4 + (8 + info.size) + (8 + sdtaSize) + (8 + pdta.size)
        if (riffSize > 0xFFFFFFF0L) throw Sf2Exception("El archivo resultante superaría 4 GB")

        out.write("RIFF".toByteArray()); writeLE32(out, riffSize); out.write("sfbk".toByteArray())
        out.write("LIST".toByteArray()); writeLE32(out, info.size.toLong()); out.write(info)
        out.write("LIST".toByteArray()); writeLE32(out, sdtaSize); out.write("sdta".toByteArray())
        out.write("smpl".toByteArray()); writeLE32(out, smplBytes)

        val total = (smplBytes + sm24Bytes).coerceAtLeast(1).toFloat()
        var done = 0L
        val pad16 = ByteArray(47 * 2)
        for (s in samples) {
            if (s.ratio >= 1.0) {
                // straight copy, in chunks
                var pos = s.font.smplOffset + s.srcStart * 2
                var left = s.srcLen * 2L
                while (left > 0) {
                    val n = minOf(left, 1L shl 20).toInt()
                    val b = s.source.read(pos, n)
                    out.write(b.array(), 0, n)
                    pos += n; left -= n
                }
            } else {
                val data = resample(readSample(s), s)
                val b = le(data.size * 2)
                for (v in data) b.putShort((v shr 8).toShort())
                out.write(b.array())
            }
            out.write(pad16, 0, s.pad * 2)
            done += (s.newLen.toLong() + s.pad) * 2
            progress(done / total)
        }
        if (use24) {
            out.write("sm24".toByteArray()); writeLE32(out, sm24Bytes)
            val pad8 = ByteArray(47)
            for (s in samples) {
                if (!s.font.has24Bit) {
                    var left = s.newLen.toLong()
                    while (left > 0) { val n = minOf(left, 1L shl 20).toInt(); out.write(ByteArray(n)); left -= n }
                } else if (s.ratio >= 1.0) {
                    var pos = s.font.sm24Offset + s.srcStart
                    var left = s.srcLen.toLong()
                    while (left > 0) {
                        val n = minOf(left, 1L shl 20).toInt()
                        out.write(s.source.read(pos, n).array(), 0, n)
                        pos += n; left -= n
                    }
                } else {
                    val data = resample(readSample(s), s)
                    out.write(ByteArray(data.size) { (data[it] and 0xFF).toByte() })
                }
                out.write(pad8, 0, s.pad)
                done += s.newLen.toLong() + s.pad
                progress(done / total)
            }
            if (sm24Bytes and 1L == 1L) out.write(0)
        }
        out.write("LIST".toByteArray()); writeLE32(out, pdta.size.toLong()); out.write(pdta)
        out.flush()
        progress(1f)
    }
}
