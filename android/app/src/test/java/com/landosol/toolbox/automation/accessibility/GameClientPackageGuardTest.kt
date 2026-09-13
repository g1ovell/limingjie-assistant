package com.landosol.toolbox.automation.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClientPackageGuardTest {
    @Test
    fun `accepts the resolved Xiaomi foreground package`() {
        assertTrue(isExpectedGamePackage("com.bilibili.priconne.mi", "com.bilibili.priconne.mi"))
    }

    @Test
    fun `rejects Bilibili foreground when Xiaomi is resolved`() {
        assertFalse(isExpectedGamePackage("com.bilibili.priconne.mi", "com.bilibili.priconne"))
    }

    @Test
    fun `rejects foreground when resolution is unavailable or ambiguous`() {
        assertFalse(isExpectedGamePackage(null, "com.bilibili.priconne.mi"))
        assertFalse(isExpectedGamePackage(null, "com.bilibili.priconne"))
        assertFalse(isExpectedGamePackage(null, null))
        assertFalse(isExpectedGamePackage("com.bilibili.priconne.mi", null))
    }
}
