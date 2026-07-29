package jp.jobcan.app.model

import java.util.Date

// ── 打刻種別 ──────────────────────────────────────────────────
enum class ClockType(val label: String, val aditItem: String) {
    CLOCK_IN("出勤", "2"),
    CLOCK_OUT("退勤", "1"),
    BREAK_START("休憩開始", "3"),
    BREAK_END("休憩終了", "4")
}

// ── 勤務状態 ──────────────────────────────────────────────────
enum class ClockStatus {
    NOT_STARTED,  // 未出勤
    WORKING,      // 勤務中
    ON_BREAK,     // 休憩中
    FINISHED,     // 退勤済
    ERROR         // エラー
}

// ── 打刻記録 ──────────────────────────────────────────────────
data class ClockRecord(
    val type: ClockType,
    val time: Date,
    val label: String
)

// ── 今日の情報 ────────────────────────────────────────────────
data class TodayInfo(
    val status: ClockStatus = ClockStatus.NOT_STARTED,
    val records: List<ClockRecord> = emptyList(),
    val workStart: Date? = null,
    val workEnd: Date? = null,
    val workMinutes: Int = 0,    // 実労働時間（分）
    val breakMinutes: Int = 0,   // 休憩時間（分）
    val errorMessage: String? = null
)

// ── 打刻結果 ──────────────────────────────────────────────────
data class PunchResult(
    val success: Boolean,
    val message: String = "",
    val newStatus: ClockStatus? = null
)

// ── ログイン結果 ──────────────────────────────────────────────
data class LoginResult(
    val success: Boolean,
    val errorMessage: String? = null
)

// ── ユーザー情報 ──────────────────────────────────────────────
data class UserInfo(
    val name: String = "ユーザー",
    val staffCode: String = "",
    val groupName: String = ""
)
