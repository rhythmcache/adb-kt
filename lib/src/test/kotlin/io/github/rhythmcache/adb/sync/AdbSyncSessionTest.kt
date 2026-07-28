package io.github.rhythmcache.adb.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSyncSessionTest {
    @Test
    fun `stat_v2 detected from device banner with semicolons`() {
        val banner = "device::ro.product.name=foo;ro.product.model=bar;ro.product.device=baz;features=stat_v2,shell_v2,cmd"
        val features = banner.substringAfter("features=", "").substringBefore(";").split(",")
        assertTrue("stat_v2" in features)
    }

    @Test
    fun `stat_v2 detected when it is the only feature`() {
        val banner = "device::features=stat_v2"
        val features = banner.substringAfter("features=", "").substringBefore(";").split(",")
        assertTrue("stat_v2" in features)
    }

    @Test
    fun `stat_v2 not present when not in features`() {
        val banner = "device::features=shell_v2,cmd"
        val features = banner.substringAfter("features=", "").substringBefore(";").split(",")
        assertFalse("stat_v2" in features)
    }

    @Test
    fun `empty features when no features in banner`() {
        val banner = "device::ro.product.name=foo"
        val features = banner.substringAfter("features=", "").substringBefore(";").split(",")
        assertTrue(features.isEmpty() || features == listOf(""))
    }

    @Test
    fun `stat_v2 works with host banner format`() {
        val banner = "host::features=stat_v2,shell_v2,cmd"
        val features = banner.substringAfter("features=", "").substringBefore(";").split(",")
        assertTrue("stat_v2" in features)
    }

    @Test
    fun `substringBefore semicolon stops at correct delimiter`() {
        val banner = "device::features=stat_v2,shell_v2;ro.product.name=foo"
        val featuresStr = banner.substringAfter("features=", "").substringBefore(";")
        assertFalse(featuresStr.contains("ro.product.name"))
        val features = featuresStr.split(",")
        assertTrue("stat_v2" in features)
        assertTrue("shell_v2" in features)
    }
}
