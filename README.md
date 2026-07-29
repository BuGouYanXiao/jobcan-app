# Jobcan Android App

ジョブカン勤怠管理のモダンなAndroidネイティブアプリ。

## 特徴

- ✅ **実際にJobcanへ打刻** (WebView→OkHttp Cookie共有で認証突破)
- ✅ 丸形打刻ボタン（出勤/退勤/休憩 で色変化）
- ✅ 企業ID固定: `C16117-95623-870523`
- ✅ 打刻後にJobcanのマイページを再取得して状態を確認
- ✅ 認証情報はAndroid EncryptedSharedPreferencesで暗号化保存

## APKビルド方法

### 方法1: Android Studio（推奨・最速）

1. Android Studioで `JobcanApp` フォルダを開く
2. ツールバー「▶ Run」または `Build > Build APK(s)` をクリック
3. APKは `app/build/outputs/apk/debug/app-debug.apk` に生成

### 方法2: コマンドライン

```bash
cd JobcanApp
# Macの場合
export ANDROID_HOME=$HOME/Library/Android/sdk
# Windowsの場合
# set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk

chmod +x gradlew
./gradlew assembleDebug
```

### 方法3: GitHub Actions（自動ビルド）

1. このリポジトリをGitHubにpush
2. Actions → "Build APK" → "Run workflow"
3. 完了後、Artifactsから `jobcan-debug-apk.zip` をダウンロード

## インストール

```bash
# ADB経由でインストール
adb install app/build/outputs/apk/debug/app-debug.apk

# または端末のファイルマネージャーでAPKを開く
# （設定→セキュリティ→提供元不明のアプリ を有効にする）
```

## アーキテクチャ

```
ログイン:  hiddenWebView でJobcanにアクセス → JSでフォーム入力・サブミット
           → onPageFinished で /employee URL 到達を検知 → 成功
セッション: WebViewのCookieをOkHttpのCookieJarと共有
打刻:     OkHttp で POST /employee/index/adit
          → CSRFトークン・グループ名をマイページHTMLから抽出して送信
状態確認:  打刻後に GET /employee を再取得してHTMLをパース
```

## ファイル構成

```
app/src/main/java/jp/jobcan/app/
├── model/Models.kt           # データモデル
├── service/
│   ├── JobcanService.kt      # Jobcan通信（ログイン/打刻/状態取得）
│   └── SessionManager.kt    # 認証情報の暗号化保存
└── ui/
    ├── SplashActivity.kt     # 起動画面・セッション確認
    ├── LoginActivity.kt      # ログイン（WebView）
    └── MainActivity.kt       # 打刻画面
```
