package com.example.robocallguard

/**
 * Action applied to an incoming call.
 * ALLOW:      ring normally
 * REJECT:     end the call immediately
 * VOICEMAIL:  send to voicemail without ringing
 * SILENCE:    ring silently (API 31+)
 */
enum class Action { ALLOW, REJECT, VOICEMAIL, SILENCE }

/** Outcome of screening one number. */
data class Verdict(
    val action: Action,
    val reason: String,
    val source: String // contacts | rules | posture | lookup
)

object Matchers {
    fun digits(number: String?): String = number?.filter { it.isDigit() } ?: ""
    fun last10(digits: String): String = digits.takeLast(10)

    fun matchesExact(digits: String, entries: Set<String>): Boolean =
        entries.any { last10(digits) == last10(it.filter { c -> c.isDigit() }) }

    fun matchesPrefix(digits: String, prefixes: List<String>): String? =
        prefixes.firstOrNull { digits.startsWith(it) }

    fun matchesPattern(digits: String, raw: String?, patterns: List<String>): String? =
        patterns.firstOrNull { p ->
            runCatching {
                Regex(p).matches(digits) || Regex(p).matches(raw.orEmpty())
            }.getOrDefault(false)
        }
}
