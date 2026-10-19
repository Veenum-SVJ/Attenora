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
        // TikTok exposes no stable feed id, so it also qualifies on a
        // screen-filling video surface — see [fullFeedApps].
        "com.zhiliaoapp.musically" to listOf(
            "video_feed", "feed_video", "reel"
        )
    )

    /**
     * Apps whose primary surface *is* full-bleed short video. For these an
     * oversized video/texture surface is enough on its own.
     */
    private val fullFeedApps = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill"
    )

    /** View classes that only appear where a video is actually playing. */
    val videoClasses = listOf("VideoView", "TextureView", "SurfaceView", "PlayerView")

    private val aliases = mapOf(
        "com.ss.android.ugc.trill" to "com.zhiliaoapp.musically",
        "app.revanced.android.youtube" to "com.google.android.youtube",
        "com.vanced.android.youtube" to "com.google.android.youtube",
        "com.facebook.lite" to "com.facebook.katana",
        "com.instagram.lite" to "com.instagram.android"
    )

    fun canonical(pkg: String): String = aliases[pkg] ?: pkg

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

    /** True when a screen-filling video surface alone identifies the feed. */
    fun isFullFeed(pkg: String): Boolean = canonical(pkg) in fullFeedApps

    /** Packages worth scanning at all (fast reject for everything else). */
    fun appliesTo(pkg: String): Boolean = canonical(pkg) in markers

    /** The key its counts are stored under. */
    fun creditKey(pkg: String): String = canonical(pkg)
}
