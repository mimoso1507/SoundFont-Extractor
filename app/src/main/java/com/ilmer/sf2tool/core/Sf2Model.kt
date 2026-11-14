package com.ilmer.sf2tool.core

/** Raw SoundFont 2 generator: operator + 16-bit amount (stored as signed short bits). */
data class Gen(val oper: Int, val amount: Int) {
    val lo: Int get() = amount and 0xFF
    val hi: Int get() = (amount shr 8) and 0xFF
    val signed: Int get() = amount.toShort().toInt()
    val unsigned: Int get() = amount and 0xFFFF
}

data class Mod(val src: Int, val dest: Int, val amount: Int, val amtSrc: Int, val trans: Int)

class Zone(val gens: List<Gen>, val mods: List<Mod>) {
    fun gen(oper: Int): Gen? = gens.firstOrNull { it.oper == oper }
}

class Preset(
    val name: String,
    val program: Int,
    val bank: Int,
    val library: Long,
    val genre: Long,
    val morphology: Long,
    val zones: List<Zone>,
)

class Instrument(val name: String, val zones: List<Zone>)

class SampleHeader(
    val name: String,
    val start: Long,
    val end: Long,
    val startLoop: Long,
    val endLoop: Long,
    val sampleRate: Long,
    val originalPitch: Int,
    val pitchCorrection: Int,
    val sampleLink: Int,
    val sampleType: Int,
) {
    val length: Long get() = (end - start).coerceAtLeast(0)
    val isRom: Boolean get() = sampleType and 0x8000 != 0
    val isRight: Boolean get() = sampleType and 0x7FFF == SampleType.RIGHT
    val isLeft: Boolean get() = sampleType and 0x7FFF == SampleType.LEFT
    val isStereoPart: Boolean get() = isRight || isLeft
}

object SampleType {
    const val MONO = 1
    const val RIGHT = 2
    const val LEFT = 4
    const val LINKED = 8
}

object GenOp {
    const val START_ADDRS_OFFSET = 0
    const val END_ADDRS_OFFSET = 1
    const val STARTLOOP_ADDRS_OFFSET = 2
    const val ENDLOOP_ADDRS_OFFSET = 3
    const val START_ADDRS_COARSE = 4
    const val END_ADDRS_COARSE = 12
    const val CHORUS_SEND = 15
    const val REVERB_SEND = 16
    const val PAN = 17
    const val INSTRUMENT = 41
    const val KEY_RANGE = 43
    const val VEL_RANGE = 44
    const val STARTLOOP_ADDRS_COARSE = 45
    const val ENDLOOP_ADDRS_COARSE = 50
    const val SAMPLE_ID = 53
}

/** A parsed SoundFont. Sample data stays on disk: only offsets are kept. */
class SoundFont(
    val name: String,
    val versionMajor: Int,
    val versionMinor: Int,
    val presets: List<Preset>,
    val instruments: List<Instrument>,
    val samples: List<SampleHeader>,
    /** Absolute file offset of the smpl chunk data, and its byte size. */
    val smplOffset: Long,
    val smplSize: Long,
    /** Absolute file offset of the sm24 chunk data (or -1), and its byte size. */
    val sm24Offset: Long,
    val sm24Size: Long,
) {
    val has24Bit: Boolean get() = sm24Offset >= 0 && sm24Size > 0
    val totalSamplePoints: Long get() = smplSize / 2

    /** Instrument indices used by a preset (only valid ones). */
    fun instrumentsOf(preset: Preset): Set<Int> =
        preset.zones.mapNotNull { it.gen(GenOp.INSTRUMENT)?.unsigned }
            .filter { it in instruments.indices }.toSet()

    /** Sample indices used by an instrument, including stereo partners. */
    fun samplesOf(instIndex: Int, includePartners: Boolean = true): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (z in instruments[instIndex].zones) {
            val id = z.gen(GenOp.SAMPLE_ID)?.unsigned ?: continue
            if (id !in samples.indices) continue
            out += id
            if (includePartners) {
                val s = samples[id]
                if (s.isStereoPart && s.sampleLink in samples.indices) out += s.sampleLink
            }
        }
        return out
    }

    fun samplesOfPreset(preset: Preset): Set<Int> =
        instrumentsOf(preset).flatMapTo(LinkedHashSet()) { samplesOf(it) }

    /** Approximate bytes of sample data a preset needs. */
    fun sampleBytesOf(preset: Preset): Long {
        val bytesPerPoint = if (has24Bit) 3 else 2
        return samplesOfPreset(preset).sumOf { (samples[it].length + 46) * bytesPerPoint }
    }
}
