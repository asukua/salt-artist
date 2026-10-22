package dev.saltartist.hook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HookTargetsTest {
    @Test
    fun acceptsVerifiedHostVersions() {
        listOf("1.19.0", "1.19.1").forEach { version ->
            assertTrue(HookTargets.isCompatibleHost(version), version)
        }
    }

    @Test
    fun acceptsExistingHyphenatedVersionSuffixes() {
        listOf("1.19.0-Steam", "1.19.1-Steam", "1.19.1-dev-build").forEach { version ->
            assertTrue(HookTargets.isCompatibleHost(version), version)
            assertEquals(HookTargets.forHost(version.substringBefore('-')), HookTargets.forHost(version), version)
        }
    }

    @Test
    fun rejectsUnverifiedVersionsAndMalformedValues() {
        listOf(
            "1.18.9", "1.19.2", "1.20.0", "2.0.0", "", "1.19", "1.19.1foo",
            "v1.19.1", " 1.19.1", "1.19.1 ",
        ).forEach { version ->
            assertFalse(HookTargets.isCompatibleHost(version), version)
            assertNull(HookTargets.forHost(version), version)
        }
    }

    @Test
    fun selectsVerifiedTargetsForEachHostVersion() {
        val oldHost = assertNotNull(HookTargets.forHost("1.19.0"))
        val currentHost = assertNotNull(HookTargets.forHost("1.19.1"))

        assertEquals("androidx.compose.ui.\u0137", oldHost.artistPageClass)
        assertEquals("androidx.compose.ui.\u0179", oldHost.artistViewModelClass)
        assertEquals("androidx.compose.ui.\u0219", oldHost.artistAvatarClass)
        assertEquals("androidx.compose.ui.\u03B8", currentHost.artistPageClass)
        assertEquals("androidx.compose.ui.\u10A9", currentHost.artistViewModelClass)
        assertEquals("androidx.compose.ui.\u0843", currentHost.artistAvatarClass)
        assertNotEquals(oldHost.artistPageClass, currentHost.artistPageClass)
        assertNotEquals(oldHost.artistViewModelClass, currentHost.artistViewModelClass)
        assertNotEquals(oldHost.artistAvatarClass, currentHost.artistAvatarClass)
    }

    @Test
    fun usesTheMatchingViewModelInEachArtistPageSignature() {
        val route = "com.xuncorp.voxzen.ui.navigation.ScreenRoute\$ArtistContent"
        assertEquals(
            listOf(route, "androidx.compose.ui.\u0179"),
            assertNotNull(HookTargets.forHost("1.19.0")).artistPageParams,
        )
        assertEquals(
            listOf(route, "androidx.compose.ui.\u10A9"),
            assertNotNull(HookTargets.forHost("1.19.1")).artistPageParams,
        )
        assertEquals(
            listOf("com.xuncorp.voxzen.data.entity.Artist", "androidx.compose.ui.Modifier"),
            HookTargets.ARTIST_AVATAR_PARAMS,
        )
    }

    @Test
    fun directoryHooksAreEnabledOnlyForTheSeparatelyVerifiedHost() {
        assertNull(assertNotNull(HookTargets.forHost("1.19.0")).directory)
        val directory = assertNotNull(assertNotNull(HookTargets.forHost("1.19.1-Steam")).directory)
        assertEquals("androidx.compose.ui.\u0417", directory.screenClass)
        assertEquals("androidx.compose.ui.\u10A5", directory.itemClass)
        assertEquals("kotlin.jvm.functions.Function1", HookTargets.DIRECTORY_GRID_PARAMS.last())
        assertEquals(11, HookTargets.DIRECTORY_GRID_PARAMS.size)
    }
}
