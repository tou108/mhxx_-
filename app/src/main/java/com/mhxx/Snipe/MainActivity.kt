package com.mhxx.snipe

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
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
                // 日本語フォントを正しく表示するための設定
                defaultTextEncodingName = "UTF-8"
                useWideViewPort         = true
                loadWithOverviewMode    = true
                setSupportZoom(true)
                builtInZoomControls     = true
                displayZoomControls     = false
            }

            // JavaScript ブリッジ登録 ("Android.runMlKit()" で JS から呼べる)
            addJavascriptInterface(MlKitBridge(), "Android")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    android.util.Log.d("MhxxSnipe/JS",
                        "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
                // ファイル選択ダイアログ（画像アップロード用）
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

            // assets フォルダの HTML を読み込む
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
    // JavaScript ⇄ Kotlin ブリッジ
    // HTML の JS から Android.runMlKit(base64) を呼ぶと
    // ML Kit で認識し receiveMlKitResult(json) をコールバックする
    // =========================================================
    inner class MlKitBridge {

        @JavascriptInterface
        fun runMlKit(base64Image: String) {
            try {
                // Base64 → Bitmap
                val bytes = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return returnError("画像デコード失敗")

                val image = InputImage.fromBitmap(bitmap, 0)

                // Japanese OCR（日本語特化モデル）
                val recognizer = TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build()
                )

                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val json = JSONObject().apply { put("text", result.text) }
                        sendToJs(json.toString())
                    }
                    .addOnFailureListener { e ->
                        returnError(e.localizedMessage ?: "ML Kit 認識失敗")
                    }

            } catch (e: Exception) {
                returnError(e.localizedMessage ?: "不明なエラー")
            }
        }

        private fun returnError(msg: String) {
            sendToJs(JSONObject().apply { put("error", msg) }.toString())
        }

        /**
         * JSON を UTF-8 Base64 でエンコードして JS に渡す。
         * 日本語文字列を直接埋め込むと escaping 問題が起きるため Base64 経由。
         */
        private fun sendToJs(json: String) {
            val b64 = Base64.encodeToString(
                json.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            // TextDecoder で UTF-8 デコード → receiveMlKitResult() に渡す
            val js = """
                (function(){
                    var b='$b64';
                    var bin=atob(b);
                    var u8=new Uint8Array(bin.length);
                    for(var i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i);
                    var s=new TextDecoder('utf-8').decode(u8);
                    receiveMlKitResult(s);
                })();
            """.trimIndent()
            runOnUiThread { webView.evaluateJavascript(js, null) }
        }
    }
}
