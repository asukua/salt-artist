package dev.saltartist.am

import java.text.Normalizer
import java.util.Locale

/** A verified or curated set of names that may refer to one artist. */
internal data class ArtistAliasGroup(
    val id: String?,
    val aliases: List<String>,
) {
    val names: Set<String> by lazy { aliases.map(::artistIdentityKey).toSet() }
    val matchKeys: Set<String> by lazy { aliases.flatMap(::artistMatchKeys).toSet() }
}

/**
 * Cross-script names are deliberately curated instead of inferred from search
 * ranking. Groups with an Apple Music ID are checked with lookup before use;
 * groups without an ID improve candidate matching only.
 */
internal object ArtistAliasRegistry {
    private fun group(id: String?, vararg aliases: String) = ArtistAliasGroup(id, aliases.toList())

    val groups: List<ArtistAliasGroup> = listOf(
        group("307078957", "初音ミク", "初音 ミク", "初音未来", "初音未來", "Hatsune Miku", "HatsuneMiku"),
        group("300117743", "周杰伦", "周杰倫", "Jay Chou", "Zhou Jielun", "Zhou Jie Lun", "Zhoujielun"),
        group("137938148", "陈奕迅", "陳奕迅", "Eason Chan", "Chen Yixun", "Chen Yi Xun", "Chenyixun"),
        group("216635866", "林俊杰", "林俊傑", "JJ Lin", "Lin Jun Jie", "Lin Junjie", "Linjunjie"),
        group("41760704", "王菲", "Faye Wong", "Wang Fei", "Wangfei"),
        group("425208570", "邓紫棋", "鄧紫棋", "G.E.M.", "G E M", "GEM", "Deng Ziqi", "Deng Zi Qi", "Dengziqi"),
        group("567072", "Gorillaz"),

        // Common Chinese names that are frequently tagged with pinyin or an
        // English stage name. Their Apple IDs are resolved from search results.
        group(null, "张学友", "張學友", "Jacky Cheung", "Zhang Xueyou", "Zhang Xue You"),
        group(null, "刘德华", "劉德華", "Andy Lau", "Liu Dehua", "Liu De Hua"),
        group(null, "郭富城", "Aaron Kwok", "Guo Fucheng", "Guo Fu Cheng"),
        group(null, "黎明", "Leon Lai", "Li Ming"),
        group(null, "蔡依林", "Jolin Tsai", "Cai Yilin", "Cai Yi Lin"),
        group(null, "五月天", "Mayday"),
        group(null, "李荣浩", "李榮浩", "Li Ronghao", "Li Rong Hao"),
        group(null, "薛之谦", "薛之謙", "Joker Xue", "Xue Zhiqian", "Xue Zhi Qian"),
        group(null, "毛不易", "Mao Buyi", "Mao Bu Yi"),
        group(null, "华晨宇", "華晨宇", "Hua Chenyu", "Hua Chen Yu"),
        group(null, "周深", "Zhou Shen"),
        group(null, "许嵩", "許嵩", "Xu Song"),
        group(null, "张杰", "張杰", "Jason Zhang", "Zhang Jie"),
        group(null, "王力宏", "王力宏", "Wang Leehom", "Wang Lihong"),
        group(null, "陶喆", "陶喆", "David Tao", "Tao Zhe"),
        group(null, "林宥嘉", "Yoga Lin", "Lin Youjia", "Lin You Jia"),
        group(null, "方大同", "Khalil Fong", "Fang Datong", "Fang Da Tong"),
        group(null, "张韶涵", "張韶涵", "Angela Zhang", "Zhang Shaohan", "Zhang Shao Han"),
        group(null, "李宇春", "Chris Lee", "Li Yuchun", "Li Yu Chun"),
        group(null, "蔡健雅", "Tanya Chua", "Cai Jianya", "Cai Jian Ya"),
        group(null, "罗志祥", "羅志祥", "Show Lo", "Luo Zhixiang", "Luo Zhi Xiang"),
        group(null, "杨丞琳", "楊丞琳", "Rainie Yang", "Yang Chenglin", "Yang Cheng Lin"),
        group(null, "孙燕姿", "孫燕姿", "Stefanie Sun", "Sun Yanzi", "Sun Yan Zi"),
        group(null, "莫文蔚", "Karen Mok", "Mo Wenwei", "Mo Wen Wei"),
        group(null, "张靓颖", "張靚穎", "Jane Zhang", "Zhang Liangying", "Zhang Liang Ying"),
        group(null, "张惠妹", "張惠妹", "A-Mei", "A Mei", "Zhang Huimei", "Zhang Hui Mei"),
        group(null, "郑秀文", "鄭秀文", "Sammi Cheng", "Zheng Xiuwen", "Zheng Xiu Wen"),
        group(null, "汪峰", "Wang Feng"),
        group(null, "许巍", "許巍", "Xu Wei"),
        group(null, "伍佰", "Wu Bai"),
        group(null, "赵雷", "趙雷", "Zhao Lei"),
        group(null, "陈粒", "陳粒", "Chen Li"),
        group(null, "朴树", "朴樹", "Pu Shu"),
        group(null, "苏打绿", "蘇打綠", "Sodagreen", "Soda Green"),

        // Japanese tags often switch between kanji/kana and the artist's
        // preferred romaji spelling. Kana-only names also work generically.
        group(null, "宇多田ヒカル", "宇多田光", "Hikaru Utada", "Utada Hikaru"),
        group(null, "浜崎あゆみ", "濱崎步", "Ayumi Hamasaki"),
        group(null, "安室奈美恵", "安室奈美惠", "Namie Amuro"),
        group(null, "米津玄師", "Kenshi Yonezu"),
        group(null, "藤井風", "Fujii Kaze"),
        group(null, "あいみょん", "Aimyon"),
        group(null, "西野カナ", "Kana Nishino"),
        group(null, "星野源", "Gen Hoshino"),
        group(null, "菅田将暉", "Masaki Suda"),
        group(null, "中島美嘉", "Mika Nakashima"),
        group(null, "倖田來未", "Koda Kumi"),
        group(null, "きゃりーぱみゅぱみゅ", "Kyary Pamyu Pamyu", "Kyarypamyupamyu"),
        group(null, "Official髭男dism", "Official HIGE DANdism", "Official Hige Dandism"),
        group(null, "ワン・オク・ロック", "ONE OK ROCK", "One Ok Rock"),
        group(null, "ラッドウィンプス", "RADWIMPS"),
        group(null, "パフューム", "Perfume"),
        group(null, "ミスターチルドレン", "Mr.Children"),
        group(null, "嵐", "Arashi"),
        group(null, "スマップ", "SMAP"),
        group(null, "ヨアソビ", "YOASOBI"),
    )

    fun groupsFor(name: String): List<ArtistAliasGroup> {
        val keys = artistMatchKeys(name)
        if (keys.isEmpty()) return emptyList()
        return groups.filter { group -> group.matchKeys.any(keys::contains) }
    }
}

/**
 * Returns stable comparison keys. The first key is the old strict identity
 * key. Additional keys are limited to kana romanization, so punctuation and
 * collaboration names do not become aliases accidentally.
 */
internal fun artistMatchKeys(name: String): Set<String> {
    val exact = artistIdentityKey(name)
    if (exact.isEmpty()) return emptySet()
    val keys = linkedSetOf(exact)
    kanaRomanKey(name)?.takeIf { it.isNotEmpty() && it != exact }?.let(keys::add)
    return keys
}

internal fun artistNamesMatch(left: String, right: String, aliasesEnabled: Boolean = true): Boolean {
    if (artistIdentityKey(left) == artistIdentityKey(right)) return true
    if (!aliasesEnabled) return false
    if (artistMatchKeys(left).intersect(artistMatchKeys(right)).isNotEmpty()) return true
    val leftGroups = ArtistAliasRegistry.groupsFor(left)
    val rightGroups = ArtistAliasRegistry.groupsFor(right)
    return leftGroups.any { it in rightGroups }
}

/** Search the original spelling first, then a small number of known variants. */
internal fun artistSearchTerms(name: String, aliasesEnabled: Boolean): List<String> {
    val base = name.trim()
    if (!aliasesEnabled || base.isEmpty()) return listOf(base)
    val groups = ArtistAliasRegistry.groupsFor(base)
    val aliases = groups.flatMap { it.aliases }
    val transliterations = artistMatchKeys(base).drop(1)
    return (listOf(base) + aliases + transliterations)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(::artistIdentityKey)
        .take(4)
}

private fun kanaRomanKey(value: String): String? {
    val text = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    if (!text.any(::isKana)) return null
    val output = StringBuilder()
    var index = 0
    var geminate = false
    while (index < text.length) {
        val current = toKatakana(text[index])
        when {
            current == 'ッ' -> {
                geminate = true
                index++
            }
            current == 'ー' -> index++
            else -> {
                val pair = if (index + 1 < text.length) {
                    val candidate = current.toString() + toKatakana(text[index + 1])
                    KANA_COMBINATIONS[candidate]?.let { candidate to it }
                } else null
                val token = pair?.second ?: KANA_BASE[current]
                if (token != null) {
                    output.append(if (geminate) doubleInitial(token) else token)
                    geminate = false
                    index += pair?.first?.length ?: 1
                } else {
                    if (geminate) geminate = false
                    if (!current.isWhitespace() && !isInvisible(current)) output.append(current.lowercase(Locale.ROOT))
                    index++
                }
            }
        }
    }
    return artistIdentityKey(output.toString()).takeIf { it.isNotEmpty() }
}

private fun doubleInitial(token: String): String = when {
    token.startsWith("ch") -> "t$token"
    token.firstOrNull()?.let { it in "aeiou" } == true -> token
    else -> token.firstOrNull()?.let { "$it$token" } ?: token
}

private fun toKatakana(char: Char): Char = when {
    char in '\u3041'..'\u3096' -> (char.code + 0x60).toChar()
    else -> char
}

private fun isKana(char: Char): Boolean = char in '\u3040'..'\u30ff' || char in '\u31f0'..'\u31ff'

private fun isInvisible(char: Char): Boolean = char == '\u200b' || char == '\ufeff' || char == '\u2060'

private val KANA_BASE: Map<Char, String> = mapOf(
    'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
    'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
    'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
    'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
    'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
    'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
    'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
    'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
    'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
    'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
    'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
    'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
    'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
    'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
    'ワ' to "wa", 'ヰ' to "wi", 'ヱ' to "we", 'ヲ' to "o", 'ン' to "n",
    'ヴ' to "vu", 'ヵ' to "ka", 'ヶ' to "ke", 'ゥ' to "u", 'ァ' to "a",
    'ィ' to "i", 'ェ' to "e", 'ォ' to "o", 'ヮ' to "wa",
)

private val KANA_COMBINATIONS: Map<String, String> = mapOf(
    "イェ" to "ye", "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo",
    "キェ" to "kye", "ギェ" to "gye", "シェ" to "she", "ジェ" to "je",
    "スィ" to "si", "ズィ" to "zi", "チェ" to "che", "ティ" to "ti",
    "ディ" to "di", "トゥ" to "tu", "ドゥ" to "du", "ニェ" to "nye",
    "ヒェ" to "hye", "ビェ" to "bye", "ピェ" to "pye", "ファ" to "fa",
    "フィ" to "fi", "フェ" to "fe", "フォ" to "fo", "フュ" to "fyu",
    "ミェ" to "mye", "リェ" to "rye", "ヴァ" to "va", "ヴィ" to "vi",
    "ヴェ" to "ve", "ヴォ" to "vo", "ヴュ" to "vyu", "ツァ" to "tsa",
    "ツィ" to "tsi", "ツェ" to "tse", "ツォ" to "tso", "ツュ" to "tsyu",
    "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo", "ギャ" to "gya",
    "ギュ" to "gyu", "ギョ" to "gyo", "シャ" to "sha", "シュ" to "shu",
    "ショ" to "sho", "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
    "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "ニャ" to "nya",
    "ニュ" to "nyu", "ニョ" to "nyo", "ヒャ" to "hya", "ヒュ" to "hyu",
    "ヒョ" to "hyo", "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
    "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo", "ミャ" to "mya",
    "ミュ" to "myu", "ミョ" to "myo", "リャ" to "rya", "リュ" to "ryu",
    "リョ" to "ryo", "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
)
