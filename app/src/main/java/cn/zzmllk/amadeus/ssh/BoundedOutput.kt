package cn.zzmllk.amadeus.ssh

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Drain the SSH stream without retaining unbounded output in memory. */
class BoundedOutput(private val limit: Int) : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    @Volatile var exceeded = false
        private set
    init { require(limit > 0) }
    @Synchronized override fun write(value: Int) {
        if (buffer.size() < limit) buffer.write(value) else exceeded = true
    }
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val accepted = minOf(length, limit - buffer.size())
        buffer.write(bytes, offset, accepted)
        if (accepted < length) exceeded = true
    }
    @Synchronized fun text(): String = buffer.toString(Charsets.UTF_8.name())
    @Synchronized fun size(): Int = buffer.size()
}
