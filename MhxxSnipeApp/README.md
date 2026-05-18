# MHXX スナイプ APK

MHXX 護石スナイプ統合ツールの Android アプリ版。  
**Google ML Kit Japanese Text Recognition** を搭載したローカルOCRで護石を認識します。

## 機能

| 機能 | 詳細 |
|---|---|
| OCRエンジン | **Google ML Kit** (日本語特化・完全オフライン) |
| Fallback | Tesseract.js（ブラウザ版との互換性維持） |
| 動作環境 | Android 8.0 (API 26) 以上 |
| オフライン | ✅ ネット不要（ML Kit モデルはアプリと同梱） |

## GitHub Actions でのビルド方法（APK取得）

1. このプロジェクトを GitHub にアップロード
2. `Actions` タブ → `Build APK` → `Run workflow`
3. ビルド完了後、`Artifacts` から APK をダウンロード

> **初回ビルド**: `gradle-wrapper.jar` が無い場合、CI が自動生成します。

## Android Studio でのローカルビルド

```bash
# gradle-wrapper.jar を生成（初回のみ）
gradle wrapper --gradle-version 8.4

# Debug APK ビルド
./gradlew assembleDebug

# APK の場所
# app/build/outputs/apk/debug/app-debug.apk
```

## プロジェクト構造

```
MhxxSnipeApp/
├── app/src/main/
│   ├── assets/
│   │   └── snipe_modified.html   ← ツール本体 HTML
│   ├── java/com/mhxx/snipe/
│   │   └── MainActivity.kt       ← WebView + ML Kit ブリッジ
│   └── AndroidManifest.xml
├── .github/workflows/
│   └── build.yml                 ← GitHub Actions CI
└── README.md
```

## OCR の仕組み

```
HTML (JS) side                     Kotlin (Native) side
─────────────────────────────────────────────────────
画像アップロード
  │
  ▼
window.Android.runMlKit(base64) ──→ MlKitBridge.runMlKit()
                                         │
                                         ▼
                                    InputImage.fromBitmap()
                                         │
                                         ▼
                                    JapaneseTextRecognizer
                                    .process(image)
                                         │
                                    result.text (日本語テキスト)
                                         │
                                         ▼
receiveMlKitResult(json) ←──────── evaluateJavascript()
  │
  ▼
parseOcrResult() → スキル・スロット解析 → 検索実行
```

## 依存関係

- `com.google.mlkit:text-recognition-japanese:16.0.0`
- `androidx.appcompat:appcompat:1.6.1`
- Kotlin 1.9.22 / AGP 8.2.2 / Gradle 8.4

## 注意

- 初回インストール時、ML Kit が日本語OCRモデルを自動ダウンロードします（約15MB）
- `AndroidManifest.xml` に `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="ocr_japanese" />` を設定済みのため、インストール直後から使用可能
