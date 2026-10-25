package dev.opensource.attenora.enforcement

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
        assertFalse(Reels.appliesTo("dev.opensource.attenora"))
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
    fun `a full-bleed video identifies the short-video feed in every supported app`() {
        // Every supported app plays its short videos full-bleed. The size gate
        // (65 % of the window) is what keeps a small inline video in a normal
        // feed from counting, not a per-app allowlist.
        assertTrue(Reels.isFullFeed("com.zhiliaoapp.musically"))
        assertTrue(Reels.isFullFeed("com.instagram.android"))
        assertTrue(Reels.isFullFeed("com.google.android.youtube"))
        assertTrue(Reels.isFullFeed("com.snapchat.android"))
        assertFalse(Reels.isFullFeed("com.whatsapp"))
    }

    @Test
    fun `instagram home feed is not the reels viewer`() {
        // Regression, Pixel 2026-10-25: an in-feed Reel renders as
        // `root_clips_layout` at ~88 % of the window, so the old "clips_"
        // fragment matched it and every swipe on the home feed counted.
        // Only the viewer's own container ids may mark the surface.
        val markers = Reels.markersFor("com.instagram.android")
        assertTrue("clips_viewer" in markers)
        assertTrue("reel_viewer" in markers)

        // Home feed ids, all captured from a real home-feed dump.
        for (homeId in listOf(
            "com.instagram.android:id/root_clips_layout",
            "com.instagram.android:id/reels_tray_container",
            "com.instagram.android:id/reel_empty_badge",
            "com.instagram.android:id/clips_tab"
        )) {
            assertFalse(
                "$homeId must not match a marker",
                markers.any { homeId.contains(it, ignoreCase = true) }
            )
        }

        // Reels viewer ids, captured from a real Reels-tab dump. Only the
        // viewer's own container/pager may mark the surface: the media
        // container below is deliberately *not* a marker, because an in-feed
        // Reel can reuse the same clips components and must not count.
        for (viewerId in listOf(
            "com.instagram.android:id/clips_viewer_container",
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_viewer_action_bar"
        )) {
            assertTrue(
                "$viewerId must match a marker",
                markers.any { viewerId.contains(it, ignoreCase = true) }
            )
        }
        assertFalse(
            "the clips media container alone must not mark the surface",
            markers.any { "com.instagram.android:id/clips_video_container".contains(it, ignoreCase = true) }
        )
    }

    @Test
    fun `instagram alone may not be marked by player size alone`() {
        // Instagram's home feed plays video near full-bleed, so "big player"
        // is not evidence of the viewer there. Everywhere else the home feed's
        // inline videos stay under the size gate, so the shortcut is kept.
        assertFalse(Reels.videoClassFeed("com.instagram.android"))
        assertFalse(Reels.videoClassFeed("com.instagram.lite"))
        assertTrue(Reels.videoClassFeed("com.google.android.youtube"))
        assertTrue(Reels.videoClassFeed("com.snapchat.android"))
        assertTrue(Reels.videoClassFeed("com.facebook.katana"))
        assertFalse(Reels.videoClassFeed("com.whatsapp"))
    }

    @Test
    fun `repackaged clients resolve to their family`() {
        // Morphe / Pico / InstaPro style repacks keep neither the label nor a
        // tidy package name, but the family token is still in there.
        assertEquals("com.google.android.youtube", Reels.canonical("app.morphe.android.youtube"))
        assertEquals("com.instagram.android", Reels.canonical("com.pico.instagram"))
        assertEquals("com.zhiliaoapp.musically", Reels.canonical("com.zhiliaoapp.musically.go"))
        assertEquals("com.facebook.katana", Reels.canonical("com.facebook.lite"))
        // Messenger must not inherit Facebook's reel rules.
        assertEquals("com.facebook.orca", Reels.canonical("com.facebook.orca"))
    }

    @Test
    fun `conversations and comment sheets are not the feed`() {
        // A DM opened from a reel, or a comment sheet over a short, keeps the
        // player in the tree. These ids are the surfaces themselves, not the
        // small buttons that carry the same words on a feed.
        assertTrue(
            Reels.isChatSurfaceId(
                "com.instagram.android",
                "com.instagram.android:id/direct_thread"
            )
        )
        assertTrue(
            Reels.isChatSurfaceId(
                "com.instagram.android",
                "com.instagram.android:id/message_list"
            )
        )
        assertTrue(
            Reels.isChatSurfaceId(
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically:id/inbox_container"
            )
        )
        assertTrue(
            Reels.isChatSurfaceId(
                "com.google.android.youtube",
                "com.google.android.youtube:id/comments_list"
            )
        )
        assertTrue(
            Reels.isChatSurfaceId(
                "com.snapchat.android",
                "com.snapchat.android:id/chat_conversation_view"
            )
        )
        assertTrue(
            Reels.isChatSurfaceId(
                "com.facebook.katana",
                "com.facebook.katana:id/thread_list"
            )
        )
    }

    @Test
    fun `feed ids are never read as conversations`() {
        // The gate must not eat a real reel: these are the ids that were
        // verified on a phone for the four families.
        assertFalse(
            Reels.isChatSurfaceId(
                "com.instagram.android",
                "com.instagram.android:id/clips_viewer"
            )
        )
        assertFalse(
            Reels.isChatSurfaceId(
                "com.google.android.youtube",
                "com.google.android.youtube:id/reel_recycler"
            )
        )
        assertFalse(
            Reels.isChatSurfaceId(
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically:id/player_view"
            )
        )
        assertFalse(
            Reels.isChatSurfaceId(
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically:id/video_container_area"
            )
        )
        assertFalse(
            Reels.isChatSurfaceId(
                "com.snapchat.android",
                "com.snapchat.android:id/spotlight_feed_pager"
            )
        )
        assertFalse(Reels.isChatSurfaceId("com.instagram.android", ""))
        // Nothing to exclude in an app we do not count.
        assertTrue(Reels.chatIdsFor("com.whatsapp").isEmpty())
    }

    @Test
    fun `a word inside a longer id is not a chat surface`() {
        // Found on a phone: TikTok's feed carries "scrim_view" at full size,
        // and plain substring matching read it as "im" and killed the counter.
        assertFalse(
            Reels.isChatSurfaceId(
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically:id/scrim_view"
            )
        )
        assertFalse(
            Reels.isChatSurfaceId(
                "com.snapchat.android",
                "com.snapchat.android:id/rear_view"
            )
        )
        assertFalse(
            Reels.isChatSurfaceId(
                "com.instagram.android",
                "com.instagram.android:id/commentary_button"
            )
        )
        // The real surfaces still match, with or without a plural.
        assertTrue(
            Reels.isChatSurfaceId(
                "com.instagram.android",
                "com.instagram.android:id/direct_thread"
            )
        )
        assertTrue(
            Reels.isChatSurfaceId(
                "com.google.android.youtube",
                "com.google.android.youtube:id/comments_list"
            )
        )
    }

    @Test
    fun `a selected chat tab means the user left the feed`() {
        assertTrue(Reels.isChatTab("Inbox"))
        assertTrue(Reels.isChatTab("inbox (3)"))
        assertTrue(Reels.isChatTab("Chats"))
        assertTrue(Reels.isChatTab("Direct"))
        assertTrue(Reels.isChatTab("Messages"))
        assertTrue(Reels.isChatTab("Friends"))
        assertTrue(Reels.isChatTab("Comments"))
        // The tabs that own the feeds must stay counting.
        assertFalse(Reels.isChatTab("Home"))
        assertFalse(Reels.isChatTab("Reels"))
        assertFalse(Reels.isChatTab("Shorts"))
        assertFalse(Reels.isChatTab("Spotlight"))
        assertFalse(Reels.isChatTab(""))
    }
}
