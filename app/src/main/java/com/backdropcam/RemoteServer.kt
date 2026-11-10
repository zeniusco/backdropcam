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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny web server on the local Wi-Fi so another phone, tablet or laptop can watch the camera
 * and start / pause / stop the recording from a browser. Nothing to install on the other device.
 * Every request must carry a random key that is only shown on this phone.
 */
class RemoteServer(
    private val status: () -> Status,
    private val onCommand: (String) -> Unit,
) {
    class Status(
        val recording: Boolean,
        val paused: Boolean,
        val seconds: Long,
        val countdown: Boolean,
        /** Auto-stop length in seconds, 0 = off. */
        val limit: Int = 0,
        /** Microphone level 0..1 (while recording). */
        val level: Float = 0f,
        val quality: String = "",
        val camera: String = "",
    )

    /** Secret in the link: 10 characters from a secure random source (about 50 bits). */
    val key: String = SecureRandom().let { r -> (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)] }.joinToString("") }
    private val keyBytes = key.toByteArray()

    /** Wrong keys per address, and when that address may try again (blocks guessing). */
    private class Strikes(var count: Int = 0, var blockedUntil: Long = 0L)
    private val strikes = ConcurrentHashMap<String, Strikes>()
    private val openConnections = AtomicInteger(0)
    @Volatile var port = 0; private set
    @Volatile private var running = false
    private var server: ServerSocket? = null
    @Volatile private var latestJpeg: ByteArray? = null
    private val frameLock = Object()
    @Volatile private var encoding = false
    /** True while the last picture is still being compressed; the caller then skips a frame. */
    val busy: Boolean get() = encoding
    private val viewerCount = java.util.concurrent.atomic.AtomicInteger(0)
    val viewers: Int get() = viewerCount.get()

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
                // Only devices on the same local network (or this phone) may connect, and only a
                // few at a time, so the phone cannot be flooded.
                val addr = client.inetAddress
                val local = addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
                if (!local || openConnections.get() >= MAX_CONNECTIONS) {
                    runCatching { client.close() }
                    continue
                }
                openConnections.incrementAndGet()
                Thread({
                    try { handle(client) } finally { openConnections.decrementAndGet() }
                }, "remote-client").apply { isDaemon = true }.start()
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
        encoding = true
        try {
            val out = ByteArrayOutputStream(128 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            latestJpeg = out.toByteArray()
            synchronized(frameLock) { frameLock.notifyAll() }
        } finally {
            encoding = false
        }
    }

    /** http://ip:port/?k=key for every local network address (Wi-Fi or this phone's hotspot). */
    fun urls(): List<String> = localAddresses().map { "http://$it:$port/?k=$key" }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 15_000
                sock.tcpNoDelay = true
                runCatching { sock.sendBufferSize = 512 * 1024 }
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()), 4096)
                val requestLine = readLimitedLine(reader) ?: return
                // Skip the headers, but never read more than a small, fixed amount.
                var headerLines = 0
                while (headerLines++ < 64) { val l = readLimitedLine(reader) ?: break; if (l.isEmpty()) break }
                val parts = requestLine.split(" ")
                if (parts.getOrNull(0) != "GET") return
                val target = parts.getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "").split('&').associate {
                    it.substringBefore('=') to it.substringAfter('=', "")
                }
                val out = sock.getOutputStream()
                val ip = sock.inetAddress.hostAddress ?: "?"
                val st = strikes.getOrPut(ip) { Strikes() }
                val now = System.currentTimeMillis()
                if (now < st.blockedUntil) return send(out, 429, "text/plain", "Too many wrong links. Wait a minute.".toByteArray())
                val given = (query["k"] ?: "").toByteArray()
                if (!MessageDigest.isEqual(given, keyBytes)) {
                    // A few mistakes are fine; then this address must wait before trying again.
                    if (++st.count >= 5) { st.blockedUntil = now + 60_000; st.count = 0 }
                    return send(out, 403, "text/plain", "Open the link shown in BackdropKit.".toByteArray())
                }
                st.count = 0
                when (path) {
                    "/" -> send(out, 200, "text/html; charset=utf-8", page().toByteArray())
                    "/status" -> {
                        val st = status()
                        send(out, 200, "application/json",
                            ("""{"recording":${st.recording},"paused":${st.paused},"seconds":${st.seconds},""" +
                                """"countdown":${st.countdown},"limit":${st.limit},"level":${"%.3f".format(java.util.Locale.US, st.level)},""" +
                                """"quality":"${st.quality}","camera":"${st.camera}"}""").toByteArray())
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

    /** MJPEG: a never-ending multipart response, one JPEG part per picture (up to 20 per second). */
    private fun stream(out: OutputStream) {
        viewerCount.incrementAndGet()
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
                // Wake as soon as the next picture is ready instead of polling.
                synchronized(frameLock) { if (latestJpeg === last) frameLock.wait(200) }
            }
        } finally {
            viewerCount.decrementAndGet()
        }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 429 -> "Too Many Requests"; else -> "Not Found" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun page() = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BackdropKit monitor</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:#0d0d10;color:#fff;font-family:system-ui,"Segoe UI",sans-serif;display:flex;flex-direction:column;align-items:center;min-height:100vh;padding:12px 16px}
#wrap{position:relative;display:inline-block;max-width:100%}
#cam{display:block;max-width:100%;max-height:calc(100vh - 150px);border-radius:14px;background:#000}
#wrap:fullscreen{display:flex;align-items:center;justify-content:center;background:#000;width:100vw;height:100vh}
#wrap:fullscreen #cam{max-height:100vh;border-radius:0}
.pill{position:absolute;top:12px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.55);border-radius:999px;padding:6px 14px;font-size:17px;font-weight:700;font-variant-numeric:tabular-nums;display:flex;align-items:center;gap:8px;white-space:nowrap}
.dot{width:11px;height:11px;border-radius:50%;background:#888}
.rec .dot{background:#ff3b30;animation:b 1s infinite}
.paused .dot{background:#ffcc00;animation:none}
@keyframes b{50%{opacity:.3}}
#info{position:absolute;bottom:10px;left:12px;font-size:12px;opacity:.75;background:rgba(0,0,0,.45);padding:3px 8px;border-radius:8px}
#meter{position:absolute;right:12px;bottom:12px;top:56px;width:8px;border-radius:4px;background:rgba(255,255,255,.18);overflow:hidden}
#lvl{position:absolute;bottom:0;left:0;right:0;height:0;background:#34c759;transition:height .1s}
.row{display:flex;gap:12px;margin:14px 0 6px;align-items:center;flex-wrap:wrap;justify-content:center}
button{border:0;border-radius:999px;font-size:17px;font-weight:700;padding:14px 24px;color:#fff;background:#3a3a42;cursor:pointer}
button:disabled{opacity:.4;cursor:default}
#rec{background:#ff3b30;min-width:120px}
#keys{font-size:12px;opacity:.55}
</style></head><body>
<div id="wrap">
 <img id="cam" src="/stream?k=$key" alt="Live picture, as it will be recorded">
 <div class="pill" id="pill"><span class="dot"></span><span id="st">Connecting</span></div>
 <div id="meter"><div id="lvl"></div></div>
 <div id="info"></div>
</div>
<div class="row"><button id="rec">Record</button><button id="pause" disabled>Pause</button><button id="fs">Full screen</button></div>
<div id="keys">Keys: Space record or stop, P pause, F full screen</div>
<script>
const k="$key", ${'$'}=id=>document.getElementById(id);
const cmd=a=>fetch('/cmd?a='+a+'&k='+k);
const t=s=>String(Math.floor(s/60)).padStart(2,'0')+':'+String(s%60).padStart(2,'0');
${'$'}('rec').onclick=()=>cmd('record');
${'$'}('pause').onclick=()=>cmd('pause');
const fs=()=>document.fullscreenElement?document.exitFullscreen():${'$'}('wrap').requestFullscreen();
${'$'}('fs').onclick=fs;
document.addEventListener('keydown',e=>{
 if(e.code==='Space'){e.preventDefault();cmd('record')}
 else if(e.key==='p'||e.key==='P')cmd('pause');
 else if(e.key==='f'||e.key==='F')fs();
});
// Reconnect the picture if the Wi-Fi drops for a moment.
${'$'}('cam').onerror=()=>setTimeout(()=>{${'$'}('cam').src='/stream?k='+k+'&t='+Date.now()},1000);
async function poll(){
 try{
  const s=await (await fetch('/status?k='+k)).json();
  ${'$'}('rec').textContent = s.recording ? 'Stop' : (s.countdown ? 'Cancel' : 'Record');
  const p=${'$'}('pause'); p.disabled=!s.recording; p.textContent = s.paused ? 'Resume' : 'Pause';
  let txt;
  if(s.recording){
   txt=(s.paused?'Paused ':'')+t(s.seconds);
   if(s.limit>0) txt+=' / '+t(s.limit)+'  ('+t(Math.max(0,s.limit-s.seconds))+' left)';
  } else txt = s.countdown ? 'Starting' : 'Ready';
  ${'$'}('st').textContent=txt;
  ${'$'}('pill').className='pill'+(s.recording?(s.paused?' paused':' rec'):'');
  const lv=Math.min(1,s.recording?s.level:0);
  ${'$'}('lvl').style.height=(lv*100)+'%';
  ${'$'}('lvl').style.background=lv>0.9?'#ff3b30':(lv>0.7?'#ffcc00':'#34c759');
  ${'$'}('info').textContent=[s.camera,s.quality,s.limit>0?'auto-stop '+t(s.limit):''].filter(Boolean).join('  ·  ');
 }catch(e){${'$'}('st').textContent='Reconnecting'}
 setTimeout(poll,250);
}
poll();
</script></body></html>"""

    /** Reads one line of at most 2 KB; longer lines (or none) end the request. */
    private fun readLimitedLine(r: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = r.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= 2048) return null
            sb.append(c.toChar())
        }
    }

    companion object {
        private const val MAX_CONNECTIONS = 8

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
