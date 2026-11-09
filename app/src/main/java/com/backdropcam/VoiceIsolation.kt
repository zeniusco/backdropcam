package com.backdropcam

/**
 * Voice isolation: removes background noise (fans, traffic, AC hum, keyboard) from the
 * recording's sound while keeping the voice, with the small offline RNNoise network.
 *
 * Works on 48 kHz mono audio in 10 ms frames; the recorder hands it chunks of any size and gets
 * the same number of cleaned samples back, 10 ms later (too little to notice against the video).
 * Not thread safe: use from the audio thread only, and [close] when the recording ends.
 */
class VoiceIsolation {
    private var handle = runCatching { nativeCreateSafe() }.getOrDefault(0L)
    private val input = ShortArray(FRAME * 8)
    private var inCount = 0
    // Output queue, primed with one frame of silence so there is always enough to hand back.
    private val output = ShortArray(FRAME * 16)
    private var outCount = FRAME
    private val work = ShortArray(FRAME * 8)

    val available: Boolean get() = handle != 0L

    /** Replaces the first [n] samples of [pcm] with cleaned audio (delayed by one frame). */
    fun process(pcm: ShortArray, n: Int) {
        if (handle == 0L || n <= 0) return
        var pos = 0
        while (pos < n) {
            // Take in as much as fits, clean every whole frame, keep the rest for next time.
            val take = minOf(n - pos, input.size - inCount)
            System.arraycopy(pcm, pos, input, inCount, take)
            inCount += take
            pos += take
            val frames = inCount / FRAME
            if (frames > 0) {
                val len = frames * FRAME
                System.arraycopy(input, 0, work, 0, len)
                nativeProcess(handle, work, frames)
                appendOutput(work, len)
                System.arraycopy(input, len, input, 0, inCount - len)
                inCount -= len
            }
        }
        // Hand back exactly n samples.
        val give = minOf(n, outCount)
        System.arraycopy(output, 0, pcm, 0, give)
        if (give < n) java.util.Arrays.fill(pcm, give, n, 0.toShort())
        System.arraycopy(output, give, output, 0, outCount - give)
        outCount -= give
    }

    private fun appendOutput(src: ShortArray, len: Int) {
        val room = output.size - outCount
        val keep = minOf(len, room)
        System.arraycopy(src, 0, output, outCount, keep)
        outCount += keep
    }

    fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private companion object {
        const val FRAME = 480
        val loaded = runCatching { System.loadLibrary("voiceiso") }.isSuccess

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeProcess(handle: Long, pcm: ShortArray, frames: Int)

        fun nativeCreateSafe() = if (loaded) nativeCreate() else 0L
    }
}
