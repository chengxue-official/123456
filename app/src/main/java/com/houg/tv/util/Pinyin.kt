package com.houg.tv.util

import java.nio.charset.Charset

/**
 * 汉字 → 拼音首字母（GB2312 区位码映射，覆盖常用 6763 汉字）。
 * 用于「首字母搜索」：把剧名转成拼音首字母缩写，如「重生之都市仙尊」→ CSZD...
 * 非汉字字符（字母/数字）原样保留并转大写。
 */
object Pinyin {

    private val GBK: Charset = Charset.forName("GBK")

    /**
     * 把字符串转成拼音首字母序列。
     * - 汉字 → 拼音首字母（A-Z）
     * - 英文字母/数字 → 原样大写
     * - 其它字符（空格/标点/·等）→ 跳过
     *
     * @param input 原始字符串
     * @param keepNonHan 是否保留非汉字字母数字字符（true 时标点也跳过）
     * @return 大写拼音首字母串，例如 "重生之都市仙尊" → "CSZDCSXZ"
     */
    fun firstLetters(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val c = firstLetter(ch)
            if (c != '\u0000') sb.append(c)
        }
        return sb.toString()
    }

    /**
     * 多音字的「其它可能首字母」。键为汉字，值为该字可能的全部首字母。
     * 只收录两个读音首字母不同的字——同首字母的多音字对本功能无影响。
     * 剧名里「重生/长夜/行者/藏娇/曾记/单挑/解语/仇敌…」很常见，
     * 只按 GB2312 主读音索引会让用户按另一读音搜不到（如「重生」按 CS 搜不到）。
     */
    private val POLYPHONES: Map<Char, String> = mapOf(
        '重' to "CZ", '长' to "CZ", '行' to "XH", '藏' to "CZ", '曾' to "CZ",
        '单' to "DS", '解' to "JX", '仇' to "QC", '区' to "QO", '查' to "CZ",
        '朝' to "CZ", '传' to "CZ", '参' to "CS", '便' to "BP", '泊' to "BP",
        '给' to "GJ", '奇' to "QJ", '宿' to "SX", '提' to "TD", '调' to "TD",
        '折' to "ZS", '省' to "SX", '弄' to "NL", '强' to "QJ", '校' to "XJ",
        '叶' to "YX", '识' to "SZ", '期' to "QJ", '石' to "SD",
    )

    /**
     * 单个字符可能的全部首字母（多音字返回多个，主读音在前）；
     * 无法转换（标点/空格等）返回空串。
     */
    fun firstLetterOptions(ch: Char): String {
        val primary = firstLetter(ch)
        if (primary == '\u0000') return ""
        val extra = POLYPHONES[ch] ?: return primary.toString()
        if (extra.indexOf(primary) < 0) return primary + extra
        val sb = StringBuilder(extra.length + 1)
        sb.append(primary)
        for (c in extra) if (c != primary) sb.append(c)
        return sb.toString()
    }

    /**
     * 剧名是否以查询串的拼音首字母开头（多音字任一读音命中即可）。
     * 例：query="CS" 命中「重生之都市仙尊」（chong sheng），
     *     query="ZS" 同样命中（zhong sheng）。
     */
    fun matchesPrefix(title: String, query: String): Boolean {
        if (query.isEmpty()) return false
        var i = 0
        for (ch in title) {
            val options = firstLetterOptions(ch)
            if (options.isEmpty()) continue
            if (i >= query.length) return true
            if (options.indexOf(query[i]) < 0) return false
            i++
        }
        return i >= query.length
    }

    /** 单个字符 → 拼音首字母；无法转换（标点/空格等）返回 '\u0000'。 */
    fun firstLetter(ch: Char): Char {
        when {
            ch in 'a'..'z' -> return ch.uppercaseChar()
            ch in 'A'..'Z' -> return ch
            ch in '0'..'9' -> return ch
        }
        val bytes = try {
            ch.toString().toByteArray(GBK)
        } catch (e: Exception) {
            return '\u0000'
        }
        if (bytes.size != 2) return '\u0000'
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val code = (b0 shl 8) or b1
        // 按 GB2312 区位码区间映射到首字母
        return when {
            code in 0xB0A1..0xB0C4 -> 'A'
            code in 0xB0C5..0xB2C0 -> 'B'
            code in 0xB2C1..0xB4ED -> 'C'
            code in 0xB4EE..0xB6E9 -> 'D'
            code in 0xB6EA..0xB7A1 -> 'E'
            code in 0xB7A2..0xB8C0 -> 'F'
            code in 0xB8C1..0xB9FD -> 'G'
            code in 0xB9FE..0xBBF6 -> 'H'
            code in 0xBBF7..0xBFA5 -> 'J'
            code in 0xBFA6..0xC0AB -> 'K'
            code in 0xC0AC..0xC2E7 -> 'L'
            code in 0xC2E8..0xC4C2 -> 'M'
            code in 0xC4C3..0xC5B5 -> 'N'
            code in 0xC5B6..0xC5BD -> 'O'
            code in 0xC5BE..0xC6D9 -> 'P'
            code in 0xC6DA..0xC8BA -> 'Q'
            code in 0xC8BB..0xC8F5 -> 'R'
            code in 0xC8F6..0xCBF9 -> 'S'
            code in 0xCBFA..0xCDD9 -> 'T'
            code in 0xCDDA..0xCEF3 -> 'W'
            // X 的上界是 D1B8（不是 D188）：D189..D1B8 仍是 X 声母字
            // （迅/寻/勋/巡/训…都在这一段），写成 D188 会让这些字丢字母，
            // 导致「迅」开头的剧首字母串少一个 X、按首字母搜不到。
            code in 0xCEF4..0xD1B8 -> 'X'
            code in 0xD1B9..0xD4D0 -> 'Y'
            code in 0xD4D1..0xD7F9 -> 'Z'
            else -> '\u0000'
        }
    }
}
