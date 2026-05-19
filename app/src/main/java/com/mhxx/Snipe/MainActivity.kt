package com.mhxx.snipe

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.*
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var surfaceView: SurfaceView
    private lateinit var dvrOverlay: View
    private var wakeLock: PowerManager.WakeLock? = null
    private val sysBotBridge = lazy { SysBotBridge() }
    private val dvrBridge    = lazy { SysDvrVideoBridge() }

    companion object {
        private const val NOTIF_CHANNEL_ID = "mhxx_snipe"
        private const val NOTIF_ID         = 1001
        private const val FILE_CHOOSER_RC  = 1001
        private const val TAG              = "MhxxSnipe"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        // ── WebView ──────────────────────────────────────────────────────────
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled                = true
                domStorageEnabled                = true
                allowFileAccess                  = true
                allowContentAccess               = true
                mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                defaultTextEncodingName          = "UTF-8"
                useWideViewPort                  = true
                loadWithOverviewMode             = true
                setSupportZoom(false)
                builtInZoomControls              = false
                displayZoomControls              = false
            }

            addJavascriptInterface(MlKitBridge(),      "Android")
            addJavascriptInterface(sysBotBridge.value, "AndroidBridge")
            addJavascriptInterface(dvrBridge.value,    "AndroidDvr")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("MhxxSnipe/JS",
                        "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
                override fun onShowFileChooser(
                    wv: WebView,
                    callback: ValueCallback<Array<android.net.Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileChooserCallback = callback
                    runCatching { startActivityForResult(params.createIntent(), FILE_CHOOSER_RC) }
                        .onFailure { callback.onReceiveValue(null) }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page loaded: $url")
                }
            }
            loadUrl("file:///android_asset/snipe_modified.html")
        }

        // ── SysDVR 映像オーバーレイ (SurfaceView + 閉じるボタン) ────────────
        surfaceView = SurfaceView(this)

        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setPadding(20, 20, 20, 20)
            setColorFilter(android.graphics.Color.WHITE)
            setOnClickListener { dvrBridge.value.stopStream() }
        }

        dvrOverlay = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ))
            addView(closeBtn, FrameLayout.LayoutParams(128, 128).also {
                it.gravity   = Gravity.TOP or Gravity.END
                it.topMargin = 24
                it.marginEnd = 24
            })
            visibility = View.GONE
        }

        // ── Root レイアウト (WebView + DVR オーバーレイ) ──────────────────────
        val root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(dvrOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(root)
    }

    // ── ファイル選択 ──────────────────────────────────────────────────────────
    private var fileChooserCallback: ValueCallback<Array<android.net.Uri>>? = null

    @Deprecated("onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_RC) {
            fileChooserCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileChooserCallback = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        when {
            dvrOverlay.visibility == View.VISIBLE -> dvrBridge.value.stopStream()
            webView.canGoBack()                   -> webView.goBack()
            else                                  -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (sysBotBridge.isInitialized()) sysBotBridge.value.disconnectInternal()
        if (dvrBridge.isInitialized())    dvrBridge.value.stopStreamInternal()
        releaseWakeLock()
    }

    // ── 通知チャンネル ─────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID, "MHXXスナイプ通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "お守りスナイプ結果通知" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    // ── Wake Lock ──────────────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MhxxSnipe::SnipeWakeLock"
        )
        wakeLock?.acquire(4L * 60 * 60 * 1000)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    // =========================================================================
    //  SysDvrVideoBridge  ―  SysDVR 6.x TCP bridge H.264 映像ストリーミング
    //
    //  Switch側: SysDVR sysmodule を TCPブリッジモードで起動すること
    //  ポート: 9911 (映像)
    //
    //  JS側: window.AndroidDvr.startStream(ip) / .stopStream() / .isStreaming()
    //  コールバック: function onDvrStatus(e) { e.streaming, e.message }
    // =========================================================================
    inner class SysDvrVideoBridge {

        // SysDVR StreamInfo ― 固定 SPS / PPS  (1280×720, H.264 High Profile)
        private val SPS_NAL = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x67, 0x64, 0x0C, 0x20, 0xAC.toByte(), 0x2B, 0x40, 0x28,
            0x02, 0xDD.toByte(), 0x35, 0x01, 0x0D, 0x01, 0xE0.toByte(), 0x80.toByte()
        )
        private val PPS_NAL = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x68.toByte(), 0xEE.toByte(), 0x3C, 0xB0.toByte()
        )

        private val VIDEO_PORT       = 9911
        private val PACKET_MAGIC     = 0xCCCCCCCCL
        private val HANDSHAKE_RESP_SZ = 72
        private val HEADER_SZ        = 18           // PacketHeader size
        private val MAX_PAYLOAD      = 0x54000      // VideoPayloadSize
        private val META_VIDEO       = 0x01
        private val META_REPLAY      = 0x08

        @Volatile private var running        = false
        private var videoThread: Thread?     = null
        private var codec: MediaCodec?       = null
        private var codecStarted             = false

        // ── JS インターフェース ───────────────────────────────────────────────

        /** Switch の IP を指定して映像ストリームを開始 */
        @JavascriptInterface
        fun startStream(ip: String) {
            stopStreamInternal()
            running = true
            showOverlay()
            notifyDvrStatus(false, "接続中 → $ip:$VIDEO_PORT")
            videoThread = Thread {
                try {
                    runVideoStream(ip)
                } catch (e: InterruptedException) {
                    Log.i(TAG, "DVR thread stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "DVR error: ${e.message}")
                    notifyDvrStatus(false, "エラー: ${e.message?.take(80)}")
                } finally {
                    running = false
                    releaseCodec()
                    hideOverlay()
                }
            }.also { it.isDaemon = true; it.start() }
        }

        /** ストリームを停止し映像オーバーレイを閉じる */
        @JavascriptInterface
        fun stopStream() {
            notifyDvrStatus(false, "停止しました")
            stopStreamInternal()
        }

        /** ストリーミング中かどうか */
        @JavascriptInterface
        fun isStreaming(): Boolean = running

        /** Activity から呼ばれる内部停止 (onDestroy など) */
        fun stopStreamInternal() {
            running = false
            videoThread?.interrupt()
            videoThread = null
            releaseCodec()
            hideOverlay()
        }

        // ── メインループ ──────────────────────────────────────────────────────

        private fun runVideoStream(ip: String) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, VIDEO_PORT), 6000)
                socket.soTimeout = 6000
                val ins  = socket.getInputStream()
                val outs = socket.getOutputStream()

                // 1) Hello 受信 ― "SysDVR|02\0" (10 バイト)
                val hello = ByteArray(10)
                readFully(ins, hello)
                val helloStr = String(hello, Charsets.UTF_8).trimEnd('\u0000')
                if (!helloStr.startsWith("SysDVR|")) {
                    notifyDvrStatus(false, "Hello 失敗: $helloStr")
                    return
                }
                Log.i(TAG, "DVR Hello OK: $helloStr")

                // 2) ハンドシェイクリクエスト送信 (16 バイト)
                // struct ProtoHandshakeRequest { Magic(4) ProtoVer(2) MetaFlags VideoFlags AudioBatching FeatureFlags Reserved[6] }
                val req = ByteArray(16)
                req[0] = 0xAA.toByte(); req[1] = 0xAA.toByte()
                req[2] = 0xAA.toByte(); req[3] = 0xAA.toByte()   // Magic = 0xAAAAAAAA LE
                req[4] = 0x30;          req[5] = 0x32             // ProtoVer "02" LE ('0'=0x30, '2'=0x32)
                req[6] = META_VIDEO.toByte()                       // MetaFlags: video のみ
                req[7] = 0x02                                      // VideoFlags: InjectPPSSPS
                req[8] = 3                                         // AudioBatching
                // [9]..[15] = 0 (FeatureFlags + Reserved)
                outs.write(req)
                outs.flush()

                // 3) ハンドシェイクレスポンス受信 (72 バイト) ― result code == 6 で OK
                val resp = ByteArray(HANDSHAKE_RESP_SZ)
                readFully(ins, resp)
                val code = readU32LE(resp, 0)
                if (code != 6L) {
                    notifyDvrStatus(false, "ハンドシェイク失敗 code=$code")
                    return
                }
                Log.i(TAG, "DVR Handshake OK")

                // 4) MediaCodec セットアップ
                setupCodec()
                socket.soTimeout = 0
                notifyDvrStatus(true, "映像受信中 [$ip]")

                // 5) パケットループ
                // PacketHeader: Magic(4) DataSize(4) Timestamp(8) Flags(1) ReplaySlot(1) = 18 bytes
                val hdr = ByteArray(HEADER_SZ)
                while (running && !Thread.currentThread().isInterrupted) {
                    readFully(ins, hdr)
                    val magic    = readU32LE(hdr, 0)
                    if (magic != PACKET_MAGIC) { resync(ins, hdr); continue }
                    val dataSize = readU32LE(hdr, 4).toInt()
                    if (dataSize <= 0 || dataSize > MAX_PAYLOAD) { continue }
                    val flags    = hdr[16].toInt() and 0xFF
                    val isVideo  = (flags and META_VIDEO)  != 0
                    val isReplay = (flags and META_REPLAY) != 0

                    val data = ByteArray(dataSize)
                    readFully(ins, data)

                    if (isVideo && !isReplay) feedToCodec(data)
                }
            } finally {
                runCatching { socket.close() }
            }
        }

        // ── MediaCodec ────────────────────────────────────────────────────────

        private fun setupCodec() {
            // SurfaceView が ready になるまで最大 3 秒待機
            var waited = 0
            while (!surfaceView.holder.surface.isValid && waited < 3000) {
                Thread.sleep(50); waited += 50
            }
            codec = MediaCodec.createDecoderByType("video/avc").also { c ->
                val fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(SPS_NAL))
                    setByteBuffer("csd-1", ByteBuffer.wrap(PPS_NAL))
                }
                c.configure(fmt, surfaceView.holder.surface, null, 0)
                c.start()
                codecStarted = true
                Log.i(TAG, "MediaCodec started")
            }
        }

        private fun feedToCodec(data: ByteArray) {
            val c = codec ?: return
            try {
                val idx = c.dequeueInputBuffer(8_000L)
                if (idx >= 0) {
                    val buf = c.getInputBuffer(idx) ?: return
                    buf.clear()
                    val len = minOf(data.size, buf.capacity())
                    buf.put(data, 0, len)
                    c.queueInputBuffer(idx, 0, len, 0, 0)
                }
                val info = MediaCodec.BufferInfo()
                var out  = c.dequeueOutputBuffer(info, 0)
                while (out >= 0) { c.releaseOutputBuffer(out, true); out = c.dequeueOutputBuffer(info, 0) }
            } catch (e: Exception) { Log.w(TAG, "feedToCodec: ${e.message}") }
        }

        private fun releaseCodec() {
            runCatching {
                if (codecStarted) { codec?.stop(); codecStarted = false }
                codec?.release()
            }
            codec = null
        }

        // ── ユーティリティ ────────────────────────────────────────────────────

        @Throws(IOException::class, InterruptedException::class)
        private fun readFully(ins: InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                if (!running || Thread.currentThread().isInterrupted)
                    throw InterruptedException("stopped")
                val n = ins.read(buf, off, buf.size - off)
                if (n < 0) throw IOException("EOF at $off/${buf.size}")
                off += n
            }
        }

        private fun readU32LE(buf: ByteArray, off: Int): Long =
            (buf[off].toLong()       and 0xFF) or
            ((buf[off+1].toLong()    and 0xFF) shl 8) or
            ((buf[off+2].toLong()    and 0xFF) shl 16) or
            ((buf[off+3].toLong()    and 0xFF) shl 24)

        /** 0xCC が 4 バイト連続するまで読み飛ばしてパケット再同期 */
        private fun resync(ins: InputStream, hdrBuf: ByteArray) {
            Log.w(TAG, "DVR resync...")
            var cc = 0
            while (running && cc < 4) {
                val b = ins.read(); if (b < 0) throw IOException("EOF during resync")
                if (b == 0xCC) cc++ else cc = 0
            }
            val remain = ByteArray(HEADER_SZ - 4)
            readFully(ins, remain)
            hdrBuf[0] = 0xCC.toByte(); hdrBuf[1] = 0xCC.toByte()
            hdrBuf[2] = 0xCC.toByte(); hdrBuf[3] = 0xCC.toByte()
            remain.copyInto(hdrBuf, 4)
        }

        // ── オーバーレイ & JS 通知 ────────────────────────────────────────────

        private fun showOverlay() = runOnUiThread {
            dvrOverlay.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        private fun hideOverlay() = runOnUiThread {
            dvrOverlay.visibility = View.GONE
        }

        private fun notifyDvrStatus(streaming: Boolean, msg: String) {
            val json = JSONObject().apply { put("streaming", streaming); put("message", msg) }
            val b64  = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js   = """(function(){var b='$b64';var bin=atob(b);var u8=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);var s=new TextDecoder('utf-8').decode(u8);if(typeof onDvrStatus==='function')onDvrStatus(JSON.parse(s));})();"""
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // =========================================================================
    //  SysBotBridge  ―  sys-botbase TCP  (変更なし)
    // =========================================================================
    inner class SysBotBridge {

        @Volatile private var tcpSocket: Socket?      = null
        @Volatile private var tcpWriter: PrintWriter? = null
        @Volatile private var _connected              = false

        @JavascriptInterface
        fun connectSwitch(ip: String, port: Int) {
            Thread {
                try {
                    disconnectInternal(silent = true)
                    val s = Socket()
                    s.connect(InetSocketAddress(ip, port), 5000)
                    s.soTimeout = 0
                    tcpSocket = s
                    tcpWriter = PrintWriter(BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)), true)
                    _connected = true
                    notifyStatus(true, "接続成功: $ip:$port")
                } catch (e: Exception) {
                    _connected = false
                    notifyStatus(false, "接続失敗: ${e.message}")
                }
            }.start()
        }

        @JavascriptInterface fun disconnectSwitch() = disconnectInternal()

        fun disconnectInternal(silent: Boolean = false) {
            _connected = false
            tcpWriter?.runCatching { close() }; tcpWriter = null
            tcpSocket?.runCatching { close() }; tcpSocket = null
            if (!silent) notifyStatus(false, "切断しました")
        }

        @JavascriptInterface fun isConnected(): Boolean = _connected && tcpSocket?.isConnected == true
        @JavascriptInterface fun click(button: String)               = sendCmd("click $button")
        @JavascriptInterface fun clickCount(button: String, n: Int)  = sendCmd("clickCount $button $n")
        @JavascriptInterface fun press(button: String)               = sendCmd("press $button")
        @JavascriptInterface fun release(button: String)             = sendCmd("release $button")

        @JavascriptInterface
        fun setStick(side: String, x: Int, y: Int) {
            val nx = ((x.coerceIn(-100,100)/100.0*0x4000)+0x4000).toInt().coerceIn(0,0x7FFF)
            val ny = ((y.coerceIn(-100,100)/100.0*0x4000)+0x4000).toInt().coerceIn(0,0x7FFF)
            sendCmd("setStick $side $nx $ny")
        }
        @JavascriptInterface fun resetStick(side: String)    = setStick(side,0,0)
        @JavascriptInterface fun attachController()          = sendCmd("attachController")
        @JavascriptInterface fun detachController()          = sendCmd("detachController")
        @JavascriptInterface fun sendRaw(cmd: String)        = sendCmd(cmd.trimEnd())

        @JavascriptInterface
        fun vibrate(ms: Int) {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms.toLong())
        }

        @JavascriptInterface
        fun vibratePattern(patternJson: String) {
            try {
                val arr = org.json.JSONArray(patternJson)
                val pat = LongArray(arr.length()) { arr.getLong(it) }
                val v   = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createWaveform(pat, -1))
                else @Suppress("DEPRECATION") v.vibrate(pat, -1)
            } catch (e: Exception) { Log.e("SysBot","vibratePattern: ${e.message}") }
        }

        @JavascriptInterface
        fun setKeepScreenOn(on: Boolean) = runOnUiThread {
            if (on) { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); acquireWakeLock() }
            else    { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); releaseWakeLock() }
        }

        @JavascriptInterface
        fun showNotification(title: String, body: String) {
            val pi = PendingIntent.getActivity(
                this@MainActivity, 0,
                Intent(this@MainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK },
                PendingIntent.FLAG_IMMUTABLE)
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID,
                NotificationCompat.Builder(this@MainActivity, NOTIF_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title).setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setAutoCancel(true).setContentIntent(pi).build())
        }

        @JavascriptInterface
        fun showNativeToast(msg: String) =
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }

        private fun sendCmd(cmd: String): Boolean {
            if (!_connected) return false
            return try { tcpWriter?.println(cmd); true }
            catch (e: Exception) {
                _connected = false
                notifyStatus(false, "送信エラー: ${e.message}")
                false
            }
        }

        private fun notifyStatus(ok: Boolean, msg: String) {
            val json = JSONObject().apply { put("connected", ok); put("message", msg) }
            val b64  = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js   = """(function(){var b='$b64';var bin=atob(b);var u8=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);var s=new TextDecoder('utf-8').decode(u8);if(typeof onSysBotStatus==='function')onSysBotStatus(JSON.parse(s));})();"""
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // =========================================================================
    //  MlKitBridge  ―  ML Kit 日本語 OCR (変更なし)
    // =========================================================================
    inner class MlKitBridge {
        @JavascriptInterface
        fun runMlKit(base64Image: String) {
            try {
                val bytes  = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return returnError("画像デコード失敗")
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    .process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { r -> sendToJs(JSONObject().apply { put("text", r.text) }.toString()) }
                    .addOnFailureListener { e -> returnError(e.localizedMessage ?: "ML Kit 失敗") }
            } catch (e: Exception) { returnError(e.localizedMessage ?: "不明なエラー") }
        }
        private fun returnError(msg: String) =
            sendToJs(JSONObject().apply { put("error", msg) }.toString())
        private fun sendToJs(json: String) {
            val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js  = """(function(){var b='$b64';var bin=atob(b);var u8=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);var s=new TextDecoder('utf-8').decode(u8);receiveMlKitResult(s);})();"""
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }
}
