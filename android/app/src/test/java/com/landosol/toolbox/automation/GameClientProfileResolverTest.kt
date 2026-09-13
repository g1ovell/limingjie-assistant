package com.landosol.toolbox.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClientProfileResolverTest {
    @Test
    fun `resolves only Bilibili`() {
        assertEquals(
            GameClientResolution.Available(GameClientProfiles.BILIBILI),
            GameClientProfileResolver.resolve(setOf(GameClientProfiles.BILIBILI.packageName)),
        )
    }

    @Test
    fun `resolves only Xiaomi`() {
        assertEquals(
            GameClientResolution.Available(GameClientProfiles.XIAOMI),
            GameClientProfileResolver.resolve(setOf(GameClientProfiles.XIAOMI.packageName)),
        )
    }

    @Test
    fun `returns unavailable when neither client is installed`() {
        assertEquals(GameClientResolution.Unavailable, GameClientProfileResolver.resolve(emptySet()))
    }

    @Test
    fun `requires explicit selection when both clients are installed`() {
        val resolution = GameClientProfileResolver.resolve(
            setOf(GameClientProfiles.BILIBILI.packageName, GameClientProfiles.XIAOMI.packageName),
        )
        assertTrue(resolution is GameClientResolution.Ambiguous)
        assertEquals(2, (resolution as GameClientResolution.Ambiguous).profiles.size)
    }

    @Test
    fun `defines both supported package names`() {
        assertEquals("com.bilibili.priconne", GameClientProfiles.BILIBILI.packageName)
        assertEquals("com.bilibili.priconne.mi", GameClientProfiles.XIAOMI.packageName)
    }
}
