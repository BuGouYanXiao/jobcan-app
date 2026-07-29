package jp.jobcan.app.service

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import jp.jobcan.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class JobcanService(private val context: Context) {

    companion object {
        private const val TAG = "JobcanService"
        const val CLIENT_ID  = "C16117-95623-870523"
        const val BASE_URL   = "https://ssl.jobcan.jp"
        const val LOGIN_URL  = "$BASE_URL/login/mb-employee?client_id=$CLIENT_ID&lang_code=ja"
        const val EMPLOYEE_URL = "$BASE_URL/employee"
        const val ADIT_URL   = "$BASE_URL/employee/index/adit"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(WebViewCookieJar())
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ja,en-US;q=0.7,en;q=0.3")
                .build()
            chain.proceed(req)
        }
        .build()

    // ── セッション有効チェック ────────────────────────────────────
    suspend fun isSessionValid(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(EMPLOYEE_URL).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val finalUrl = resp.request.url.toString()
            val valid = resp.isSuccessful
                && !finalUrl.contains("login")
                && (body.contains("adit") || body.contains("employee") || body.contains("打刻"))
            Log.d(TAG, "isSessionValid=$valid url=$finalUrl")
            valid
        } catch (e: Exception) {
            Log.e(TAG, "isSessionValid error", e)
            false
        }
    }

    // ── 今日の打刻状態取得 ────────────────────────────────────────
    suspend fun getTodayInfo(): TodayInfo = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(EMPLOYEE_URL)
                ?: return@withContext TodayInfo(
                    status = ClockStatus.ERROR,
                    errorMessage = "マイページの取得に失敗しました。ネットワークを確認してください。"
                )
            Log.d(TAG, "getTodayInfo HTML length=${html.length}")
            parseEmployeePage(html)
        } catch (e: Exception) {
            Log.e(TAG, "getTodayInfo error", e)
            TodayInfo(status = ClockStatus.ERROR, errorMessage = e.message ?: "不明なエラー")
        }
    }

    // ── 打刻送信 ──────────────────────────────────────────────────
    suspend fun punch(type: ClockType): PunchResult = withContext(Dispatchers.IO) {
        try {
            // Step1: マイページからCSRFトークンを取得
            val html = fetchHtml(EMPLOYEE_URL)
                ?: return@withContext PunchResult(false, "ページの取得に失敗しました")

            val token = extractToken(html)
            if (token == null) {
                Log.w(TAG, "Token not found. Redirected to login?")
                return@withContext PunchResult(false, "セッションが切れています。再ログインしてください。")
            }

            val group = extractGroupName(html) ?: ""
            Log.d(TAG, "Punching: type=${type.name}, aditItem=${type.aditItem}, token=${token.take(10)}...")

            // Step2: 打刻POST
            val body = FormBody.Builder()
                .add("token",                token)
                .add("adit_item",            type.aditItem)
                .add("notice",               "")
                .add("is_yakin",             "0")
                .add("work_place",           "")
                .add("adit_groupcombo_name", group)
                .add("latitude",             "")
                .add("longitude",            "")
                .build()

            val req = Request.Builder()
                .url(ADIT_URL)
                .post(body)
                .header("Referer",             EMPLOYEE_URL)
                .header("X-Requested-With",    "XMLHttpRequest")
                .header("Origin",              BASE_URL)
                .build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            Log.d(TAG, "Punch response code=${resp.code} body=${respBody.take(300)}")

            parsePunchResponse(respBody, type, resp.code)

        } catch (e: IOException) {
            Log.e(TAG, "Punch IO error", e)
            PunchResult(false, "ネットワークエラー: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Punch error", e)
            PunchResult(false, "エラー: ${e.message}")
        }
    }

    // ── ユーザー情報 ──────────────────────────────────────────────
    suspend fun getUserInfo(): UserInfo? = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(EMPLOYEE_URL) ?: return@withContext null
            parseUserInfo(html)
        } catch (e: Exception) { null }
    }

    // ── HTML取得 ──────────────────────────────────────────────────
    private fun fetchHtml(url: String): String? {
        return try {
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) resp.body?.string() else {
                Log.w(TAG, "fetchHtml: HTTP ${resp.code} for $url")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchHtml error: $url", e)
            null
        }
    }

    // ── マイページHTMLパース ──────────────────────────────────────
    private fun parseEmployeePage(html: String): TodayInfo {
        val records = mutableListOf<ClockRecord>()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.JAPAN)

        // ──────────────────────────────────────────────────────────
        // 打刻記録の抽出（複数パターンに対応）
        //
        // Jobcanのマイページには大きく2つのパターンあり
        //  A) <td>出勤</td><td>09:00</td>  (テーブル形式)
        //  B) <span class="adit-type">出勤</span>...<span class="adit-time">09:00</span>
        // ──────────────────────────────────────────────────────────

        // パターンA: <tr>内の<td>
        val trPattern = Pattern.compile(
            """<tr[^>]*>(?:(?!</tr>).)*?""" +
            """<td[^>]*>\s*(出勤|退勤|休憩開始|休憩終了)\s*</td>""" +
            """(?:(?!</tr>).)*?""" +
            """<td[^>]*>\s*(\d{1,2}:\d{2})\s*</td>""" +
            """(?:(?!</tr>).)*?</tr>""",
            Pattern.DOTALL
        )
        var m = trPattern.matcher(html)
        while (m.find()) {
            val typeStr = m.group(1) ?: continue
            val timeStr = m.group(2) ?: continue
            parseRecord(typeStr, timeStr, timeFmt)?.let { records.add(it) }
        }

        // パターンB: spanタグ（パターンAで見つからなかった場合）
        if (records.isEmpty()) {
            val spanPattern = Pattern.compile(
                """(出勤|退勤|休憩開始|休憩終了)[^<]*</(?:span|td|div)>[^<]*(?:<[^/][^>]*>[^<]*</[^>]*>[^<]*)*?(\d{1,2}:\d{2})""",
                Pattern.DOTALL
            )
            m = spanPattern.matcher(html)
            while (m.find()) {
                val typeStr = m.group(1) ?: continue
                val timeStr = m.group(2) ?: continue
                parseRecord(typeStr, timeStr, timeFmt)?.let { records.add(it) }
            }
        }

        // パターンC: JSON embedded
        if (records.isEmpty()) {
            val jsonPattern = Pattern.compile(
                """"adit_type"\s*:\s*"(\d+)"[^}]*?"time"\s*:\s*"(\d{2}:\d{2})""""
            )
            m = jsonPattern.matcher(html)
            while (m.find()) {
                val aditType = m.group(1) ?: continue
                val timeStr  = m.group(2) ?: continue
                val typeStr = when (aditType) {
                    "2" -> "出勤"; "1" -> "退勤"
                    "3" -> "休憩開始"; "4" -> "休憩終了"
                    else -> continue
                }
                parseRecord(typeStr, timeStr, timeFmt)?.let { records.add(it) }
            }
        }

        Log.d(TAG, "Found ${records.size} records")

        // ── 現在のステータス判定 ──────────────────────────────────
        val status = detectStatus(html, records)
        Log.d(TAG, "Detected status: $status")

        // ── 時間計算 ──────────────────────────────────────────────
        val workStart  = records.firstOrNull { it.type == ClockType.CLOCK_IN  }?.time
        val workEnd    = records.firstOrNull { it.type == ClockType.CLOCK_OUT }?.time
        var workMinutes = 0
        var breakMinutes = 0

        if (workStart != null) {
            val endTime = workEnd ?: Date()
            workMinutes = ((endTime.time - workStart.time) / 60_000).toInt().coerceAtLeast(0)
        }

        var bStart: Date? = null
        for (r in records) {
            when (r.type) {
                ClockType.BREAK_START -> bStart = r.time
                ClockType.BREAK_END   -> {
                    if (bStart != null) {
                        breakMinutes += ((r.time.time - bStart!!.time) / 60_000).toInt().coerceAtLeast(0)
                        bStart = null
                    }
                }
                else -> {}
            }
        }
        if (status == ClockStatus.ON_BREAK && bStart != null) {
            breakMinutes += ((Date().time - bStart!!.time) / 60_000).toInt().coerceAtLeast(0)
        }

        return TodayInfo(
            status       = status,
            records      = records,
            workStart    = workStart,
            workEnd      = workEnd,
            workMinutes  = (workMinutes - breakMinutes).coerceAtLeast(0),
            breakMinutes = breakMinutes
        )
    }

    private fun parseRecord(typeStr: String, timeStr: String, fmt: SimpleDateFormat): ClockRecord? {
        val type = when (typeStr.trim()) {
            "出勤"   -> ClockType.CLOCK_IN
            "退勤"   -> ClockType.CLOCK_OUT
            "休憩開始" -> ClockType.BREAK_START
            "休憩終了" -> ClockType.BREAK_END
            else -> return null
        }
        return try {
            val time = fmt.parse(timeStr.trim()) ?: return null
            ClockRecord(type = type, time = time, label = typeStr.trim())
        } catch (e: Exception) { null }
    }

    private fun detectStatus(html: String, records: List<ClockRecord>): ClockStatus {
        // HTMLのクラス/テキストから直接判定
        val lower = html.lowercase()
        return when {
            lower.contains("class=\"working\"") || lower.contains("勤務中") -> ClockStatus.WORKING
            lower.contains("class=\"resting\"") || lower.contains("休憩中") -> ClockStatus.ON_BREAK
            lower.contains("class=\"retired\"") || lower.contains("退勤済") -> ClockStatus.FINISHED
            records.isEmpty() -> ClockStatus.NOT_STARTED
            else -> when (records.last().type) {
                ClockType.CLOCK_IN    -> ClockStatus.WORKING
                ClockType.CLOCK_OUT   -> ClockStatus.FINISHED
                ClockType.BREAK_START -> ClockStatus.ON_BREAK
                ClockType.BREAK_END   -> ClockStatus.WORKING
            }
        }
    }

    // ── 打刻レスポンス解析 ────────────────────────────────────────
    private fun parsePunchResponse(body: String, type: ClockType, httpCode: Int): PunchResult {
        val label = when (type) {
            ClockType.CLOCK_IN    -> "出勤しました"
            ClockType.CLOCK_OUT   -> "退勤しました"
            ClockType.BREAK_START -> "休憩を開始しました"
            ClockType.BREAK_END   -> "休憩を終了しました"
        }
        return when {
            // JSON成功
            body.contains("\"result\":1") || body.contains("result=1") ->
                PunchResult(true, label)
            // リダイレクト後のHTMLで打刻記録が含まれている場合も成功
            httpCode in 200..399 && (body.contains("打刻") || body.contains("adit")) ->
                PunchResult(true, label)
            // 空レスポンス（一部の環境でリダイレクトのみ）
            body.isEmpty() && httpCode in 200..399 ->
                PunchResult(true, label)
            body.contains("すでに") || body.contains("already") ->
                PunchResult(false, "すでに打刻済みです。")
            body.contains("ログイン") || body.contains("sign_in") ->
                PunchResult(false, "セッションが切れました。再ログインしてください。")
            body.contains("\"result\":0") -> {
                val msgPat = Pattern.compile(""""message"\s*:\s*"([^"]+)"""")
                val mm = msgPat.matcher(body)
                val errMsg = if (mm.find()) mm.group(1) else "打刻に失敗しました"
                PunchResult(false, errMsg ?: "打刻に失敗しました")
            }
            else -> {
                Log.w(TAG, "Unknown punch response: ${body.take(200)}")
                PunchResult(false, "打刻に失敗しました (HTTP $httpCode)")
            }
        }
    }

    // ── CSRFトークン抽出 ──────────────────────────────────────────
    private fun extractToken(html: String): String? {
        val patterns = listOf(
            // Jobcan モバイル
            Pattern.compile("""<input[^>]+name=["']token["'][^>]+value=["']([^"']+)["']"""),
            Pattern.compile("""<input[^>]+value=["']([^"']+)["'][^>]+name=["']token["']"""),
            // 汎用 CSRF
            Pattern.compile("""<input[^>]+name=["']authenticity_token["'][^>]+value=["']([^"']+)["']"""),
            Pattern.compile("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']"""),
        )
        for (p in patterns) {
            val mm = p.matcher(html)
            if (mm.find()) {
                val t = mm.group(1)
                if (!t.isNullOrBlank()) {
                    Log.d(TAG, "Token found (len=${t.length})")
                    return t
                }
            }
        }
        Log.w(TAG, "No token found in HTML (len=${html.length})")
        return null
    }

    private fun extractGroupName(html: String): String? {
        val p = Pattern.compile("""name=["']adit_groupcombo_name["'][^>]+value=["']([^"']*)["']""")
        val m = p.matcher(html)
        return if (m.find()) m.group(1) else ""
    }

    private fun parseUserInfo(html: String): UserInfo {
        val patterns = listOf(
            Pattern.compile("""class=["'][^"']*staff[_-]?name[^"']*["'][^>]*>\s*([^<\n]{1,30})\s*<"""),
            Pattern.compile("""class=["'][^"']*user[_-]?name[^"']*["'][^>]*>\s*([^<\n]{1,30})\s*<"""),
            Pattern.compile("""<span[^>]*>\s*([^\s<][^<]{0,20})\s*(さん|様)\s*</span>"""),
        )
        for (p in patterns) {
            val m = p.matcher(html)
            if (m.find()) {
                val name = m.group(1)?.trim() ?: continue
                if (name.isNotEmpty()) return UserInfo(name = name)
            }
        }
        return UserInfo(name = "ユーザー")
    }
}

// ── WebViewと同じCookieをOkHttpで共有 ────────────────────────────
class WebViewCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val mgr = CookieManager.getInstance()
        cookies.forEach { mgr.setCookie(url.toString(), it.toString()) }
        mgr.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieStr = CookieManager.getInstance().getCookie(url.toString())
            ?: return emptyList()
        return cookieStr.split(";").mapNotNull { pair ->
            val kv = pair.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                try {
                    Cookie.Builder()
                        .name(kv[0].trim())
                        .value(kv[1].trim())
                        .domain(url.host)
                        .build()
                } catch (e: Exception) { null }
            } else null
        }
    }
}
