package com.mhxx.snipe

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled       = true
                domStorageEnabled       = true
                allowFileAccess         = true
                allowContentAccess      = true
                mixedContentMode        = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                defaultTextEncodingName = "UTF-8"
                useWideViewPort         = true
                loadWithOverviewMode    = true
                setSupportZoom(true)
                builtInZoomControls     = true
                displayZoomControls     = false
            }

            // OCR ブリッジ登録
            addJavascriptInterface(OcrBridge(), "AndroidOcr")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    android.util.Log.d("MhxxSnipe/JS",
                        "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
                override fun onShowFileChooser(
                    wv: WebView,
                    callback: ValueCallback<Array<android.net.Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileChooserCallback = callback
                    val intent = params.createIntent()
                    runCatching {
                        startActivityForResult(intent, FILE_CHOOSER_RC)
                    }.onFailure { callback.onReceiveValue(null) }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("MhxxSnipe", "Page loaded: $url")
                }
            }

            loadUrl("file:///android_asset/snipe_modified.html")
        }

        setContentView(webView)
    }

    // ── ファイル選択コールバック ────────────────────────────────────
    private var fileChooserCallback: ValueCallback<Array<android.net.Uri>>? = null
    private val FILE_CHOOSER_RC = 1001

    @Deprecated("onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == FILE_CHOOSER_RC) {
            fileChooserCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
            fileChooserCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // =========================================================
    // OCR ブリッジ (JS → Kotlin → ML Kit → JS)
    //
    // JS から AndroidOcr.runOcr(base64) を呼ぶと
    // 以下の前処理 + ML Kit Text Recognition v2 を実行し
    // receiveMlKitResult(json) をコールバックする
    //
    // 前処理パイプライン:
    //   1. Base64 → Bitmap デコード
    //   2. 2倍拡大 (LANCZOS相当: filterBitmap=true)
    //   3. グレースケール変換
    //   4. 適応的二値化 (Otsu風 閾値自動計算)
    //   5. ML Kit Japanese Text Recognition v2 に投入
    // =========================================================
    inner class OcrBridge {

        /**
         * メイン OCR エントリポイント
         * @param base64Image  JPEG/PNG の Base64 文字列（クロップ済み画像を JS 側で渡す）
         */
        @JavascriptInterface
        fun runOcr(base64Image: String) {
            try {
                // ── Step 1: Base64 デコード ──────────────────────────
                val bytes = Base64.decode(base64Image, Base64.DEFAULT)
                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return sendError("画像デコード失敗")

                // ── Step 2: 2倍拡大 ───────────────────────────────────
                // ML Kit はドットフォントなど小さい文字が苦手なため拡大して精度向上
                val scaled = Bitmap.createScaledBitmap(
                    original,
                    original.width * 2,
                    original.height * 2,
                    true   // bilinear filter (高品質補間)
                )

                // ── Step 3: グレースケール変換 ────────────────────────
                val gray = toGrayscale(scaled)

                // ── Step 4: 適応的二値化 ──────────────────────────────
                // ゲーム画面の不均一な背景に対してOtsu法で自動閾値を決定
                val binarized = binarize(gray)

                // ── Step 5: ML Kit v2 (日本語) 認識 ───────────────────
                val image = InputImage.fromBitmap(binarized, 0)
                val recognizer = TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build()
                )

                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        // 認識結果をブロック単位でJSONに変換
                        val blocksJson = JSONArray()
                        for (block in result.textBlocks) {
                            val blockObj = JSONObject()
                            blockObj.put("text", block.text)
                            val linesArr = JSONArray()
                            for (line in block.lines) {
                                val lineObj = JSONObject()
                                lineObj.put("text", line.text)
                                val elemsArr = JSONArray()
                                for (elem in line.elements) {
                                    elemsArr.put(elem.text)
                                }
                                lineObj.put("elements", elemsArr)
                                linesArr.put(lineObj)
                            }
                            blockObj.put("lines", linesArr)
                            blocksJson.put(blockObj)
                        }
                        val json = JSONObject().apply {
                            put("text", result.text)
                            put("blocks", blocksJson)
                        }
                        sendToJs(json.toString())
                    }
                    .addOnFailureListener { e ->
                        sendError(e.localizedMessage ?: "ML Kit 認識失敗")
                    }

            } catch (e: Exception) {
                sendError(e.localizedMessage ?: "不明なエラー")
            }
        }

        // ── 画像前処理ユーティリティ ──────────────────────────────────

        /**
         * Bitmap → グレースケール Bitmap (ARGB_8888 維持)
         */
        private fun toGrayscale(src: Bitmap): Bitmap {
            val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(dst)
            val paint = Paint()
            val cm = ColorMatrix()
            cm.setSaturation(0f)              // 彩度0 = グレースケール
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(src, 0f, 0f, paint)
            return dst
        }

        /**
         * グレースケール Bitmap → 二値化 Bitmap
         * Otsu法で最適閾値を自動計算。
         * ゲーム画面は「明るい文字 / 暗い背景」のケースが多いため
         * 閾値以上の輝度ピクセルを白(文字)、以下を黒(背景)に変換する。
         * ただし背景が明るい場合は自動反転する。
         */
        private fun binarize(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            // ヒストグラム作成 (輝度 0-255)
            val hist = IntArray(256)
            for (px in pixels) {
                val r = (px shr 16) and 0xFF
                val g = (px shr 8)  and 0xFF
                val b = px          and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                hist[lum]++
            }

            // Otsu法で最適閾値を計算
            val total = pixels.size.toDouble()
            var sumAll = 0.0
            for (i in 0..255) sumAll += i * hist[i]

            var sumB = 0.0; var wB = 0.0
            var maxVar = 0.0; var threshold = 128

            for (t in 0..255) {
                wB += hist[t]
                if (wB == 0.0) continue
                val wF = total - wB
                if (wF == 0.0) break
                sumB += t * hist[t]
                val mB = sumB / wB
                val mF = (sumAll - sumB) / wF
                val varBetween = wB * wF * (mB - mF) * (mB - mF)
                if (varBetween > maxVar) { maxVar = varBetween; threshold = t }
            }

            // ゲーム画面: 高輝度ピクセルが多い場合は白背景→黒文字なので反転
            var brightCount = 0
            for (px in pixels) {
                val lum = ((px shr 16 and 0xFF) * 299 + (px shr 8 and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
                if (lum > threshold) brightCount++
            }
            val invertNeeded = brightCount > pixels.size / 2

            // 二値化適用
            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8)  and 0xFF
                val b = pixels[i]           and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                val white = if (invertNeeded) lum <= threshold else lum > threshold
                pixels[i] = if (white) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }

            val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            dst.setPixels(pixels, 0, w, 0, 0, w, h)
            return dst
        }

        // ── JS コールバック ───────────────────────────────────────────

        private fun sendError(msg: String) {
            sendToJs(JSONObject().apply { put("error", msg) }.toString())
        }

        /**
         * JSON を UTF-8 Base64 経由で JS に渡す。
         * 日本語を直接埋め込むと escaping 問題が起きるため Base64 経由。
         */
        private fun sendToJs(json: String) {
            val b64 = Base64.encodeToString(
                json.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val js = """
                (function(){
                    var b='$b64';
                    var bin=atob(b);
                    var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);
                    if(typeof receiveMlKitResult==='function') receiveMlKitResult(s);
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }
}
