package jp.jobcan.app.service

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "jobcan_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(email: String, password: String) {
        prefs.edit()
            .putString("email", email)
            .putString("password", password)
            .apply()
    }

    fun getEmail(): String? = prefs.getString("email", null)
    fun getPassword(): String? = prefs.getString("password", null)

    fun hasCredentials(): Boolean =
        !getEmail().isNullOrEmpty() && !getPassword().isNullOrEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    // 表示名の保存
    fun saveUserName(name: String) = prefs.edit().putString("user_name", name).apply()
    fun getUserName(): String = prefs.getString("user_name", "ユーザー") ?: "ユーザー"

    // セッションフラグ
    fun setLoggedIn(value: Boolean) = prefs.edit().putBoolean("is_logged_in", value).apply()
    fun isLoggedIn(): Boolean = prefs.getBoolean("is_logged_in", false)
}
