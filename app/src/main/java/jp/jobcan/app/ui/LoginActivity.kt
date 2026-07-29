package jp.jobcan.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.jobcan.app.databinding.ActivityLoginBinding
import jp.jobcan.app.service.JobcanService
import jp.jobcan.app.service.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var service: JobcanService

    private var isLoading = false
    private var loginAttempted = false

    companion object {
        private const val TAG = "LoginActivity"
        private const val LOGIN_TIMEOUT_MS = 30_000L
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        service = JobcanService(this)

        // WebView: Cookie同期のため事前に有効化
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.hiddenWebView, true)
        }

        setupWebView()
        setupUI()
    }

    private fun setupUI() {
        // 保存済みメールを自動入力
        sessionManager.getEmail()?.let { binding.etEmail.setText(it) }

        binding.btnLogin.setOnClickListener {
            val email    = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()

            binding.tilEmail.error    = null
            binding.tilPassword.error = null
            binding.tvError.visibility = View.GONE

            when {
                email.isEmpty()    -> { binding.tilEmail.error = "メールアドレスを入力してください"; return@setOnClickListener }
                password.isEmpty() -> { binding.tilPassword.error = "パスワードを入力してください"; return@setOnClickListener }
            }

            performLogin(email, password)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.hiddenWebView.apply {
            settings.apply {
                javaScriptEnabled      = true
                domStorageEnabled      = true
                databaseEnabled        = true
                javaScriptCanOpenWindowsAutomatically = false
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.6367.82 Mobile Safari/537.36"
            }
        }
    }

    private fun performLogin(email: String, password: String) {
        if (isLoading) return
        isLoading = true
        loginAttempted = false
        setLoadingState(true)

        Log.d(TAG, "Starting login for: $email")

        // タイムアウト保護
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (isLoading) {
                Log.w(TAG, "Login timed out")
                onLoginError("接続がタイムアウトしました。ネットワークを確認してください。")
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, LOGIN_TIMEOUT_MS)

        binding.hiddenWebView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "Page loaded: $url")

                when {
                    // ① Jobcanのログインページ → フォームに入力してsubmit
                    isLoginPage(url) && !loginAttempted -> {
                        loginAttempted = true
                        view.evaluateJavascript(buildLoginJS(email, password)) { result ->
                            Log.d(TAG, "Login JS result: $result")
                        }
                    }

                    // ② 従業員マイページに到達 → ログイン成功
                    isEmployeePage(url) -> {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        Log.d(TAG, "Login success! URL: $url")
                        onLoginSuccess(email, password)
                    }

                    // ③ ログインページのまま（エラーの可能性）
                    isLoginPage(url) && loginAttempted -> {
                        view.evaluateJavascript("""
                            (function() {
                                var selectors = [
                                    '.error', '.alert', '.flash', '[class*="error"]',
                                    '[class*="alert"]', '#flash_message', '.notice'
                                ];
                                for (var s of selectors) {
                                    var el = document.querySelector(s);
                                    if (el && el.textContent.trim()) {
                                        return el.textContent.trim();
                                    }
                                }
                                // フォームが存在するならまだログインページ
                                var form = document.querySelector('form');
                                return form ? 'FORM_STILL_VISIBLE' : 'UNKNOWN';
                            })()
                        """.trimIndent()) { rawError ->
                            val errorText = rawError?.trim('"') ?: "UNKNOWN"
                            Log.d(TAG, "Error detection: $errorText")

                            when {
                                errorText == "FORM_STILL_VISIBLE" -> {
                                    // まだフォームが見える→再試行
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        view.evaluateJavascript(buildLoginJS(email, password)) {}
                                    }, 1000)
                                }
                                errorText != "UNKNOWN" && errorText != "null" -> {
                                    timeoutHandler.removeCallbacks(timeoutRunnable)
                                    onLoginError(errorText)
                                }
                                else -> {
                                    // 何も検出できなかった場合は少し待つ
                                }
                            }
                        }
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    Log.e(TAG, "WebView error: ${error.description}")
                    onLoginError("接続エラー: ${error.description}")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: HttpErrorResponse) {
                if (request.isForMainFrame) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    Log.e(TAG, "HTTP error: ${errorResponse.statusCode}")
                    onLoginError("HTTPエラー: ${errorResponse.statusCode}")
                }
            }
        }

        binding.hiddenWebView.loadUrl(JobcanService.LOGIN_URL)
    }

    // ── URL判定ヘルパー ───────────────────────────────────────────
    private fun isLoginPage(url: String) =
        url.contains("login") || url.contains("sign_in") || url.contains("sign-in")

    private fun isEmployeePage(url: String) =
        url.contains("ssl.jobcan.jp/employee") &&
        !url.contains("login") && !url.contains("sign")

    // ── ログインフォーム入力JS ────────────────────────────────────
    private fun buildLoginJS(email: String, password: String): String {
        // メアド・パスワードを JavaScript 内でエスケープ
        val safeEmail = email.replace("\\", "\\\\").replace("'", "\\'")
        val safePass  = password.replace("\\", "\\\\").replace("'", "\\'")

        return """
        (function() {
            // 可能性のあるすべてのフィールドを試す
            var emailSelectors = [
                '#user_email', '#email', '#staff_email',
                'input[name="user[email]"]', 'input[name="email"]',
                'input[type="email"]', '#login_id', '#staff_code'
            ];
            var passSelectors = [
                '#user_password', '#password', '#staff_password',
                'input[name="user[password]"]', 'input[name="password"]',
                'input[type="password"]'
            ];
            var submitSelectors = [
                '#login_button', 'input[type="submit"]',
                'button[type="submit"]', '.btn-primary', '.login-btn',
                'button[name="commit"]'
            ];

            function fillField(selectors, value) {
                for (var s of selectors) {
                    var el = document.querySelector(s);
                    if (el) {
                        var nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                            window.HTMLInputElement.prototype, 'value').set;
                        nativeInputValueSetter.call(el, value);
                        el.dispatchEvent(new Event('input',  {bubbles: true}));
                        el.dispatchEvent(new Event('change', {bubbles: true}));
                        return true;
                    }
                }
                return false;
            }

            var emailOk = fillField(emailSelectors, '$safeEmail');
            var passOk  = fillField(passSelectors,  '$safePass');

            if (emailOk && passOk) {
                for (var s of submitSelectors) {
                    var btn = document.querySelector(s);
                    if (btn) { btn.click(); return 'clicked:' + s; }
                }
                var form = document.querySelector('form');
                if (form) { form.submit(); return 'form_submitted'; }
                return 'no_submit_found';
            }
            return 'fill_failed email:' + emailOk + ' pass:' + passOk;
        })()
        """.trimIndent()
    }

    // ── ログイン成功処理 ──────────────────────────────────────────
    private fun onLoginSuccess(email: String, password: String) {
        lifecycleScope.launch {
            // OkHttpのCookieJarを同期してセッション確認
            val valid = withContext(Dispatchers.IO) { service.isSessionValid() }

            if (valid) {
                sessionManager.saveCredentials(email, password)
                sessionManager.setLoggedIn(true)

                // ユーザー名取得
                val userInfo = withContext(Dispatchers.IO) { service.getUserInfo() }
                userInfo?.let { sessionManager.saveUserName(it.name) }

                withContext(Dispatchers.Main) {
                    isLoading = false
                    setLoadingState(false)
                    Toast.makeText(this@LoginActivity, "ログインしました", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            } else {
                // WebViewはOKだがOkHttpのセッションが取れなかった→再試行不要、直接遷移
                withContext(Dispatchers.Main) {
                    sessionManager.saveCredentials(email, password)
                    sessionManager.setLoggedIn(true)
                    isLoading = false
                    setLoadingState(false)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    // ── ログイン失敗処理 ──────────────────────────────────────────
    private fun onLoginError(message: String) {
        isLoading = false
        runOnUiThread {
            setLoadingState(false)
            binding.tvError.text = message
            binding.tvError.visibility = View.VISIBLE
            loginAttempted = false
        }
    }

    private fun setLoadingState(loading: Boolean) {
        binding.btnLogin.isEnabled  = !loading
        binding.btnLogin.text       = if (loading) "ログイン中..." else "ログイン"
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
