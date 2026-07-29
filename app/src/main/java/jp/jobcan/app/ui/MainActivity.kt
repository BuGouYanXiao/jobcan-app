package jp.jobcan.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import jp.jobcan.app.R
import jp.jobcan.app.databinding.ActivityMainBinding
import jp.jobcan.app.model.ClockStatus
import jp.jobcan.app.model.ClockType
import jp.jobcan.app.model.TodayInfo
import jp.jobcan.app.service.JobcanService
import jp.jobcan.app.service.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var service: JobcanService
    private lateinit var sessionManager: SessionManager

    private var currentStatus = ClockStatus.NOT_STARTED
    private var isPunching = false

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.JAPAN)
    private val dateFmt = SimpleDateFormat("yyyy年M月d日 (EEE)", Locale("ja"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        service = JobcanService(this)
        sessionManager = SessionManager(this)

        setupUI()
        startClock()
        loadTodayInfo()
    }

    private fun setupUI() {
        binding.tvUserName.text = sessionManager.getUserName()
        updateGreeting()

        // ★ 丸打刻ボタン ★
        binding.btnClock.setOnClickListener {
            if (!isPunching) onClockButtonPressed()
        }

        binding.btnBreakStart.setOnClickListener {
            if (!isPunching) confirmPunch(ClockType.BREAK_START, "休憩開始の打刻をしますか？")
        }
        binding.btnBreakEnd.setOnClickListener {
            if (!isPunching) confirmPunch(ClockType.BREAK_END, "休憩終了の打刻をしますか？")
        }

        binding.swipeRefresh.setOnRefreshListener { loadTodayInfo() }
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.accent_blue)
        )

        binding.btnLogout.setOnClickListener { confirmLogout() }
    }

    // ── リアルタイム時計 ──────────────────────────────────────────
    private fun startClock() {
        lifecycleScope.launch {
            while (isActive) {
                val now = Date()
                binding.tvClock.text = timeFmt.format(now)
                binding.tvDate.text  = dateFmt.format(now)
                updateGreeting()
                delay(1000)
            }
        }
    }

    private fun updateGreeting() {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        binding.tvGreeting.text = when {
            h < 12 -> "おはようございます"
            h < 17 -> "こんにちは"
            else   -> "お疲れ様です"
        }
    }

    // ── 今日の情報ロード（実際のJobcanから取得）───────────────────
    private fun loadTodayInfo() {
        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            binding.statusLoadingBar.visibility = View.VISIBLE

            val info = service.getTodayInfo()

            binding.swipeRefresh.isRefreshing = false
            binding.statusLoadingBar.visibility = View.GONE

            updateUI(info)
        }
    }

    // ── UI全体更新 ─────────────────────────────────────────────────
    private fun updateUI(info: TodayInfo) {
        currentStatus = info.status
        updateStatusBadge(info.status)
        updateClockButton(info.status)

        // 勤務時間
        if (info.workMinutes > 0) {
            binding.tvWorkTime.text = "%dh %02dm".format(info.workMinutes / 60, info.workMinutes % 60)
        } else {
            binding.tvWorkTime.text = "--h --m"
        }
        binding.tvWorkStart.text = info.workStart?.let { "開始: ${timeFmt.format(it)}" } ?: "開始: --:--"

        // 休憩時間
        binding.tvBreakTime.text = if (info.breakMinutes > 0) "${info.breakMinutes}分" else "--分"

        // 休憩ボタン表示切替
        binding.btnBreakStart.visibility = if (info.status == ClockStatus.WORKING) View.VISIBLE else View.GONE
        binding.btnBreakEnd.visibility   = if (info.status == ClockStatus.ON_BREAK)  View.VISIBLE else View.GONE

        // 打刻記録
        updateRecordList(info)

        // エラー表示
        if (info.status == ClockStatus.ERROR) {
            binding.tvError.text = info.errorMessage ?: "エラーが発生しました"
            binding.tvError.visibility = View.VISIBLE
        } else {
            binding.tvError.visibility = View.GONE
        }
    }

    // ── ステータスバッジ更新 ──────────────────────────────────────
    private fun updateStatusBadge(status: ClockStatus) {
        val (text, colorRes) = when (status) {
            ClockStatus.NOT_STARTED -> "● 未出勤" to R.color.status_not_started
            ClockStatus.WORKING     -> "● 勤務中" to R.color.status_working
            ClockStatus.ON_BREAK    -> "● 休憩中" to R.color.status_break
            ClockStatus.FINISHED    -> "● 退勤済" to R.color.status_finished
            ClockStatus.ERROR       -> "● エラー" to R.color.status_error
        }
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.cardStatus.strokeColor = ContextCompat.getColor(this, colorRes)
    }

    // ── 丸打刻ボタン更新 ──────────────────────────────────────────
    private fun updateClockButton(status: ClockStatus) {
        val (icon, label, bgRes, colorRes) = when (status) {
            ClockStatus.NOT_STARTED -> Quad("▶", "出勤",   R.drawable.btn_clock_bg,      R.color.btn_clock_in)
            ClockStatus.WORKING     -> Quad("■", "退勤",   R.drawable.btn_clock_out_bg,  R.color.btn_clock_out)
            ClockStatus.ON_BREAK    -> Quad("▶", "休憩終了", R.drawable.btn_break_end_bg, R.color.btn_break_end)
            ClockStatus.FINISHED    -> Quad("✓", "退勤済", R.drawable.btn_finished_bg,   R.color.btn_finished)
            ClockStatus.ERROR       -> Quad("↺", "再読込", R.drawable.btn_clock_bg,      R.color.btn_clock_in)
        }
        binding.tvClockBtnIcon.text  = icon
        binding.tvClockBtnLabel.text = label
        binding.btnClock.background  = ContextCompat.getDrawable(this, bgRes)

        // グロー色も変更
        val glowColor = ContextCompat.getColor(this, colorRes)
        binding.viewGlow.setBackgroundResource(bgRes)
        binding.viewGlow.alpha = 0.2f

        binding.btnClock.alpha     = if (status == ClockStatus.FINISHED) 0.5f else 1.0f
        binding.btnClock.isEnabled = status != ClockStatus.FINISHED
    }

    // ── 打刻記録リスト更新 ────────────────────────────────────────
    private fun updateRecordList(info: TodayInfo) {
        binding.llRecords.removeAllViews()

        if (info.records.isEmpty()) {
            val tv = TextView(this).apply {
                text = "本日の打刻記録はありません"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                textSize = 13f
                setPadding(0, 24, 0, 8)
            }
            binding.llRecords.addView(tv)
            return
        }

        info.records.forEach { record ->
            val row = layoutInflater.inflate(R.layout.item_record, binding.llRecords, false)

            row.findViewById<TextView>(R.id.tvRecordType).text = record.label
            row.findViewById<TextView>(R.id.tvRecordTime).text =
                SimpleDateFormat("HH:mm", Locale.JAPAN).format(record.time)

            val dotColorRes = when (record.type) {
                ClockType.CLOCK_IN    -> R.color.accent_blue
                ClockType.CLOCK_OUT   -> R.color.accent_red
                ClockType.BREAK_START -> R.color.accent_orange
                ClockType.BREAK_END   -> R.color.accent_cyan
            }
            val dot = row.findViewById<View>(R.id.viewDot)
            dot.background?.setTint(ContextCompat.getColor(this, dotColorRes))

            binding.llRecords.addView(row)

            // 区切り線（最後以外）
            if (record != info.records.last()) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.setMargins(20, 0, 0, 0) }
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.border_subtle))
                }
                binding.llRecords.addView(divider)
            }
        }
    }

    // ── 打刻ボタン押下 ────────────────────────────────────────────
    private fun onClockButtonPressed() {
        if (currentStatus == ClockStatus.ERROR) {
            loadTodayInfo()
            return
        }
        val (msg, type) = when (currentStatus) {
            ClockStatus.NOT_STARTED -> "出勤打刻をしますか？"   to ClockType.CLOCK_IN
            ClockStatus.WORKING     -> "退勤打刻をしますか？"   to ClockType.CLOCK_OUT
            ClockStatus.ON_BREAK    -> "休憩終了の打刻をしますか？" to ClockType.BREAK_END
            else -> return
        }
        confirmPunch(type, msg)
    }

    private fun confirmPunch(type: ClockType, message: String) {
        AlertDialog.Builder(this)
            .setTitle("打刻確認")
            .setMessage(message)
            .setPositiveButton("はい") { _, _ -> executePunch(type) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ── 実際の打刻実行（Jobcanへ送信して結果確認）─────────────────
    private fun executePunch(clockType: ClockType) {
        if (isPunching) return
        isPunching = true

        // UI: ローディング開始
        binding.btnClock.isEnabled       = false
        binding.btnClock.alpha           = 0.6f
        binding.tvClockBtnIcon.text      = ""
        binding.tvClockBtnLabel.text     = "送信中"
        binding.punchingSpinner.visibility = View.VISIBLE
        binding.statusLoadingBar.visibility = View.VISIBLE

        // 押したアニメーション
        val sx = ObjectAnimator.ofFloat(binding.btnClock, "scaleX", 1f, 0.92f, 1f)
        val sy = ObjectAnimator.ofFloat(binding.btnClock, "scaleY", 1f, 0.92f, 1f)
        AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 250
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        lifecycleScope.launch {
            // ★ Jobcanに実際にPOSTして結果を受け取る ★
            val result = service.punch(clockType)

            binding.statusLoadingBar.visibility = View.GONE
            binding.punchingSpinner.visibility  = View.GONE
            isPunching = false

            if (result.success) {
                // 成功アニメーション（拡大フラッシュ）
                val sx2 = ObjectAnimator.ofFloat(binding.btnClock, "scaleX", 1f, 1.12f, 1f)
                val sy2 = ObjectAnimator.ofFloat(binding.btnClock, "scaleY", 1f, 1.12f, 1f)
                AnimatorSet().apply {
                    playTogether(sx2, sy2)
                    duration = 350
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }

                Toast.makeText(this@MainActivity, "✓ ${result.message}", Toast.LENGTH_LONG).show()

                // ★ 打刻後に実際の状態をJobcanから再取得 ★
                delay(800)
                loadTodayInfo()

            } else {
                // 失敗 → ボタンを元の状態に戻す
                binding.btnClock.isEnabled = true
                binding.btnClock.alpha     = 1f
                updateClockButton(currentStatus)

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("打刻エラー")
                    .setMessage(result.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ── ログアウト ────────────────────────────────────────────────
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("ログアウト")
            .setMessage("ログアウトしますか？")
            .setPositiveButton("はい") { _, _ ->
                sessionManager.clear()
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ── 4値タプルのユーティリティ ─────────────────────────────────
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
