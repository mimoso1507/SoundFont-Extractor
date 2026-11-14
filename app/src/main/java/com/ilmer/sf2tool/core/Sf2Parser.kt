package com.ilmer.sf2tool.core

import java.nio.ByteBuffer
import java.nio.charset.Charset

class Sf2Exception(msg: String) : Exception(msg)

object Sf2Parser {
    private val LATIN1: Charset = Charsets.ISO_8859_1

    private class Chunk(val id: String, val dataOffset: Long, val size: Long, val listType: String?)

    private fun fourcc(b: ByteBuffer): String {
        val arr = ByteArray(4); b.get(arr); return String(arr, LATIN1)
    }

    private fun readChildren(src: RandomSource, start: Long, end: Long): List<Chunk> {
        val out = ArrayList<Chunk>()
        var p = start
        while (p + 8 <= end) {
            val h = src.read(p, 8)
            val id = fourcc(h)
            var size = h.int.toLong() and 0xFFFFFFFFL
            val dataOff = p + 8
            if (dataOff + size > end) size = end - dataOff // truncated / malformed files
            var listType: String? = null
            if (id == "LIST" && size >= 4) listType = fourcc(src.read(dataOff, 4))
            out += Chunk(id, dataOff, size, listType)
            p = dataOff + size + (size and 1)
        }
        return out
    }

    fun readName(b: ByteBuffer, len: Int): String {
        val arr = ByteArray(len); b.get(arr)
        var n = arr.indexOf(0.toByte()); if (n < 0) n = len
        return String(arr, 0, n, LATIN1).trim()
    }

    fun parse(src: RandomSource, fallbackName: String): SoundFont {
        if (src.size < 12) throw Sf2Exception("El archivo es demasiado pequeño")
        val head = src.read(0, 12)
        val riff = fourcc(head)
        val riffSize = head.int.toLong() and 0xFFFFFFFFL
        val form = fourcc(head)
        if (riff != "RIFF" || form != "sfbk") throw Sf2Exception("No es un archivo SoundFont (.sf2) válido")
        val riffEnd = minOf(src.size, 8 + riffSize)

        var info: Chunk? = null; var sdta: Chunk? = null; var pdta: Chunk? = null
        for (c in readChildren(src, 12, riffEnd)) {
            if (c.id == "LIST") when (c.listType) {
                "INFO" -> info = c
                "sdta" -> sdta = c
                "pdta" -> pdta = c
            }
        }
        if (pdta == null) throw Sf2Exception("Falta la sección pdta (datos de presets)")

        var name = fallbackName
        var vMaj = 2; var vMin = 1
        info?.let { ic ->
            for (c in readChildren(src, ic.dataOffset + 4, ic.dataOffset + ic.size)) {
                when (c.id) {
                    "ifil" -> if (c.size >= 4) { val b = src.read(c.dataOffset, 4); vMaj = b.short.toInt(); vMin = b.short.toInt() }
                    "INAM" -> if (c.size in 1..1024) {
                        val n = readName(src.read(c.dataOffset, c.size.toInt()), c.size.toInt())
                        if (n.isNotBlank()) name = n
                    }
                }
            }
        }
        if (vMaj >= 3) throw Sf2Exception("Archivo SF3 (comprimido) no soportado; usa un .sf2")

        var smplOff = -1L; var smplSize = 0L; var sm24Off = -1L; var sm24Size = 0L
        sdta?.let { sc ->
            for (c in readChildren(src, sc.dataOffset + 4, sc.dataOffset + sc.size)) {
                when (c.id) {
                    "smpl" -> { smplOff = c.dataOffset; smplSize = c.size }
                    "sm24" -> { sm24Off = c.dataOffset; sm24Size = c.size }
                }
            }
        }
        // sm24 is only valid if it matches the smpl size
        if (sm24Off >= 0 && sm24Size < smplSize / 2) { sm24Off = -1; sm24Size = 0 }

        val sub = HashMap<String, Chunk>()
        for (c in readChildren(src, pdta.dataOffset + 4, pdta.dataOffset + pdta.size)) sub[c.id] = c
        fun load(id: String, rec: Int): Pair<ByteBuffer, Int> {
            val c = sub[id] ?: throw Sf2Exception("Falta el bloque $id")
            val count = (c.size / rec).toInt()
            if (c.size > 64L * 1024 * 1024) throw Sf2Exception("Bloque $id demasiado grande")
            return src.read(c.dataOffset, count * rec) to count
        }

        val (phdrB, phdrN) = load("phdr", 38)
        val (pbagB, pbagN) = load("pbag", 4)
        val (pmodB, pmodN) = load("pmod", 10)
        val (pgenB, pgenN) = load("pgen", 4)
        val (instB, instN) = load("inst", 22)
        val (ibagB, ibagN) = load("ibag", 4)
        val (imodB, imodN) = load("imod", 10)
        val (igenB, igenN) = load("igen", 4)
        val (shdrB, shdrN) = load("shdr", 46)

        val pgens = List(pgenN) { Gen(pgenB.short.toInt() and 0xFFFF, pgenB.short.toInt() and 0xFFFF) }
        val igens = List(igenN) { Gen(igenB.short.toInt() and 0xFFFF, igenB.short.toInt() and 0xFFFF) }
        fun mods(b: ByteBuffer, n: Int) = List(n) {
            Mod(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF,
                b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF)
        }
        val pmods = mods(pmodB, pmodN)
        val imods = mods(imodB, imodN)
        fun bags(b: ByteBuffer, n: Int) = List(n) { intArrayOf(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF) }
        val pbags = bags(pbagB, pbagN)
        val ibags = bags(ibagB, ibagN)

        fun zones(bagStart: Int, bagEnd: Int, bagList: List<IntArray>, gens: List<Gen>, ms: List<Mod>): List<Zone> {
            val out = ArrayList<Zone>()
            for (b in bagStart until bagEnd) {
                if (b + 1 >= bagList.size) break
                val g0 = bagList[b][0].coerceIn(0, gens.size); val g1 = bagList[b + 1][0].coerceIn(g0, gens.size)
                val m0 = bagList[b][1].coerceIn(0, ms.size); val m1 = bagList[b + 1][1].coerceIn(m0, ms.size)
                out += Zone(gens.subList(g0, g1).toList(), ms.subList(m0, m1).toList())
            }
            return out
        }

        data class PH(val name: String, val prog: Int, val bank: Int, val bag: Int, val lib: Long, val genre: Long, val morph: Long)
        val phs = List(phdrN) {
            val n = readName(phdrB, 20)
            PH(n, phdrB.short.toInt() and 0xFFFF, phdrB.short.toInt() and 0xFFFF, phdrB.short.toInt() and 0xFFFF,
                phdrB.int.toLong() and 0xFFFFFFFFL, phdrB.int.toLong() and 0xFFFFFFFFL, phdrB.int.toLong() and 0xFFFFFFFFL)
        }
        val presets = (0 until (phdrN - 1).coerceAtLeast(0)).map { i ->
            val h = phs[i]
            Preset(h.name.ifBlank { "Preset ${h.bank}:${h.prog}" }, h.prog, h.bank, h.lib, h.genre, h.morph,
                zones(h.bag, phs[i + 1].bag, pbags, pgens, pmods))
        }

        val ihs = List(instN) { readName(instB, 20) to (instB.short.toInt() and 0xFFFF) }
        val instruments = (0 until (instN - 1).coerceAtLeast(0)).map { i ->
            Instrument(ihs[i].first.ifBlank { "Instrumento $i" }, zones(ihs[i].second, ihs[i + 1].second, ibags, igens, imods))
        }

        val samples = (0 until (shdrN - 1).coerceAtLeast(0)).map {
            val n = readName(shdrB, 20)
            val s = SampleHeader(
                n,
                shdrB.int.toLong() and 0xFFFFFFFFL, shdrB.int.toLong() and 0xFFFFFFFFL,
                shdrB.int.toLong() and 0xFFFFFFFFL, shdrB.int.toLong() and 0xFFFFFFFFL,
                shdrB.int.toLong() and 0xFFFFFFFFL,
                shdrB.get().toInt() and 0xFF, shdrB.get().toInt(),
                shdrB.short.toInt() and 0xFFFF, shdrB.short.toInt() and 0xFFFF,
            )
            s
        }
        // skip terminal shdr record
        return SoundFont(name, vMaj, vMin, presets, instruments, samples, smplOff, smplSize, sm24Off, sm24Size)
    }
}
