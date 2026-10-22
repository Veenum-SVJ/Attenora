package dev.opensource.focusguard.enforcement

/**
 * Where short videos live, per app.
 *
 * Detection is marker-first and deliberately conservative. A marker only counts
 * when the node carrying it covers most of the window: Instagram keeps a
 * "Reels" tab in its navigation bar on *every* screen, so matching the bare
 * word would call the home feed a reel feed. Requiring a big node is what
 * separates "there is a Reels button somewhere" from "a reel is playing".
 *
 * Cost: the marker scan runs at most once every [FocusGuardAccessibilityService]
 * interval while one of these apps is foreground, and never on any other app.
 * The per-swipe path itself is a single boolean check.
 *
 * Honest caveat (worth showing next to the switch): these are heuristics
 * against other apps' view ids. If a target app renames its feed views the
 * counter stops — the same contract as In-App Blocking.
 */
object Reels {

    /** id or label fragments that mean "this surface is the short-video feed". */
    private val markers: Map<String, List<String>> = mapOf(
        "com.google.android.youtube" to listOf(
            "reel_recycler", "reel_watch", "reel_player", "shorts"
        ),
        "com.instagram.android" to listOf(
            "clips_viewer", "clips_", "reel_viewer", "reels"
        ),
        "com.facebook.katana" to listOf(
            "clips_", "reel_"
        ),
        "com.snapchat.android" to listOf(
            "spotlight"
        ),
        // TikTok publishes obfuscated ids (v5i, jjq, 05rH…) except for the
        // player itself: on the For You feed the tree carries
        // "…:id/player_view" at ~94% of the screen and
        // "…:id/video_container_area" around it. Both were confirmed on a
        // real 2026 build; the old guesses (video_feed/feed_video) matched
        // nothing, which is why TikTok alone never counted.
        "com.zhiliaoapp.musically" to listOf(
            "player_view", "video_container_area", "video_feed", "feed_video"
        )
    )

    /**
     * Apps whose entire foreground is the video feed. For these a scroll of a
     * near-full-window pager is enough on its own, because their feed exposes
     * no stable id and the player surface is often absent from the tree.
     */
    fun isFeedFirst(pkg: String): Boolean = canonical(pkg) == "com.zhiliaoapp.musically"

    /**
     * Fragments that mean "this surface is a conversation, an inbox or a
     * comment sheet" — the opposite of a marker.
     *
     * A marker alone was not enough. Opening a direct message from a reel, or
     * the comment sheet over a short, leaves the player in the tree: the scan
     * still saw a screen-filling video, called the conversation a feed, and
     * counted swipes there. These fragments are matched against view ids only
     * (never labels, so an English word on a button cannot trigger them), and
     * the caller additionally requires the node to be big enough to be a whole
     * surface. Every one of these apps keeps a "Messages"/"Inbox" *button* on
     * its feed, which is far too small to pass that gate.
     */
    private val chatIds: Map<String, List<String>> = mapOf(
        "com.google.android.youtube" to listOf(
            "comment", "bottom_sheet", "engagement_panel"
        ),
        "com.instagram.android" to listOf(
            "direct_", "message_list", "message_thread", "thread_", "inbox",
            "comment", "notes_tray"
        ),
        "com.facebook.katana" to listOf(
            "message", "inbox", "thread", "comment"
        ),
        "com.snapchat.android" to listOf(
            "chat", "conversation", "message", "send_to", "friend_", "reply"
        ),
        "com.zhiliaoapp.musically" to listOf(
            "inbox", "chat", "im_", "message", "notice", "comment"
        )
    )

    /**
     * Bottom-tab labels that, when the tab reports itself as *selected*, prove
     * the user is in a chat section rather than the feed. This is the one
     * signal that survives TikTok's obfuscated ids and Instagram's DM inbox,
     * and it is checked only on selected nodes — every feed shows an unselected
     * "Messages" tab, so only the selected one is meaningful.
     */
    private val chatTabs = listOf(
        "inbox", "chats", "chat", "messages", "direct", "friends",
        "activity", "comments", "replies", "notifications"
    )

    /** Chat/inbox/comment id fragments for [pkg]; empty for anything else. */
    fun chatIdsFor(pkg: String): List<String> = chatIds[canonical(pkg)] ?: emptyList()

    /** True when [id] names a conversation, inbox or comment surface of [pkg]. */
    fun isChatSurfaceId(pkg: String, id: String): Boolean {
        // Only the id itself, never the package prefix: "com.snapchat.android"
        // contains "chat" and would exclude every Spotlight reel.
        val name = id.substringAfterLast('/')
        if (name.isEmpty()) return false
        return chatIdsFor(pkg).any { name.contains(it, ignoreCase = true) }
    }

    /** True when a selected tab labelled [label] means "not the feed". */
    fun isChatTab(label: String): Boolean {
        val l = label.trim().lowercase()
        if (l.isEmpty()) return false
        return chatTabs.any { tab ->
            l == tab || l.startsWith("$tab ") || l.startsWith("$tab(") || l.startsWith("$tab,")
        }
    }

    /** View classes that only appear where a video is actually playing. */
    val videoClasses = listOf("VideoView", "TextureView", "SurfaceView", "PlayerView")

    private val aliases = mapOf(
        "com.ss.android.ugc.trill" to "com.zhiliaoapp.musically",
        "app.revanced.android.youtube" to "com.google.android.youtube",
        "com.vanced.android.youtube" to "com.google.android.youtube",
        "com.facebook.lite" to "com.facebook.katana",
        "com.instagram.lite" to "com.instagram.android"
    )

    fun canonical(pkg: String): String {
        aliases[pkg]?.let { return it }
        val p = pkg.lowercase()
        return when {
            p.contains("youtube") || p.contains("vanced") || p.contains("newpipe") ->
                "com.google.android.youtube"
            p.contains("instagram") -> "com.instagram.android"
            p.contains("tiktok") || p.contains("musically") || p.contains("trill") ||
                p.contains("aweme") || p.contains("zhiliao") -> "com.zhiliaoapp.musically"
            p.contains("snapchat") || p.contains("snapenjoy") -> "com.snapchat.android"
            p.contains("facebook") && !p.contains("orca") -> "com.facebook.katana"
            else -> pkg
        }
    }

    /**
     * The other packages that mean the same app (Lite builds, patched clients).
     * Used for icon fallbacks, where a variant install would otherwise show as
     * a blank tile.
     */
    fun alternates(pkg: String): List<String> {
        val c = canonical(pkg)
        return aliases.filterValues { it == c }.keys.toList()
    }

    /** Marker fragments for [pkg]; empty for anything we do not count. */
    fun markersFor(pkg: String): List<String> = markers[canonical(pkg)] ?: emptyList()

    /**
     * True when a screen-filling video surface alone identifies the feed.
     * Every supported app plays full-bleed video in its short-video feed, so
     * this is no longer TikTok-only: Instagram Reels, Shorts, Facebook Reels
     * and Spotlight all qualify, while their home feeds only show small
     * inline videos that fail the size gate.
     */
    fun isFullFeed(pkg: String): Boolean = canonical(pkg) in markers

    /** Packages worth scanning at all (fast reject for everything else). */
    fun appliesTo(pkg: String): Boolean = canonical(pkg) in markers

    /** The key its counts are stored under. */
    fun creditKey(pkg: String): String = canonical(pkg)
}
