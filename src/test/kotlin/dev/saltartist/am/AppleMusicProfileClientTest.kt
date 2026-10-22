package dev.saltartist.am

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppleMusicProfileClientTest {
    @Test
    fun readsOnlyTheMatchingArtistsLocalizedFacts() {
        val json = """{"data":[{"id":"999","type":"artists","attributes":{"origin":"其他艺人地址"}},{"id":"42","type":"artists","attributes":{"url":"https://music.apple.com/cn/artist/test/42","bornOrFormed":"2000年6月3日","isGroup":false,"origin":"中国台湾","genreNames":["国语流行","Alternative"],"artistBio":"不从API复制简介正文"}}]}"""
        val details = assertNotNull(AppleMusicProfileClient.parseProfile(json, "42"))
        assertEquals("2000年6月3日", details.born)
        assertNull(details.formed)
        assertEquals("中国台湾", details.origin)
        assertEquals(listOf("国语流行", "另类音乐"), details.genres)
        assertNull(details.biography)
    }

    @Test
    fun distinguishesGroupFormationFromBirthAndValidatesDates() {
        val group = """{"data":[{"id":"42","type":"artists","attributes":{"bornOrFormed":"1995-02","isGroup":true}}]}"""
        val details = assertNotNull(AppleMusicProfileClient.parseProfile(group, "42"))
        assertEquals("1995年2月", details.formed)
        assertNull(details.born)
        assertNull(AppleMusicProfileClient.parseProfile(group.replace("1995-02", "1995-02-30"), "42"))
        assertNull(AppleMusicProfileClient.parseProfile(group.replace(",\"isGroup\":true", ""), "42"))
    }

    @Test
    fun leavesUnlocalizedAndMissingFactsEmpty() {
        val json = """{"data":[{"id":"42","type":"artists","attributes":{"origin":"Nashville, TN","genreNames":["Unmapped genre"],"artistBio":"An English biography with unrelated recommendations."}}]}"""
        assertNull(AppleMusicProfileClient.parseProfile(json, "42"))
    }

    @Test
    fun rejectsMismatchedIdsTypesCanonicalUrlsAndAmbiguousDuplicates() {
        val artist = """{"id":"42","type":"artists","attributes":{"origin":"中国台湾","url":"https://music.apple.com/cn/artist/test/42"}}"""
        assertNull(AppleMusicProfileClient.parseProfile("""{"data":[$artist]}""", "43"))
        assertNull(AppleMusicProfileClient.parseProfile("""{"data":[${artist.replace("\"artists\"", "\"albums\"")}]}""", "42"))
        assertNull(AppleMusicProfileClient.parseProfile("""{"data":[${artist.replace("test/42", "test/999")}]}""", "42"))
        assertNull(AppleMusicProfileClient.parseProfile("""{"data":[$artist,$artist]}""", "42"))
        assertNull(AppleMusicProfileClient.parseProfile("not JSON", "42"))
    }

    @Test
    fun discoversOnlyThePublicAppleIndexAsset() {
        assertEquals(
            "https://music.apple.com/assets/index~abc123.js",
            AppleMusicProfileClient.indexBundleUrl("""<script src="https://example.com/assets/index~wrong.js"></script><script type="module" src="/assets/index~abc123.js"></script>"""),
        )
        assertNull(AppleMusicProfileClient.indexBundleUrl("""<script src="http://music.apple.com/assets/index~unsafe.js"></script>"""))
        assertNull(AppleMusicProfileClient.indexBundleUrl("""<script src="https://example.com/assets/index~unsafe.js"></script>"""))
    }

    @Test
    fun selectsTheReferencedUnexpiredPublicBearerInsteadOfUnrelatedTokens() {
        val now = 1_000_000L
        val publicBearer = fakeBearer(7200)
        val unrelated = fakeBearer(9000)
        val bundle = """const example="$unrelated",appBearer="$publicBearer";configure({developerToken:appBearer});"""
        assertEquals(publicBearer, AppleMusicProfileClient.publicBearerFromBundle(bundle, now))
        assertNull(AppleMusicProfileClient.publicBearerFromBundle("""const example="$unrelated";""", now))
        assertNull(AppleMusicProfileClient.publicBearerFromBundle(bundle.replace(publicBearer, fakeBearer(1)), now))
    }

    private fun fakeBearer(expirySeconds: Long): String {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return "${encode("""{"alg":"ES256","typ":"JWT"}""")}.${encode("""{"exp":$expirySeconds}""")}.fake-signature"
    }
}
