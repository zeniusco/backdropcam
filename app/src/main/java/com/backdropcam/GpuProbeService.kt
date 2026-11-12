package com.backdropcam

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * Tries the cut-out on the graphics chip in a separate process.
 *
 * On some phones the cut-out library crashes on the graphics chip in a way an app cannot catch
 * (the whole process is ended). Trying it here first means such a crash only ends this small
 * helper process: the camera screen keeps running and simply uses the processor instead.
 * Replies 1 (works) or 0 (does not); no reply at all (the process died) also means it does not.
 */
class GpuProbeService : Service() {

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        val reply = msg.replyTo
        val highQuality = msg.what == 1
        Thread({
            val ok = try {
                val seg = PersonSegmenter(applicationContext, highQuality, allowGpu = true)
                // A simple picture of the same size the camera screen sends.
                val bmp = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
                Canvas(bmp).apply {
                    drawColor(0xFF808890.toInt())
                    drawCircle(160f, 130f, 60f, Paint().apply { color = 0xFFC8A080.toInt() })
                    drawRect(90f, 190f, 230f, 320f, Paint().apply { color = 0xFF304060.toInt() })
                }
                var good = seg.onGpu
                repeat(4) {
                    if (seg.segment(bmp) == null) good = false
                    Thread.sleep(30)
                }
                good = good && seg.onGpu
                seg.close()
                good
            } catch (t: Throwable) {
                false
            }
            runCatching { reply?.send(Message.obtain(null, if (ok) 1 else 0)) }
        }, "gpu-probe").start()
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
