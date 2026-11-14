package com.ilmer.sf2tool.core

enum class Category(val label: String) {
    DRUMS("Baterías"), PIANO("Pianos"), CHROMATIC("Percusión cromática"), ORGAN("Órganos"),
    GUITAR("Guitarras"), BASS("Bajos"), STRINGS("Cuerdas"), ENSEMBLE("Ensambles/Coros"),
    BRASS("Metales"), REED("Lengüetas/Sax"), PIPE("Flautas"), SYNTH_LEAD("Synth lead"),
    SYNTH_PAD("Synth pad"), SYNTH_FX("Synth FX"), ETHNIC("Étnicos"), PERCUSSIVE("Percusivos"),
    SFX("Efectos de sonido"), OTHER("Otros");

    companion object {
        private val drumWords = listOf("drum", "kit", "bater", "percus", "perc ", "snare", "kick", "hihat", "hi-hat", "tom ", "cymbal", "platillo", "bombo", "redoblante", "tarola")

        fun of(bank: Int, program: Int, name: String): Category {
            val n = name.lowercase()
            if (bank == 128 || bank == 120 || drumWords.any { n.contains(it) } && !n.contains("steel drum")) return DRUMS
            return when (program) {
                in 0..7 -> PIANO; in 8..15 -> CHROMATIC; in 16..23 -> ORGAN; in 24..31 -> GUITAR
                in 32..39 -> BASS; in 40..47 -> STRINGS; in 48..55 -> ENSEMBLE; in 56..63 -> BRASS
                in 64..71 -> REED; in 72..79 -> PIPE; in 80..87 -> SYNTH_LEAD; in 88..95 -> SYNTH_PAD
                in 96..103 -> SYNTH_FX; in 104..111 -> ETHNIC; in 112..119 -> PERCUSSIVE
                in 120..127 -> SFX; else -> OTHER
            }
        }
    }
}
