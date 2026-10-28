package dev.opensource.attenora.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The App monitor is repaired by editing ENABLED_ACCESSIBILITY_SERVICES by hand,
 * so these helpers decide whether a watch is forced to rebind. Getting them
 * wrong means either a repair that never happens (setting looks unchanged) or a
 * repair that disables someone else's accessibility service, which is why they
 * are string-only and tested here.
 */
class MonitorWatchdogTest {

    private val pkg = "dev.opensource.attenora"
    private val full = "$pkg/$pkg.enforcement.AttenoraAccessibilityService"
    private val short = "$pkg/.enforcement.AttenoraAccessibilityService"
    private val other = "com.edib.openwhispr/com.edib.openwhispr.WhisperAccessibilityService"

    @Test
    fun `recognises both serialisations android writes`() {
        assertTrue(accessibilityListHas(full, full, short))
        assertTrue(accessibilityListHas(short, full, short))
        assertTrue(accessibilityListHas("$other:$full", full, short))
        assertTrue(accessibilityListHas("$other:$short", full, short))
        // Toggling another service re-serialises the whole list; the case it
        // comes back in is not ours to choose.
        assertTrue(accessibilityListHas(full.uppercase(), full, short))
    }

    @Test
    fun `ignores an absent or empty setting`() {
        assertFalse(accessibilityListHas(null, full, short))
        assertFalse(accessibilityListHas("", full, short))
        assertFalse(accessibilityListHas(other, full, short))
    }

    @Test
    fun `a different service in the same package is not us`() {
        assertFalse(
            accessibilityListHas(
                "$pkg/$pkg.ui.LauncherDefault",
                full,
                short
            )
        )
        // Prefix-only matches would make this "granted" and skip the repair.
        assertFalse(accessibilityListHas("${full}X", full, short))
        assertFalse(accessibilityListHas("$pkg.enforcement.AttenoraAccessibilityService", full, short))
    }

    @Test
    fun `removing our entry keeps every other service`() {
        assertEquals(other, accessibilityListWithout("$other:$full", full, short))
        assertEquals(other, accessibilityListWithout("$full:$other", full, short))
        assertEquals(other, accessibilityListWithout("$other:$short", full, short))
        // Both forms present (a vendor can duplicate the entry).
        assertEquals(other, accessibilityListWithout("$short:$other:$full", full, short))
        assertEquals(other, accessibilityListWithout(other, full, short))
    }

    @Test
    fun `removing the only entry empties the list`() {
        assertEquals("", accessibilityListWithout(full, full, short))
        assertEquals("", accessibilityListWithout(short, full, short))
    }

    @Test
    fun `adding us writes the full form and never duplicates`() {
        assertEquals(full, accessibilityListWith("", full, short))
        assertEquals("$other:$full", accessibilityListWith(other, full, short))
        // A short-form copy is replaced by the full form, not added next to it.
        assertEquals("$other:$full", accessibilityListWith("$other:$short", full, short))
        assertEquals("$other:$full", accessibilityListWith("$other:$full", full, short))
    }

    @Test
    fun `adding us twice is the same as adding us once`() {
        val once = accessibilityListWith("$other:$short", full, short)
        assertEquals(once, accessibilityListWith(once, full, short))
    }
}
