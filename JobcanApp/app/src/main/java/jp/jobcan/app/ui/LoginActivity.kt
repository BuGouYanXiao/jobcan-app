package jp.jobcan.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        service = JobcanService(this)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.hiddenWebView, true)
        }

        setupUI()
    }

    private fun setupUI() {
        sessionManager.getEmail()?.let { binding.etEmail.setText(it) }

        binding.btnLogin.setOnClickListener {
            val email    = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()

            binding.tilEmail.error     = null
            binding.tilPassword.error  = null
            binding.tvError.visibility = View.GONE

            when {
                email.isEmpty()    -> { binding.tilEmail.error    = "メールアドレスを入力してください"; return@setOnClickListener }
                password.isEmpty() -> { binding.tilPassword.error = "パスワードを入力してください";     return@setOnClickListener }
            }
            performLogin(email, password)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun performLogin(email: String, password: String) {
        if (isLoading) return
        isLoading      = true
        loginAttempted = false
        setLoadingState(true)

        val timeoutHandler  = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (isLoading) onLoginError("接続がタイムアウトしました。ネットワークを確認してください。")
        }
        timeoutHandler.postDelayed(timeoutRunnable, LOGIN_TIMEOUT_MS)

        binding.hiddenWebView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled   = true
                userAgentString   =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.6367.82 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "Page: $url")
                    when {
                        isLoginPage(url) && !loginAttempted -> {
                            loginAttempted = true
                            view.evaluateJavascript(buildLoginJS(email, password)) { result ->
                                Log.d(TAG, "JS: $result")
                            }
                        }
                        isEmployeePage(url) -> {
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            onLoginSuccess(email, password)
                        }
                        isLoginPage(url) && loginAttempted -> {
                            view.evaluateJavascript("""
                                (function() {
                                    var selectors = ['.error','.alert','.flash','[class*="error"]','#flash_message'];
                                    for (var s of selectors) {
                                        var el = document.querySelector(s);
                                        if (el && el.textContent.trim()) return el.textContent.trim();
                                    }
                                    return document.querySelector('form') ? 'FORM_VISIBLE' : 'UNKNOWN';
                                })()
                            """.trimIndent()) { raw ->
                                val msg = raw?.trim('"') ?: "UNKNOWN"
                                when {
                                    msg == "FORM_VISIBLE" -> {
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            view.evaluateJavascript(buildLoginJS(email, password)) {}
                                        }, 1500)
                                    }
                                    msg != "UNKNOWN" && msg != "null" -> {
                                        timeoutHandler.removeCallbacks(timeoutRunnable)
                                        onLoginError(msg)
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        onLoginError("接続エラーが発生しました。ネットワークを確認してください。")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        val code = errorResponse.statusCode
                        Log.e(TAG, "HTTP error: $code")
                        onLoginError("HTTPエラー: $code")
                    }
                }
            }

            loadUrl(JobcanService.LOGIN_URL)
        }
    }

    private fun isLoginPage(url: String)    = url.contains("login") || url.contains("sign_in")
    private fun isEmployeePage(url: String) = url.contains("ssl.jobcan.jp/employee") && !url.contains("login")

    private fun buildLoginJS(email: String, password: String): String {
        val safeEmail = email.replace("\\", "\\\\").replace("'", "\\'")
        val safePass  = password.replace("\\", "\\\\").replace("'", "\\'")
        return """
        (function() {
            var emailSels  = ['#user_email','#email','input[name="user[email]"]','input[type="email"]','#login_id','#staff_code'];
            var passSels   = ['#user_password','#password','input[name="user[password]"]','input[type="password"]'];
            var submitSels = ['#login_button','input[type="submit"]','button[type="submit"]','.btn-primary','button[name="commit"]'];
            function fill(sels, val) {
                for (var s of sels) {
                    var el = document.querySelector(s);
                    if (el) {
                        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
                        setter.call(el, val);
                        el.dispatchEvent(new Event('input',  {bubbles:true}));
                        el.dispatchEvent(new Event('change', {bubbles:true}));
                        return true;
                    }
                }
                return false;
            }
            var eOk = fill(emailSels, '$safeEmail');
            var pOk = fill(passSels,  '$safePass');
            if (eOk && pOk) {
                for (var s of submitSels) {
                    var btn = document.querySelector(s);
                    if (btn) { btn.click(); return 'clicked'; }
                }
                var form = document.querySelector('form');
                if (form) { form.submit(); return 'submitted'; }
            }
            return 'fill:email=' + eOk + ' pass=' + pOk;
        })()
        """.trimIndent()
    }

    private fun onLoginSuccess(email: String, password: String) {
        lifecycleScope.launch {
            try {
                sessionManager.saveCredentials(email, password)
                sessionManager.setLoggedIn(true)
                val userInfo = withContext(Dispatchers.IO) { service.getUserInfo() }
                userInfo?.let { sessionManager.saveUserName(it.name) }
            } catch (e: Exception) {
                Log.e(TAG, "Post-login error", e)
            }
            withContext(Dispatchers.Main) {
                isLoading = false
                setLoadingState(false)
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun onLoginError(message: String) {
        isLoading = false
        runOnUiThread {
            setLoadingState(false)
            binding.tvError.text       = message
            binding.tvError.visibility = View.VISIBLE
            loginAttempted             = false
        }
    }

    private fun setLoadingState(loading: Boolean) {
        binding.btnLogin.isEnabled     = !loading
        binding.btnLogin.text          = if (loading) "ログイン中..." else "ログイン"
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
