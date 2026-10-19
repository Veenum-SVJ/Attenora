package dev.opensource.focusguard.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reel counter's rules. The scan itself needs a live accessibility tree and
 * is verified by hand on a device; everything here is the part that decides
 * *which* packages are counted and under which key — the part that silently
 * miscounts when it is wrong.
 */
class ReelsTest {

    @Test
    fun `counts the four short-video apps`() {
        assertTrue(Reels.appliesTo("com.google.android.youtube"))
        assertTrue(Reels.appliesTo("com.instagram.android"))
        assertTrue(Reels.appliesTo("com.facebook.katana"))
        assertTrue(Reels.appliesTo("com.snapchat.android"))
        assertTrue(Reels.appliesTo("com.zhiliaoapp.musically"))
    }

    @Test
    fun `ignores everything else`() {
        assertFalse(Reels.appliesTo("com.whatsapp"))
        assertFalse(Reels.appliesTo("dev.opensource.focusguard"))
        assertFalse(Reels.appliesTo("com.android.chrome"))
        assertTrue(Reels.markersFor("com.whatsapp").isEmpty())
    }

    @Test
    fun `lite and variant builds credit the same counter`() {
        // The point of canonicalising: an Instagram Lite view and a full
        // Instagram view are the same habit and must not split the total.
        assertEquals(
            Reels.creditKey("com.instagram.android"),
            Reels.creditKey("com.instagram.lite")
        )
        assertEquals(
            Reels.creditKey("com.zhiliaoapp.musically"),
            Reels.creditKey("com.ss.android.ugc.trill")
        )
        assertEquals(
            Reels.creditKey("com.google.android.youtube"),
            Reels.creditKey("app.revanced.android.youtube")
        )
        assertTrue(Reels.appliesTo("com.instagram.lite"))
    }

    @Test
    fun `only apps whose whole surface is video accept a bare video node`() {
        assertTrue(Reels.isFullFeed("com.zhiliaoapp.musically"))
        // Instagram must not: a full-bleed video also appears in a normal post.
        assertFalse(Reels.isFullFeed("com.instagram.android"))
        assertFalse(Reels.isFullFeed("com.google.android.youtube"))
    }
}
