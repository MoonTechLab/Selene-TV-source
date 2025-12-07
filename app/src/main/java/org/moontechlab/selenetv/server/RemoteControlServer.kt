package org.moontechlab.selenetv.server

import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.net.ServerSocket

/**
 * 遥控器 Web 服务器 (Ktor 实现)
 */
object RemoteControlServer {
    private const val TAG = "RemoteControlServer"
    private const val START_PORT = 28256
    private const val MAX_PORT_ATTEMPTS = 100

    private var server: ApplicationEngine? = null
    private var currentPort: Int = START_PORT
    private var keyEventCallback: ((Int) -> Unit)? = null

    fun getPort(): Int = currentPort

    fun setKeyEventCallback(callback: (Int) -> Unit) {
        keyEventCallback = callback
    }

    fun start(): Boolean {
        if (server != null) {
            Log.d(TAG, "Server already running on port $currentPort")
            return true
        }

        for (i in 0 until MAX_PORT_ATTEMPTS) {
            val port = START_PORT + i
            if (isPortAvailable(port)) {
                try {
                    server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                        configureRouting()
                    }.start(wait = false)
                    currentPort = port
                    Log.i(TAG, "Remote control server started on port $port")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start server on port $port", e)
                }
            }
        }
        Log.e(TAG, "Failed to find available port")
        return false
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "Remote control server stopped")
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun Application.configureRouting() {
        routing {
            get("/") { call.respondRedirect("/remote_control") }
            get("/remote_control") { call.respondText(remoteControlHtml, ContentType.Text.Html) }
            post("/api/key") {
                try {
                    val keyCode = call.receiveText().trim().toIntOrNull()
                    if (keyCode != null) {
                        Log.d(TAG, "Received key: $keyCode")
                        keyEventCallback?.invoke(keyCode)
                        call.respondText("""{"success":true}""", ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.BadRequest)
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest)
                }
            }
        }
    }


    private val remoteControlHtml = """
<!DOCTYPE html>
<html lang="zh">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no, maximum-scale=1.0">
    <title>Selene TV Remote</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; user-select: none; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', sans-serif;
            background: linear-gradient(180deg, #1a1a1e 0%, #0d0d0f 100%);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 20px;
            color: #fff;
        }
        .remote-container { width: 100%; max-width: 320px; display: flex; flex-direction: column; align-items: center; gap: 30px; }
        .title { font-size: 18px; font-weight: 500; color: rgba(255,255,255,0.6); letter-spacing: 2px; text-transform: uppercase; }
        .touchpad-area {
            width: 260px; height: 260px;
            background: linear-gradient(145deg, #2a2a2e 0%, #1f1f22 100%);
            border-radius: 40px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.1);
            display: flex; align-items: center; justify-content: center;
        }
        .dpad-container {
            width: 220px; height: 220px;
            display: grid;
            grid-template-columns: 1fr 1fr 1fr;
            grid-template-rows: 1fr 1fr 1fr;
            gap: 4px;
        }
        .dpad-btn {
            background: transparent; border: none;
            display: flex; align-items: center; justify-content: center;
            cursor: pointer; transition: all 0.15s ease; border-radius: 12px;
        }
        .dpad-btn:active { background: rgba(255,255,255,0.1); transform: scale(0.95); }
        .dpad-btn svg { width: 32px; height: 32px; fill: rgba(255,255,255,0.5); transition: fill 0.15s ease; }
        .dpad-btn:active svg { fill: rgba(255,255,255,0.9); }
        .center-btn {
            grid-column: 2; grid-row: 2;
            width: 70px; height: 70px;
            background: linear-gradient(145deg, #3a3a3e 0%, #2a2a2e 100%);
            border: none; border-radius: 50%; cursor: pointer;
            box-shadow: 0 4px 15px rgba(0,0,0,0.3), inset 0 1px 0 rgba(255,255,255,0.15);
            transition: all 0.15s ease;
            justify-self: center; align-self: center;
        }
        .center-btn:active { transform: scale(0.92); background: linear-gradient(145deg, #4a4a4e 0%, #3a3a3e 100%); }
        .up-btn { grid-column: 2; grid-row: 1; }
        .down-btn { grid-column: 2; grid-row: 3; }
        .left-btn { grid-column: 1; grid-row: 2; }
        .right-btn { grid-column: 3; grid-row: 2; }
        .back-btn {
            width: 60px; height: 60px;
            background: linear-gradient(145deg, #2a2a2e 0%, #1f1f22 100%);
            border: none; border-radius: 50%; cursor: pointer;
            display: flex; align-items: center; justify-content: center;
            box-shadow: 0 6px 20px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.1);
            transition: all 0.15s ease; margin-top: 10px;
        }
        .back-btn:active { transform: scale(0.92); background: linear-gradient(145deg, #3a3a3e 0%, #2a2a2e 100%); }
        .back-btn svg { width: 24px; height: 24px; fill: rgba(255,255,255,0.6); }
        .back-btn:active svg { fill: rgba(255,255,255,0.9); }
        .status { font-size: 12px; color: rgba(255,255,255,0.3); margin-top: 20px; }
        .status.connected { color: #34c759; }
        .status.error { color: #ff3b30; }
    </style>
</head>
<body>
    <div class="remote-container">
        <div class="title">Selene TV</div>
        <div class="touchpad-area">
            <div class="dpad-container">
                <button class="dpad-btn up-btn" data-key="19"><svg viewBox="0 0 24 24"><path d="M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z"/></svg></button>
                <button class="dpad-btn left-btn" data-key="21"><svg viewBox="0 0 24 24"><path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg></button>
                <button class="center-btn" data-key="23"></button>
                <button class="dpad-btn right-btn" data-key="22"><svg viewBox="0 0 24 24"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg></button>
                <button class="dpad-btn down-btn" data-key="20"><svg viewBox="0 0 24 24"><path d="M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6z"/></svg></button>
            </div>
        </div>
        <button class="back-btn" data-key="4"><svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
        <div class="status" id="status">已连接</div>
    </div>
    <script>
        const status = document.getElementById('status');
        async function sendKey(k) {
            try {
                const r = await fetch('/api/key', { method: 'POST', body: k.toString() });
                status.textContent = r.ok ? '已连接' : '连接失败';
                status.className = 'status ' + (r.ok ? 'connected' : 'error');
            } catch (e) { status.textContent = '连接失败'; status.className = 'status error'; }
        }
        document.querySelectorAll('[data-key]').forEach(btn => {
            const k = parseInt(btn.dataset.key);
            btn.addEventListener('touchstart', e => { e.preventDefault(); sendKey(k); if(navigator.vibrate) navigator.vibrate(10); });
            btn.addEventListener('click', e => { if(e.pointerType !== 'touch') sendKey(k); });
        });
        document.addEventListener('keydown', e => {
            const m = { ArrowUp: 19, ArrowDown: 20, ArrowLeft: 21, ArrowRight: 22, Enter: 23, Escape: 4, Backspace: 4 };
            if (m[e.key]) { e.preventDefault(); sendKey(m[e.key]); }
        });
    </script>
</body>
</html>
    """.trimIndent()
}
