package jp.jobcan.app.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.jobcan.app.service.JobcanService
import jp.jobcan.app.service.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WebViewのCookieを有効化
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(null, true)
        }

        lifecycleScope.launch {
            delay(1000) // スプラッシュ表示時間

            val sessionManager = SessionManager(this@SplashActivity)
            val service = JobcanService(this@SplashActivity)

            // 保存済みセッション確認
            if (sessionManager.hasCredentials() && sessionManager.isLoggedIn()) {
                try {
    val valid = service.isSessionValid()
    if (valid) {
        goToMain()
        return@launch
    }
} catch (e: Exception) {
    // セッション確認失敗は無視
}
goToLogin()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
