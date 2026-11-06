package com.backdropcam

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.random.Random

/**
 * A tiny web server on the local Wi-Fi so another phone, tablet or laptop can watch the camera
 * and start / pause / stop the recording from a browser. Nothing to install on the other device.
 * Every request must carry a random key that is only shown on this phone.
 */
class RemoteServer(
    private val status: () -> Status,
    private val onCommand: (String) -> Unit,
) {
    class Status(val recording: Boolean, val paused: Boolean, val seconds: Long, val countdown: Boolean)

    val key: String = (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789"[Random.nextInt(31)] }.joinToString("")
    @Volatile var port = 0; private set
    @Volatile private var running = false
    private var server: ServerSocket? = null
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile var viewers = 0; private set

    /** True while someone has the live picture open (so frames only get made when needed). */
    val wantsFrames: Boolean get() = running && viewers > 0

    fun start(): Boolean {
        if (running) return true
        for (p in 8080..8090) {
            try {
                server = ServerSocket(p)
                port = p
                break
            } catch (e: Exception) {
                continue
            }
        }
        val s = server ?: return false
        running = true
        Thread({
            while (running) {
                val client = try { s.accept() } catch (e: SocketException) { break }
                Thread({ handle(client) }, "remote-client").apply { isDaemon = true }.start()
            }
        }, "remote-accept").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    /** Called with each new preview picture (compressed here, off the GL thread by the caller). */
    fun pushFrame(bmp: Bitmap) {
        val out = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        latestJpeg = out.toByteArray()
    }

    /** http://ip:port/?k=key for every local network address (Wi-Fi or this phone's hotspot). */
    fun urls(): List<String> = localAddresses().map { "http://$it:$port/?k=$key" }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break } // skip headers
                val target = requestLine.split(" ").getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "").split('&').associate {
                    it.substringBefore('=') to it.substringAfter('=', "")
                }
                val out = sock.getOutputStream()
                if (query["k"] != key) return send(out, 403, "text/plain", "Open the link shown in BackdropCam.".toByteArray())
                when (path) {
                    "/" -> send(out, 200, "text/html; charset=utf-8", page().toByteArray())
                    "/status" -> {
                        val st = status()
                        send(out, 200, "application/json",
                            """{"recording":${st.recording},"paused":${st.paused},"seconds":${st.seconds},"countdown":${st.countdown}}""".toByteArray())
                    }
                    "/cmd" -> {
                        val a = query["a"] ?: ""
                        if (a in setOf("record", "pause")) onCommand(a)
                        send(out, 200, "text/plain", "ok".toByteArray())
                    }
                    "/stream" -> stream(out)
                    else -> send(out, 404, "text/plain", "Not found".toByteArray())
                }
            } catch (e: Exception) {
                Log.d("RemoteServer", "client closed: ${e.message}")
            }
        }
    }

    /** MJPEG: a never-ending multipart response, one JPEG part per picture (~8 per second). */
    private fun stream(out: OutputStream) {
        viewers++
        try {
            out.write(("HTTP/1.1 200 OK\r\nCache-Control: no-cache\r\nConnection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").toByteArray())
            var last: ByteArray? = null
            while (running) {
                val jpg = latestJpeg
                if (jpg != null && jpg !== last) {
                    last = jpg
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.size}\r\n\r\n".toByteArray())
                    out.write(jpg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(60)
            }
        } finally {
            viewers--
        }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; else -> "Not Found" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun page() = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BackdropCam remote</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#0d0d10;color:#fff;font-family:system-ui,sans-serif;display:flex;flex-direction:column;align-items:center;min-height:100vh}
img{max-width:100%;max-height:72vh;margin-top:12px;border-radius:14px;background:#000}
.row{display:flex;gap:14px;margin:18px 16px;align-items:center}
button{border:0;border-radius:999px;font-size:17px;font-weight:700;padding:16px 26px;color:#fff;background:#3a3a42}
#rec{background:#ff3b30;min-width:120px}
#st{font-size:16px;font-variant-numeric:tabular-nums;opacity:.85}
</style></head><body>
<img src="/stream?k=$key" alt="Live picture">
<div class="row"><button id="rec">Record</button><button id="pause" disabled>Pause</button></div>
<div id="st">Connected</div>
<script>
const k="$key";
const cmd=a=>fetch('/cmd?a='+a+'&k='+k);
document.getElementById('rec').onclick=()=>cmd('record');
document.getElementById('pause').onclick=()=>cmd('pause');
async function poll(){
 try{
  const s=await (await fetch('/status?k='+k)).json();
  const m=String(Math.floor(s.seconds/60)).padStart(2,'0'), sec=String(s.seconds%60).padStart(2,'0');
  document.getElementById('rec').textContent = s.recording ? 'Stop' : (s.countdown ? 'Cancel' : 'Record');
  const p=document.getElementById('pause'); p.disabled=!s.recording; p.textContent = s.paused ? 'Resume' : 'Pause';
  document.getElementById('st').textContent = s.recording ? ((s.paused?'Paused ':'Recording ')+m+':'+sec) : (s.countdown?'Starting…':'Ready');
 }catch(e){document.getElementById('st').textContent='Reconnecting…'}
 setTimeout(poll,700);
}
poll();
</script></body></html>"""

    companion object {
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
