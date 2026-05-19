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

        // SurfaceView は WebView より先に作成（dvrBridge.value が参照するため）
        surfaceView = SurfaceView(this)

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

            // JS ブリッジを登録
            addJavascriptInterface(MlKitBridge(),      "Android")
            addJavascriptInterface(sysBotBridge.value, "AndroidBridge")
            addJavascriptInterface(dvrBridge.value,    "AndroidDvr")  // SysDVR映像

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("MhxxSnipe/JS",
                        "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
                override fun onShowFileChooser(
                    wv: WebView, callback: ValueCallback<Array<android.net.Uri>>,
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

        // ── SysDVR 映像オーバーレイ ────────────────────────────────────────
        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setPadding(24, 24, 24, 24)
            setColorFilter(android.graphics.Color.WHITE)
            setOnClickListener { dvrBridge.value.stopStream() }
        }
        dvrOverlay = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(closeBtn, FrameLayout.LayoutParams(130, 130).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.topMargin = 24; it.marginEnd = 24
            })
            visibility = View.GONE
        }

        // ── Root (WebView + DVRオーバーレイ) ─────────────────────────────────
        setContentView(FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(dvrOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
    }

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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL_ID, "MHXXスナイプ通知",
                NotificationManager.IMPORTANCE_HIGH).apply { description = "お守りスナイプ結果通知" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MhxxSnipe::SnipeWakeLock")
        wakeLock?.acquire(4L * 60 * 60 * 1000)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    // =========================================================================
    //  SysDvrVideoBridge
    //  ― SysDVR 6.x TCP Bridge プロトコル完全実装 + MediaCodec H.264デコード
    //
    //  【SysDVR 6.3 プロトコル仕様】
    //  ・TCP ポート: 映像=9911、音声=9922（各ストリーム独立ソケット）
    //
    //  【ハンドシェイク手順】
    //  1. Switch → Client: Hello "SysDVR|03\0" (10バイト、null終端)
    //     - バージョン文字列をここから読む（v02 or v03、6.3は"03"）
    //  2. Client → Switch: ProtoHandshakeRequest (16バイト)
    //     - Magic    : 0xAAAAAAAA (LE)
    //     - ProtoVer : Helloから読んだバージョン文字列をLE uint16に変換
    //                  "03" → '0'=0x30, '3'=0x33 → uint16LE = [0x30, 0x33]
    //     - MetaFlags: 0x01 = video のみ
    //     - VideoFlags: 0x02 = InjectPPSSPS (SPS/PPSをストリームに埋め込む)
    //     - AudioBatching: 3
    //  3. Switch → Client: ProtoHandshakeResponse
    //     - v02: 4バイト (uint32 result code)
    //     - v03: 72バイト (uint32 result + メモリ情報)
    //     - Result == 6 (Handshake_Ok) で成功
    //
    //  【パケット形式】(PacketHeader = 18バイト)
    //  - Magic    : 4バイト (0xCCCCCCCC)
    //  - DataSize : 4バイト LE int32
    //  - Timestamp: 8バイト LE uint64 (マイクロ秒)
    //  - Flags    : 1バイト (bit0=Video, bit1=Audio, bit2=Data, bit3=Replay/Hash, bit4=MultiNAL)
    //  - ReplaySlot: 1バイト
    //
    //  JS インターフェース (window.AndroidDvr):
    //    .startStream(ip: String)  ― 映像ストリーム開始 (ポート9911)
    //    .stopStream()             ― 停止
    //    .isStreaming(): Boolean
    //
    //  JS コールバック:
    //    function onDvrStatus(e) { e.streaming: Boolean, e.message: String }
    // =========================================================================
    inner class SysDvrVideoBridge {

        // ─ SysDVR StreamInfo: 固定 SPS/PPS (1280×720 H.264 High Profile) ──
        // Protocol.cs の StreamInfo クラスから転記
        private val SPS_NAL = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x67, 0x64, 0x0C, 0x20,
            0xAC.toByte(), 0x2B, 0x40, 0x28,
            0x02, 0xDD.toByte(), 0x35, 0x01,
            0x0D, 0x01, 0xE0.toByte(), 0x80.toByte()
        )
        private val PPS_NAL = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x68.toByte(), 0xEE.toByte(), 0x3C, 0xB0.toByte()
        )

        // ─ プロトコル定数 ─────────────────────────────────────────────────
        private val VIDEO_PORT           = 9911
        private val AUDIO_PORT           = 9922          // 将来拡張用
        private val HELLO_SIZE           = 10            // "SysDVR|NN\0"
        private val REQUEST_SIZE         = 16            // ProtoHandshakeRequest
        private val RESP_SIZE_V02        = 4             // ProtoHandshakeResponse02
        private val RESP_SIZE_V03        = 72            // ProtoHandshakeResponse03
        private val HANDSHAKE_OK         = 6L            // Handshake_Ok
        private val REQUEST_MAGIC_BYTES  = byteArrayOf(
            0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte())
        private val PACKET_MAGIC         = 0xCCCCCCCCL
        private val HEADER_SIZE          = 18            // PacketHeader
        private val MAX_PAYLOAD          = 0x54000 + 18  // MaxTransferSize
        private val FLAG_VIDEO           = 0x01
        private val FLAG_AUDIO           = 0x02
        private val FLAG_DATA            = 0x04
        private val FLAG_REPLAY          = 0x08          // MetaIsHash (Replay)
        private val FLAG_MULTI_NAL       = 0x10

        @Volatile private var running       = false
        private var videoThread: Thread?    = null
        private var codec: MediaCodec?      = null
        private var codecStarted            = false

        // ── JS インターフェース ───────────────────────────────────────────────

        @JavascriptInterface
        fun startStream(ip: String) {
            stopStreamInternal()
            running = true
            showOverlay()
            notifyDvrStatus(false, "接続中 → $ip:$VIDEO_PORT")
            videoThread = Thread {
                try {
                    runVideoStream(ip.trim())
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

        @JavascriptInterface
        fun stopStream() {
            notifyDvrStatus(false, "停止しました")
            stopStreamInternal()
        }

        @JavascriptInterface
        fun isStreaming(): Boolean = running

        fun stopStreamInternal() {
            running = false
            videoThread?.interrupt()
            videoThread = null
            releaseCodec()
            hideOverlay()
        }

        // ── メインストリームループ ────────────────────────────────────────────

        private fun runVideoStream(ip: String) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, VIDEO_PORT), 6000)
                socket.soTimeout  = 8000
                socket.tcpNoDelay = true
                socket.setReceiveBufferSize(MAX_PAYLOAD)
                val ins  = socket.getInputStream()
                val outs = socket.getOutputStream()

                // ── Step 1: Hello 受信 ────────────────────────────────────
                // フォーマット: "SysDVR|NN\0" (10バイト、NNはプロトコルバージョン)
                val hello = ByteArray(HELLO_SIZE)
                readFully(ins, hello)
                val helloStr = String(hello, Charsets.ASCII).trimEnd('\u0000')
                if (!helloStr.startsWith("SysDVR|")) {
                    notifyDvrStatus(false, "Hello失敗: $helloStr")
                    return
                }
                // バージョン文字列を抽出 (位置7,8 例: "SysDVR|03\0" → "03")
                val verHigh   = hello[7]   // '0' = 0x30
                val verLow    = hello[8]   // '2' or '3'
                val verStr    = "${verHigh.toInt().toChar()}${verLow.toInt().toChar()}"
                val isV03     = verStr == "03"
                Log.i(TAG, "DVR Hello OK: $helloStr (protocol=$verStr)")

                // ── Step 2: ハンドシェイクリクエスト送信 (16バイト) ──────────
                // struct ProtoHandshakeRequest (packed, 16 bytes):
                //   uint32 Magic        = 0xAAAAAAAA
                //   uint16 ProtoVer     = Helloのバージョンをそのまま LE uint16に
                //                         "03" → '0'|('3'<<8) = 0x3330 → LE:[0x30,0x33]
                //   uint8  MetaFlags    = 0x01 (Video)
                //   uint8  VideoFlags   = 0x02 (InjectPPSSPS)
                //   uint8  AudioBatching= 3
                //   uint8  FeatureFlags = 0
                //   uint8[6] Reserved   = 0
                val req = ByteArray(REQUEST_SIZE)  // zero-initialized
                REQUEST_MAGIC_BYTES.copyInto(req, 0)       // Magic [0..3]
                req[4] = verHigh                           // ProtoVer low  byte (LE)
                req[5] = verLow                            // ProtoVer high byte (LE)
                req[6] = FLAG_VIDEO.toByte()               // MetaFlags: video only
                req[7] = 0x02                              // VideoFlags: InjectPPSSPS
                req[8] = 3                                 // AudioBatching
                // req[9]=FeatureFlags=0, req[10..15]=Reserved=0 (already zero)
                outs.write(req)
                outs.flush()

                // ── Step 3: ハンドシェイクレスポンス受信 ─────────────────────
                // v02: 4バイト (uint32 result)
                // v03: 72バイト (uint32 result + uint32 queryResult + 8×uint64 memory)
                val respSize  = if (isV03) RESP_SIZE_V03 else RESP_SIZE_V02
                val resp      = ByteArray(respSize)
                readFully(ins, resp)
                val code = readU32LE(resp, 0)
                if (code != HANDSHAKE_OK) {
                    val reason = when (code) {
                        1L -> "WrongVersion"
                        2L -> "InvalidArg"
                        3L -> "InvalidSize"
                        4L -> "InvalidMeta"
                        5L -> "WrongMagic"
                        7L -> "InvalidChannel"
                        else -> "code=$code"
                    }
                    notifyDvrStatus(false, "ハンドシェイク失敗: $reason")
                    return
                }
                Log.i(TAG, "DVR Handshake OK (proto=$verStr, respSize=$respSize)")

                // ── Step 4: MediaCodec セットアップ ──────────────────────────
                setupCodec()
                socket.soTimeout = 0   // ストリーミング中はタイムアウトなし
                notifyDvrStatus(true, "映像受信中 [$ip] proto=$verStr")

                // ── Step 5: パケット受信ループ ────────────────────────────────
                // PacketHeader (18バイト):
                //   uint32 Magic      = 0xCCCCCCCC
                //   int32  DataSize   (ペイロードのバイト数)
                //   uint64 Timestamp  (マイクロ秒)
                //   uint8  Flags
                //   uint8  ReplaySlot
                val hdr = ByteArray(HEADER_SIZE)
                while (running && !Thread.currentThread().isInterrupted) {
                    readFully(ins, hdr)
                    val magic    = readU32LE(hdr, 0)
                    if (magic != PACKET_MAGIC) {
                        resync(ins, hdr)
                        continue
                    }
                    val dataSize = readI32LE(hdr, 4)
                    if (dataSize < 0 || dataSize > MAX_PAYLOAD) {
                        Log.w(TAG, "Invalid dataSize=$dataSize, resyncing")
                        resync(ins, hdr)
                        continue
                    }
                    val flags    = hdr[16].toInt() and 0xFF
                    val isVideo  = (flags and FLAG_VIDEO)  != 0
                    val isReplay = (flags and FLAG_REPLAY) != 0
                    val isData   = (flags and FLAG_DATA)   != 0

                    val data = ByteArray(dataSize)
                    if (dataSize > 0) readFully(ins, data)

                    // リプレイパケット: キャッシュから再生が必要だが省略可
                    // (InjectPPSSPSを有効にしているのでデコーダは初期化済みのため
                    //  リプレイなしでも映像は表示される。低負荷ネットワーク向け)
                    if (isVideo && !isReplay && dataSize > 0) {
                        feedToCodec(data)
                    }
                }
            } finally {
                runCatching { socket.close() }
            }
        }

        // ── MediaCodec H.264デコーダ ──────────────────────────────────────────

        private fun setupCodec() {
            // SurfaceView の Surface が ready になるまで最大5秒待機
            var waited = 0
            while (!surfaceView.holder.surface.isValid && waited < 5000) {
                Thread.sleep(50); waited += 50
            }
            if (!surfaceView.holder.surface.isValid)
                throw Exception("SurfaceView が準備できませんでした")

            codec = MediaCodec.createDecoderByType("video/avc").also { c ->
                val fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
                    // csd-0: SPS (start code 付き Annex B)
                    setByteBuffer("csd-0", ByteBuffer.wrap(SPS_NAL))
                    // csd-1: PPS (start code 付き Annex B)
                    setByteBuffer("csd-1", ByteBuffer.wrap(PPS_NAL))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_PAYLOAD)
                }
                c.configure(fmt, surfaceView.holder.surface, null, 0)
                c.start()
                codecStarted = true
                Log.i(TAG, "MediaCodec H.264 decoder started (1280x720)")
            }
        }

        private fun feedToCodec(data: ByteArray) {
            val c = codec ?: return
            try {
                // 入力バッファにNALユニットをキュー
                val inIdx = c.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx) ?: return
                    buf.clear()
                    val len = minOf(data.size, buf.capacity())
                    buf.put(data, 0, len)
                    c.queueInputBuffer(inIdx, 0, len, 0L, 0)
                }
                // 出力バッファを取り出してSurfaceにレンダリング
                val info = MediaCodec.BufferInfo()
                var outIdx = c.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    c.releaseOutputBuffer(outIdx, true)  // true = Surface にレンダリング
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "feedToCodec error: ${e.message}")
            }
        }

        private fun releaseCodec() {
            try {
                if (codecStarted) { codec?.stop(); codecStarted = false }
                codec?.release()
            } catch (_: Exception) {}
            codec = null
        }

        // ── ストリーム再同期 ──────────────────────────────────────────────────
        // ネットワーク不安定時に 0xCC が4バイト連続するまで読み飛ばして同期回復
        private fun resync(ins: InputStream, hdrBuf: ByteArray) {
            Log.w(TAG, "DVR resync...")
            var ccCount = 0
            while (running && ccCount < 4) {
                val b = ins.read()
                if (b < 0) throw IOException("EOF during resync")
                if (b == 0xCC) ccCount++ else ccCount = 0
            }
            // マジック4バイトを読み終えた→残り14バイトを読む
            val remain = ByteArray(HEADER_SIZE - 4)
            readFully(ins, remain)
            hdrBuf[0] = 0xCC.toByte(); hdrBuf[1] = 0xCC.toByte()
            hdrBuf[2] = 0xCC.toByte(); hdrBuf[3] = 0xCC.toByte()
            remain.copyInto(hdrBuf, 4)
        }

        // ── ユーティリティ ────────────────────────────────────────────────────

        @Throws(IOException::class, InterruptedException::class)
        private fun readFully(ins: InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                if (!running || Thread.currentThread().isInterrupted)
                    throw InterruptedException("Stream stopped")
                val n = ins.read(buf, off, buf.size - off)
                if (n < 0) throw IOException("Unexpected EOF (read $off/${buf.size})")
                off += n
            }
        }

        private fun readU32LE(buf: ByteArray, off: Int): Long =
            (buf[off].toLong()    and 0xFF)        or
            ((buf[off+1].toLong() and 0xFF) shl 8)  or
            ((buf[off+2].toLong() and 0xFF) shl 16) or
            ((buf[off+3].toLong() and 0xFF) shl 24)

        private fun readI32LE(buf: ByteArray, off: Int): Int =
            (buf[off].toInt()    and 0xFF)        or
            ((buf[off+1].toInt() and 0xFF) shl 8)  or
            ((buf[off+2].toInt() and 0xFF) shl 16) or
            ((buf[off+3].toInt() and 0xFF) shl 24)

        // ── オーバーレイ & JS通知 ─────────────────────────────────────────────

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
            val js   = """(function(){try{var b='$b64',bin=atob(b),u8=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);var s=new TextDecoder('utf-8').decode(u8);if(typeof onDvrStatus==='function')onDvrStatus(JSON.parse(s));}catch(e){}})();"""
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // =========================================================================
    //  SysBotBridge  ―  sys-botbase TCP (変更なし)
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
                    tcpWriter = PrintWriter(BufferedWriter(
                        OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)), true)
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

        @JavascriptInterface fun isConnected() = _connected && tcpSocket?.isConnected == true
        @JavascriptInterface fun click(b: String)             = sendCmd("click $b")
        @JavascriptInterface fun clickCount(b: String, n: Int)= sendCmd("clickCount $b $n")
        @JavascriptInterface fun press(b: String)             = sendCmd("press $b")
        @JavascriptInterface fun release(b: String)           = sendCmd("release $b")

        @JavascriptInterface
        fun setStick(side: String, x: Int, y: Int) {
            val nx = ((x.coerceIn(-100,100)/100.0*0x4000)+0x4000).toInt().coerceIn(0,0x7FFF)
            val ny = ((y.coerceIn(-100,100)/100.0*0x4000)+0x4000).toInt().coerceIn(0,0x7FFF)
            sendCmd("setStick $side $nx $ny")
        }
        @JavascriptInterface fun resetStick(s: String) = setStick(s,0,0)
        @JavascriptInterface fun attachController()    = sendCmd("attachController")
        @JavascriptInterface fun detachController()    = sendCmd("detachController")
        @JavascriptInterface fun sendRaw(cmd: String)  = sendCmd(cmd.trimEnd())

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
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
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
            val pi = PendingIntent.getActivity(this@MainActivity, 0,
                Intent(this@MainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }, PendingIntent.FLAG_IMMUTABLE)
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
            val js   = """(function(){try{var b='$b64',bin=atob(b),u8=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);var s=new TextDecoder('utf-8').decode(u8);if(typeof onSysBotStatus==='function')onSysBotStatus(JSON.parse(s));}catch(e){}})();"""
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }

    // =========================================================================
    //  MlKitBridge  ―  ML Kit 日本語OCR (変更なし)
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
            val js  = """(function(){try{var b='$b64',bin=atob(b),u8=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);var s=new TextDecoder('utf-8').decode(u8);receiveMlKitResult(s);}catch(e){}})();"""
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }
}
