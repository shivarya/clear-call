package com.clearcall.audio

/**
 * Fixed-capacity single-producer/single-consumer float ring buffer. Only ever touched from
 * the realtime audio thread, so it needs no synchronization. Capacity is set once (via
 * [ensureCapacity]) off the hot path; steady-state [write]/[read] never allocate.
 */
class FloatRing {
    private var buf = FloatArray(0)
    private var head = 0
    private var count = 0

    /** Grow (never shrink) to hold at least [capacity] samples. Clears contents when it grows. */
    fun ensureCapacity(capacity: Int) {
        if (buf.size >= capacity) return
        buf = FloatArray(capacity)
        head = 0
        count = 0
    }

    fun available(): Int = count

    /** Append [len] samples from [src]. Caller guarantees room (available + len <= capacity). */
    fun write(src: FloatArray, len: Int) {
        val cap = buf.size
        var tail = (head + count) % cap
        for (i in 0 until len) {
            buf[tail] = src[i]
            tail++
            if (tail == cap) tail = 0
        }
        count += len
    }

    /** Remove [len] samples into [dst]. Caller guarantees availability (available >= len). */
    fun read(dst: FloatArray, len: Int) {
        val cap = buf.size
        for (i in 0 until len) {
            dst[i] = buf[head]
            head++
            if (head == cap) head = 0
        }
        count -= len
    }

    fun clear() {
        head = 0
        count = 0
    }
}
