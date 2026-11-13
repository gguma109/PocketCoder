package com.pocketcoder.protocol

/** Two-set Korean composition for the editable send buffer, including compound vowels/finals. */
object Hangul {
    private const val leads = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val vowels = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val tails = "\u0000ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val vowelPairs = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')
    private val tailPairs = mapOf("ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ')
    val keys = "QWERTYUIOPASDFGHJKLZXCVBNM".zip("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ").toMap()
    private val shifted = mapOf('Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ', 'O' to 'ㅒ', 'P' to 'ㅖ')
    fun key(letter: Char, shift: Boolean) = if (shift) shifted[letter] ?: keys[letter] else keys[letter]
    private fun syllable(l: Int, v: Int, t: Int) = (0xAC00 + (l * 21 + v) * 28 + t).toChar()
    fun append(text: String, key: Char): String {
        val last = text.lastOrNull() ?: return "$key"
        val prefix = text.dropLast(1)
        val v = vowels.indexOf(key)
        if (last in '\uAC00'..'\uD7A3') {
            val n = last.code - 0xAC00; val l = n / 588; val oldV = n / 28 % 21; val t = n % 28
            if (v >= 0) {
                if (t == 0) {
                    val combined = vowelPairs["${vowels[oldV]}$key"]
                    if (combined != null) return prefix + syllable(l, vowels.indexOf(combined), 0)
                } else {
                    val split = tailPairs.entries.firstOrNull { it.value == tails[t] }?.key
                    val moving = split?.last() ?: tails[t]
                    val nextLead = leads.indexOf(moving)
                    if (nextLead >= 0) return prefix + syllable(l, oldV, split?.first()?.let { tails.indexOf(it) } ?: 0) + syllable(nextLead, v, 0)
                }
            } else {
                val nextTail = tails.indexOf(key)
                if (t == 0 && nextTail > 0) return prefix + syllable(l, oldV, nextTail)
                val combined = tailPairs["${tails[t]}$key"]
                if (combined != null) return prefix + syllable(l, oldV, tails.indexOf(combined))
            }
        } else if (v >= 0 && leads.indexOf(last) >= 0) {
            return prefix + syllable(leads.indexOf(last), v, 0)
        } else {
            vowelPairs["$last$key"]?.let { return prefix + it }
        }
        return text + key
    }
    fun backspace(text: String): String {
        val last = text.lastOrNull() ?: return text
        val prefix = text.dropLast(1)
        if (last in '\uAC00'..'\uD7A3') {
            val n = last.code - 0xAC00; val l = n / 588; val v = n / 28 % 21; val t = n % 28
            if (t > 0) {
                val left = tailPairs.entries.firstOrNull { it.value == tails[t] }?.key?.first()
                return prefix + syllable(l, v, left?.let { tails.indexOf(it) } ?: 0)
            }
            val left = vowelPairs.entries.firstOrNull { it.value == vowels[v] }?.key?.first()
            return prefix + if (left != null) syllable(l, vowels.indexOf(left), 0) else leads[l]
        }
        return text.dropLast(Character.charCount(text.codePointBefore(text.length)))
    }
}
