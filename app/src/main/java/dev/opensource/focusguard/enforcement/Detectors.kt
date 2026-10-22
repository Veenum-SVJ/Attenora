package dev.opensource.focusguard.enforcement

/**
 * Per-app element rules for In-App Blocking ("smart hide + auto-close").
 *
 * A rule matches nodes by view-id fragment and/or visible text. What happens
 * when it matches depends on [action]:
 *  - AUTO  → if the element fills most of the screen (a feed/surface), navigate
 *            Back out of it; otherwise cover just that region with an overlay.
 *  - BACK  → always navigate back (surfaces that only exist fullscreen).
 *  - COVER → always cover the element's bounds with an overlay.
 *
 * Honest caveat (shown in the UI): these are heuristics against other apps'
 * UIs and can stop matching when the target app updates.
 */
data class ElementSpec(
    val key: String,
    val texts: List<String> = emptyList(),
    val ids: List<String> = emptyList(),
    val action: DetectAction = DetectAction.AUTO
)

enum class DetectAction { AUTO, BACK, COVER }

object Detectors {

    /** Element keys, labels and default toggles shown on the In-App Blocking screen. */
    data class ElementUi(
        val pkg: String,
        val key: String,
        val label: String,
        val icon: String // key of the custom icon set to render
    )

    val youtube = "com.google.android.youtube"
    val instagram = "com.instagram.android"
    val facebook = "com.facebook.katana"
    val snapchat = "com.snapchat.android"
    val x = "com.twitter.android"
    val tiktok = "com.zhiliaoapp.musically"
    val whatsapp = "com.whatsapp"
    val messenger = "com.facebook.orca"

    /** UI catalogue: package + supported elements per app. */
    val uiCatalog: List<Pair<String, List<ElementUi>>> = listOf(
        youtube to listOf(
            ElementUi(youtube, "shorts", "Shorts", "shorts"),
            ElementUi(youtube, "video_search", "Video search", "search"),
            ElementUi(youtube, "pip", "Picture-in-picture mode", "pip"),
            ElementUi(youtube, "comments", "Comments", "comments")
        ),
        instagram to listOf(
            ElementUi(instagram, "stories", "Stories", "stories"),
            ElementUi(instagram, "reels", "Reels", "reels"),
            ElementUi(instagram, "explore", "Explore tab", "explore")
        ),
        facebook to listOf(
            ElementUi(facebook, "explore", "Explore", "explore"),
            ElementUi(facebook, "stories", "Stories", "stories")
        ),
        snapchat to listOf(
            ElementUi(snapchat, "spotlight", "Spotlight", "spotlight"),
            ElementUi(snapchat, "stories", "Stories", "stories")
        ),
        x to listOf(
            ElementUi(x, "explore", "Explore", "explore"),
            ElementUi(x, "search", "Search", "search")
        ),
        tiktok to listOf(
            ElementUi(tiktok, "search", "Search", "search"),
            ElementUi(tiktok, "comments", "Comments", "comments")
        ),
        whatsapp to listOf(
            ElementUi(whatsapp, "status", "Status", "stories"),
            ElementUi(whatsapp, "channels", "Channels", "channels"),
            ElementUi(whatsapp, "meta_ai", "Meta AI", "ai")
        )
    )

    private val specs: Map<String, List<ElementSpec>> = mapOf(
        youtube to listOf(
            ElementSpec("shorts", texts = listOf("shorts"), ids = listOf("shorts", "reel_recycler", "reel_watch"), action = DetectAction.AUTO),
            ElementSpec("video_search", texts = listOf("search youtube", "search results"), ids = listOf("searchbar", "search_bar"), action = DetectAction.AUTO),
            ElementSpec("pip", texts = listOf("picture-in-picture", "picture in picture"), ids = listOf("pip", "picture_in_picture"), action = DetectAction.BACK),
            ElementSpec("comments", texts = listOf("comments"), ids = listOf("comment"), action = DetectAction.AUTO)
        ),
        instagram to listOf(
            ElementSpec("stories", texts = listOf("your story", "stories"), ids = listOf("story", "reel_viewer"), action = DetectAction.AUTO),
            // Match the Reels viewer itself, not the word "Reels" — Instagram
            // keeps that word in its bottom bar on every screen, so the old
            // text rule pressed Back on the home feed, reloading it in a loop.
            ElementSpec("reels", ids = listOf("clips_viewer", "clips_", "reel_viewer"), action = DetectAction.BACK),
            ElementSpec("explore", texts = listOf("explore"), ids = listOf("explore"), action = DetectAction.BACK)
        ),
        facebook to listOf(
            ElementSpec("explore", texts = listOf("explore"), ids = listOf("explore"), action = DetectAction.BACK),
            ElementSpec("stories", texts = listOf("stories", "create story"), ids = listOf("story"), action = DetectAction.AUTO)
        ),
        snapchat to listOf(
            ElementSpec("spotlight", texts = listOf("spotlight"), ids = listOf("spotlight"), action = DetectAction.BACK),
            ElementSpec("stories", texts = listOf("stories"), ids = listOf("story"), action = DetectAction.AUTO)
        ),
        x to listOf(
            ElementSpec("explore", texts = listOf("explore"), ids = listOf("explore", "trending"), action = DetectAction.BACK),
            ElementSpec("search", texts = listOf("search"), ids = listOf("search"), action = DetectAction.AUTO)
        ),
        tiktok to listOf(
            ElementSpec("search", texts = listOf("search"), ids = listOf("search"), action = DetectAction.AUTO),
            ElementSpec("comments", texts = listOf("comments"), ids = listOf("comment"), action = DetectAction.AUTO)
        ),
        whatsapp to listOf(
            ElementSpec("status", texts = listOf("status"), ids = listOf("status"), action = DetectAction.AUTO),
            ElementSpec("channels", texts = listOf("channels"), ids = listOf("channel"), action = DetectAction.AUTO),
            ElementSpec("meta_ai", texts = listOf("meta ai"), ids = listOf("meta_ai", "ai_chat"), action = DetectAction.AUTO)
        )
    )

    /** Alternate package ids (clones/mods) that share another app's rules. */
    private val aliases = mapOf(
        "com.ss.android.ugc.trill" to tiktok,
        "app.revanced.android.youtube" to youtube,
        "com.vanced.android.youtube" to youtube,
        "com.facebook.lite" to facebook,
        "com.instagram.lite" to instagram
    )

    fun specsFor(pkg: String): List<ElementSpec> = specs[canonical(pkg)] ?: emptyList()

    /**
     * Map a package to the catalogue entry it should use. Patched clients
     * (Morphe/ReVanced YouTube, Pico/InstaPro Instagram, TikTok's `trill`
     * build) usually keep the original package, but some repackage — so fall
     * back to a name match instead of silently applying no rules at all.
     */
    fun canonical(pkg: String): String {
        if (specs.containsKey(pkg)) return pkg
        aliases[pkg]?.let { return it }
        val p = pkg.lowercase()
        return when {
            p.contains("youtube") || p.contains("vanced") || p.contains("newpipe") -> youtube
            p.contains("instagram") -> instagram
            p.contains("tiktok") || p.contains("musically") || p.contains("trill") || p.contains("aweme") -> tiktok
            p.contains("snapchat") || p.contains("snapenjoy") -> snapchat
            p.contains("facebook") && !p.contains("orca") -> facebook
            p.contains("whatsapp") -> whatsapp
            p.contains("orca") -> messenger
            else -> pkg
        }
    }

    /** Packages worth traversing at all (fast reject for everything else). */
    fun appliesTo(pkg: String): Boolean = specsFor(pkg).isNotEmpty()
}
