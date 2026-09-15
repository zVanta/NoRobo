package com.example.robocallguard

/**
 * Extensible rule engine. Returns a verdict for each incoming number.
 * Tune these lists to your situation (edit and rebuild).
 */
object CallRules {

    // Numbers that always ring through (family, key vendors, etc.).
    // Use E.164 ("+15551234567") or any digits; last-10 comparison is used.
    private val allowlist = setOf(
        // "+15551234567",
    )

    // Exact numbers to always reject.
    private val blocklist = setOf(
        // "+18005550123",
    )

    // Digit prefixes to reject (area codes / country codes / toll-free).
    private val blockedPrefixes = listOf(
        // "1800", "1888", "1877", "1866", "1855", "1844", "1833", // US toll-free
    )

    // Regex rules matched against the raw digit string.
    private val blockedPatterns = listOf<Regex>(
        // Regex("^\\+?1?800\\d{7}$"),
    )

    fun verdict(number: String?): Verdict {
        if (number.isNullOrBlank()) return Verdict.UNKNOWN
        val digits = number.filter { it.isDigit() }
        val last10 = digits.takeLast(10)

        if (allowlist.any { last10 == it.takeLast(10) }) return Verdict.ALLOW
        if (blocklist.any { last10 == it.takeLast(10) }) return Verdict.BLOCK
        if (blockedPrefixes.any { digits.startsWith(it) }) return Verdict.BLOCK
        if (blockedPatterns.any { it.matches(digits) || it.matches(number) }) return Verdict.BLOCK

        return Verdict.UNKNOWN
    }

    enum class Verdict { ALLOW, BLOCK, UNKNOWN }
}
