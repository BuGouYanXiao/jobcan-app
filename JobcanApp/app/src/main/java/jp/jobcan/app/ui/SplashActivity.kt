package jp.jobcan.app.ui

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cookie有効化
        CookieManager.getInstance().setAcceptCookie(true)

        // 直接ログイン画面へ（セッション確認は省略してクラッシュ回避）
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
