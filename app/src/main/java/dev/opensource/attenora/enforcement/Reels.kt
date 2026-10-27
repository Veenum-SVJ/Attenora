package dev.opensource.attenora.enforcement

/**
 * Where short videos live, per app.
 *
 * Detection is marker-first and deliberately conservative. A marker only counts
 * when the node carrying it covers most of the window: Instagram keeps a
 * "Reels" tab in its navigation bar on *every* screen, so matching the bare
 * word would call the home feed a reel feed. Requiring a big node is what
 * separates "there is a Reels button somewhere" from "a reel is playing".
 *
 * Cost: the marker scan runs at most once every [AttenoraAccessibilityService]
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
        // Instagram: only the immersive viewer's own container ids count.
        // The bare "clips_"/"reels" fragments were matching the home feed —
        // an in-feed Reel renders as `root_clips_layout` at ~88% of the window
        // (confirmed on a 2026 build, Pixel: bounds [0,128][1080,2241]), so it
        // cleared the size gate and every swipe on the home feed counted. The
        // viewer carries `clips_viewer_container` / `clips_viewer_view_pager`
        // over the same area, and neither id exists anywhere in the home feed
        // (only `clips_tab`, `reel_empty_badge`, `reels_tray_container` — all
        // far too small to pass the gate).
        "com.instagram.android" to listOf(
            "clips_viewer", "reel_viewer"
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
            "comment", "comments", "bottom_sheet", "engagement_panel"
        ),
        "com.instagram.android" to listOf(
            "direct", "message_list", "message_thread", "thread", "threads",
            "inbox", "comment", "comments", "notes_tray"
        ),
        "com.facebook.katana" to listOf(
            "message", "messages", "inbox", "thread", "threads",
            "comment", "comments"
        ),
        "com.snapchat.android" to listOf(
            "chat", "chats", "conversation", "conversations", "message",
            "messages", "send_to", "friend", "friends", "reply", "replies"
        ),
        "com.zhiliaoapp.musically" to listOf(
            "inbox", "im", "chat", "chats", "message", "messages",
            "notice", "notices", "comment", "comments"
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
        val name = id.substringAfterLast('/').lowercase()
        if (name.isEmpty()) return false
        return chatIdsFor(pkg).any { containsToken(name, it) }
    }

    /**
     * True when [token] appears in [name] as a whole id segment, so "scrim_view"
     * is not "im" and "player_view" is not "chat". Plain substring matching let
     * TikTok's feed scrim be read as a conversation and stopped the counter
     * dead — the one thing a false positive must never do.
     */
    private fun containsToken(name: String, token: String): Boolean {
        if (token.isEmpty()) return false
        var i = name.indexOf(token)
        while (i >= 0) {
            val before = i == 0 || name[i - 1] == '_' || name[i - 1] == '.'
            val end = i + token.length
            val after = end == name.length || name[end] == '_' || name[end] == '.'
            if (before && after) return true
            i = name.indexOf(token, i + 1)
        }
        return false
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
     * this is no longer TikTok-only: Shorts, Facebook Reels and Spotlight all
     * qualify, while their home feeds only show small inline videos that fail
     * the size gate.
     *
     * Instagram and Snapchat are deliberately excluded (see [videoClassFeed]).
     */
    fun isFullFeed(pkg: String): Boolean = canonical(pkg) in markers

    /** Apps where a big video alone is not proof of the short-video feed. */
    private val markerOnly = setOf("com.instagram.android", "com.snapchat.android")

    /**
     * Whether the size-of-the-player path may mark [pkg]'s feed on its own.
     *
     * False for Instagram, where the home feed's own players are as big as the
     * viewer's: there only the viewer ids in [markers] may mark the surface.
     *
     * False for Snapchat for the same class of reason, learned on a Pixel:
     * a story, a snap playing back in a conversation and a Spotlight video are
     * all full-bleed video, so "big player" cannot separate them — every
     * swipe in Stories and in opened snaps counted as a Spotlight reel. There
     * only the Spotlight ids in [markers] may mark the surface.
     */
    fun videoClassFeed(pkg: String): Boolean =
        isFullFeed(pkg) && canonical(pkg) !in markerOnly

    /** Packages worth scanning at all (fast reject for everything else). */
    fun appliesTo(pkg: String): Boolean = canonical(pkg) in markers

    /** The key its counts are stored under. */
    fun creditKey(pkg: String): String = canonical(pkg)
}
