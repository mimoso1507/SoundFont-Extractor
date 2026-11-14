package com.ilmer.sf2tool.core

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Random access reader over a file channel. */
class RandomSource(private val channel: FileChannel, private val closeable: Closeable? = null) : Closeable {
    val size: Long = channel.size()

    fun read(pos: Long, len: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        readFully(pos, buf)
        buf.flip()
        return buf
    }

    fun readFully(pos: Long, buf: ByteBuffer) {
        var p = pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n < 0) throw EOFException("Fin de archivo inesperado en $p")
            p += n
        }
    }

    override fun close() {
        try { channel.close() } finally { closeable?.close() }
    }

    companion object {
        fun of(file: File): RandomSource {
            val raf = RandomAccessFile(file, "r")
            return RandomSource(raf.channel, raf)
        }
    }
}
