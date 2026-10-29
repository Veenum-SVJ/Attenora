package dev.opensource.attenora.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home banner and the App health page both derive their state from
 * [healthIssue], so this ordering is the whole contract: a stalled monitor has
 * to win over everything else, because it is the one failure that looks healthy
 * everywhere outside the app.
 */
class HealthTest {

    @Test
    fun `a monitor that is on but not connected is the stalled case`() {
        assertEquals(
            HealthIssue.MONITOR_STALLED,
            healthIssue(usageGranted = true, monitorEnabled = true, monitorConnected = false)
        )
    }

    @Test
    fun `stalled wins over missing usage access`() {
        // Both are broken; the one that silently stops enforcement is reported
        // first, because that is the one the user cannot see anywhere else.
        assertEquals(
            HealthIssue.MONITOR_STALLED,
            healthIssue(usageGranted = false, monitorEnabled = true, monitorConnected = false)
        )
    }

    @Test
    fun `missing usage access is reported when the monitor is fine`() {
        assertEquals(
            HealthIssue.USAGE_ACCESS,
            healthIssue(usageGranted = false, monitorEnabled = true, monitorConnected = true)
        )
    }

    @Test
    fun `a deliberate off switch is reported last`() {
        assertEquals(
            HealthIssue.MONITOR_OFF,
            healthIssue(usageGranted = true, monitorEnabled = false, monitorConnected = false)
        )
    }

    @Test
    fun `everything working means no banner`() {
        assertNull(healthIssue(usageGranted = true, monitorEnabled = true, monitorConnected = true))
    }

    @Test
    fun `xiaomi gets the HyperOS steps`() {
        val guide = vendorGuide("Xiaomi", "Redmi")
        assertTrue(guide.battery.contains("No restrictions"))
        assertTrue(guide.autostart.contains("Autostart"))
        assertTrue(guide.recents.contains("padlock"))
    }

    @Test
    fun `brand alone is enough to pick the guide`() {
        // Build.MANUFACTURER is "Xiaomi" on a POCO but the brand differs; the
        // match must not depend on which of the two carries the name.
        val guide = vendorGuide("unknown", "POCO")
        assertTrue(guide.battery.contains("No restrictions"))
    }

    @Test
    fun `unknown vendors fall back to the stock android wording`() {
        val guide = vendorGuide("Google", "Pixel")
        assertTrue(guide.battery.contains("Don't optimize"))
        assertTrue(guide.autostart.contains("No autostart"))
    }
}
