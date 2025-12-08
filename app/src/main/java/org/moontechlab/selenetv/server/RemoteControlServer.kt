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
    private var textInputCallback: ((String) -> Unit)? = null

    fun getPort(): Int = currentPort

    fun setKeyEventCallback(callback: (Int) -> Unit) {
        keyEventCallback = callback
    }

    fun setTextInputCallback(callback: (String) -> Unit) {
        textInputCallback = callback
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
            post("/api/text") {
                try {
                    val text = call.receiveText().trim()
                    if (text.isNotEmpty()) {
                        Log.d(TAG, "Received text: $text")
                        textInputCallback?.invoke(text)
                        call.respondText("""{"success":true}""", ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.BadRequest)
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest)
                }
            }
            get("/api/ping") {
                call.respondText("""{"pong":true}""", ContentType.Application.Json)
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
            font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'SF Pro Text', sans-serif;
            background: #000;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 20px;
            color: #fff;
        }
        .remote-body {
            width: 100%;
            max-width: 240px;
            background: linear-gradient(180deg, 
                #5a5a5c 0%, 
                #4a4a4c 2%, 
                #3a3a3c 5%,
                #2c2c2e 50%,
                #252527 95%,
                #1c1c1e 100%);
            border-radius: 48px;
            padding: 30px 30px 48px;
            box-shadow: 
                0 70px 140px rgba(0,0,0,0.9),
                0 0 0 1px rgba(255,255,255,0.08),
                inset 0 1px 0 rgba(255,255,255,0.2),
                inset 0 -1px 0 rgba(0,0,0,0.3);
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 36px;
            position: relative;
        }
        .power-indicator {
            width: 10px; height: 10px;
            background: #34c759;
            border-radius: 50%;
            box-shadow: 0 0 12px rgba(52,199,89,0.8);
            position: absolute;
            top: 14px;
            left: 50%;
            transform: translateX(-50%);
        }
        .power-indicator.error { background: #ff3b30; box-shadow: 0 0 12px rgba(255,59,48,0.8); }
        .touchpad-ring {
            width: 180px; height: 180px;
            background: linear-gradient(180deg, #0a0a0a 0%, #151515 100%);
            border-radius: 50%;
            padding: 5px;
            box-shadow: 
                inset 0 4px 12px rgba(0,0,0,0.9),
                0 1px 0 rgba(255,255,255,0.1);
            margin-top: 18px;
        }
        .touchpad-inner {
            width: 100%; height: 100%;
            background: linear-gradient(180deg, #2d2d2f 0%, #1d1d1f 100%);
            border-radius: 50%;
            display: flex; align-items: center; justify-content: center;
            position: relative;
            box-shadow: inset 0 1px 0 rgba(255,255,255,0.08);
        }
        .dpad-container {
            width: 150px; height: 150px;
            display: grid;
            grid-template-columns: 1fr 1fr 1fr;
            grid-template-rows: 1fr 1fr 1fr;
            position: relative;
        }
        .dpad-btn {
            background: transparent; border: none;
            display: flex; align-items: center; justify-content: center;
            cursor: pointer; transition: all 0.1s ease; border-radius: 50%;
            position: relative; z-index: 2;
        }
        .dpad-btn:active { background: rgba(255,255,255,0.1); }
        .dpad-btn svg { width: 26px; height: 26px; fill: rgba(255,255,255,0.4); transition: fill 0.1s ease; }
        .dpad-btn:active svg { fill: rgba(255,255,255,0.9); }
        .center-btn {
            grid-column: 2; grid-row: 2;
            width: 60px; height: 60px;
            background: linear-gradient(180deg, #404042 0%, #303032 100%);
            border: none; border-radius: 50%; cursor: pointer;
            box-shadow: 
                0 4px 12px rgba(0,0,0,0.5),
                inset 0 1px 0 rgba(255,255,255,0.15);
            transition: all 0.1s ease;
            justify-self: center; align-self: center;
            position: relative; z-index: 3;
        }
        .center-btn:active { 
            transform: scale(0.95); 
            background: linear-gradient(180deg, #505052 0%, #404042 100%);
        }
        .up-btn { grid-column: 2; grid-row: 1; }
        .down-btn { grid-column: 2; grid-row: 3; }
        .left-btn { grid-column: 1; grid-row: 2; }
        .right-btn { grid-column: 3; grid-row: 2; }
        .btn-row { 
            display: flex; 
            gap: 30px; 
            width: 100%;
            justify-content: center;
        }
        .action-btn {
            width: 66px; height: 66px;
            background: linear-gradient(180deg, #0c0c0c 0%, #181818 100%);
            border: none; border-radius: 50%; cursor: pointer;
            display: flex; align-items: center; justify-content: center;
            box-shadow: 
                inset 0 4px 10px rgba(0,0,0,0.7),
                0 1px 0 rgba(255,255,255,0.08);
            transition: all 0.1s ease;
        }
        .action-btn:active { 
            background: linear-gradient(180deg, #1a1a1a 0%, #252525 100%);
        }
        .action-btn svg { width: 26px; height: 26px; fill: rgba(255,255,255,0.5); transition: fill 0.1s ease; }
        .action-btn:active svg { fill: rgba(255,255,255,0.9); }
        .brand-text {
            font-size: 13px;
            font-weight: 600;
            color: rgba(255,255,255,0.2);
            letter-spacing: 4px;
            text-transform: uppercase;
            margin-top: 275px;
        }
        .modal-overlay {
            position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            display: flex;
            background: rgba(0,0,0,0); 
            -webkit-backdrop-filter: blur(0px) saturate(100%);
            backdrop-filter: blur(0px) saturate(100%);
            align-items: flex-end; justify-content: center; z-index: 100;
            padding-bottom: env(safe-area-inset-bottom, 20px);
            visibility: hidden;
            pointer-events: none;
            transition: background 0.3s ease, backdrop-filter 0.3s ease, -webkit-backdrop-filter 0.3s ease, visibility 0s 0.3s;
        }
        .modal-overlay.show {
            visibility: visible;
            pointer-events: auto;
            background: rgba(0,0,0,0.6);
            -webkit-backdrop-filter: blur(40px) saturate(180%);
            backdrop-filter: blur(40px) saturate(180%);
            transition: background 0.3s ease, backdrop-filter 0.3s ease, -webkit-backdrop-filter 0.3s ease, visibility 0s 0s;
        }
        .modal {
            width: 100%; max-width: 400px;
            background: linear-gradient(180deg, rgba(58,58,60,0.98) 0%, rgba(44,44,46,0.98) 100%);
            border-radius: 14px 14px 0 0;
            overflow: hidden;
            box-shadow: 0 -10px 60px rgba(0,0,0,0.5);
            transform: translateY(100%);
            opacity: 0;
            transition: transform 0.3s cubic-bezier(0.32, 0.72, 0, 1), opacity 0.25s ease;
        }
        .modal-overlay.show .modal { transform: translateY(0); opacity: 1; }
        .modal-header {
            display: flex; align-items: center; justify-content: space-between;
            padding: 14px 16px;
            border-bottom: 1px solid rgba(255,255,255,0.08);
        }
        .modal-header-btn {
            background: none; border: none;
            font-size: 17px; font-weight: 400;
            color: #0a84ff; cursor: pointer;
            padding: 0; min-width: 60px;
        }
        .modal-header-btn:active { opacity: 0.6; }
        .modal-header-btn.send { font-weight: 600; text-align: right; }
        .modal-header-btn.cancel { text-align: left; }
        .modal-title { 
            font-size: 17px; font-weight: 600; color: #fff; 
            text-align: center; flex: 1;
        }
        .modal-body { padding: 16px; }
        .modal-input-wrapper {
            background: rgba(0,0,0,0.2);
            border-radius: 10px;
            border: 1px solid rgba(255,255,255,0.08);
            overflow: hidden;
        }
        .modal-input {
            width: 100%; height: 44px; padding: 0 14px;
            background: transparent; border: none;
            color: #fff; font-size: 17px; outline: none;
        }
        .modal-input::placeholder { color: rgba(255,255,255,0.3); }
        .modal-input:focus { background: rgba(255,255,255,0.03); }
        .modal-hint {
            font-size: 13px; color: rgba(255,255,255,0.4);
            margin-top: 10px; text-align: center;
        }
    </style>
</head>
<body>
    <div class="remote-body">
        <div class="power-indicator" id="indicator"></div>
        <div class="touchpad-ring">
            <div class="touchpad-inner">
                <div class="dpad-container">
                    <button class="dpad-btn up-btn" data-key="19"><svg viewBox="0 0 24 24"><path d="M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z"/></svg></button>
                    <button class="dpad-btn left-btn" data-key="21"><svg viewBox="0 0 24 24"><path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg></button>
                    <button class="center-btn" data-key="23"></button>
                    <button class="dpad-btn right-btn" data-key="22"><svg viewBox="0 0 24 24"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg></button>
                    <button class="dpad-btn down-btn" data-key="20"><svg viewBox="0 0 24 24"><path d="M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6z"/></svg></button>
                </div>
            </div>
        </div>
        <div class="btn-row">
            <button class="action-btn" data-key="4"><svg viewBox="0 0 24 24"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
            <button class="action-btn" id="keyboardBtn"><svg viewBox="0 0 24 24"><path d="M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z"/></svg></button>
        </div>
        <div class="brand-text">SELENE TV</div>
    </div>
    <div class="modal-overlay" id="modal">
        <div class="modal">
            <div class="modal-header">
                <button class="modal-header-btn cancel" id="cancelBtn">取消</button>
                <div class="modal-title">输入文本</div>
                <button class="modal-header-btn send" id="sendBtn">发送</button>
            </div>
            <div class="modal-body">
                <div class="modal-input-wrapper">
                    <input type="text" class="modal-input" id="textInput" placeholder="请输入...">
                </div>
                <div class="modal-hint">按回车键发送</div>
            </div>
        </div>
    </div>
    <script>
        const indicator = document.getElementById('indicator');
        function setStatus(ok) { indicator.className = 'power-indicator' + (ok ? '' : ' error'); }
        async function sendKey(k) {
            try {
                const r = await fetch('/api/key', { method: 'POST', body: k.toString() });
                setStatus(r.ok);
            } catch (e) { setStatus(false); }
        }
        document.querySelectorAll('[data-key]').forEach(btn => {
            const k = parseInt(btn.dataset.key);
            btn.addEventListener('touchstart', e => { e.preventDefault(); sendKey(k); if(navigator.vibrate) navigator.vibrate(10); });
            btn.addEventListener('click', e => { if(e.pointerType !== 'touch') sendKey(k); });
        });
        const modal = document.getElementById('modal');
        const textInput = document.getElementById('textInput');
        const keyboardBtn = document.getElementById('keyboardBtn');
        const cancelBtn = document.getElementById('cancelBtn');
        const sendBtn = document.getElementById('sendBtn');
        function openModal() { modal.classList.add('show'); setTimeout(() => textInput.focus(), 100); }
        function closeModal() { modal.classList.remove('show'); textInput.value = ''; }
        keyboardBtn.addEventListener('click', openModal);
        cancelBtn.addEventListener('click', closeModal);
        modal.addEventListener('click', e => { if (e.target === modal) closeModal(); });
        async function sendText() {
            const text = textInput.value.trim();
            if (!text) return;
            try {
                const r = await fetch('/api/text', { method: 'POST', body: text });
                if (r.ok) { textInput.value = ''; closeModal(); }
                setStatus(r.ok);
            } catch (e) { setStatus(false); }
        }
        sendBtn.addEventListener('click', sendText);
        textInput.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); sendText(); } });
        document.addEventListener('keydown', e => {
            if (modal.classList.contains('show')) { if (e.key === 'Escape') closeModal(); return; }
            const m = { ArrowUp: 19, ArrowDown: 20, ArrowLeft: 21, ArrowRight: 22, Enter: 23, Escape: 4, Backspace: 4 };
            if (m[e.key]) { e.preventDefault(); sendKey(m[e.key]); }
        });
        setInterval(async () => {
            try {
                const r = await fetch('/api/ping');
                setStatus(r.ok);
            } catch (e) { setStatus(false); }
        }, 2000);
    </script>
</body>
</html>
    """.trimIndent()
}
