package dev.opensource.focusguard.domain

/**
 * Offline app → category classifier. Pure Kotlin (unit-tested on the JVM).
 *
 * Heuristics only: package segments + label words. No network, no models.
 * Manual assignment in the UI always wins over this guess.
 */
object CategoryClassifier {

    const val SOCIAL = "Social"
    const val VIDEO = "Video"
    const val GAMES = "Games"
    const val MESSAGING = "Messaging"
    const val NEWS = "News"
    const val SHOPPING = "Shopping"
    const val PRODUCTIVITY = "Productivity"
    const val TOOLS = "Tools"
    const val UNSPECIFIED = "Not Specified"

    val defaultOrder = listOf(
        SOCIAL, VIDEO, GAMES, MESSAGING, NEWS, SHOPPING, PRODUCTIVITY, TOOLS, UNSPECIFIED
    )

    /** Hard package matches checked before word heuristics. */
    private val packageRules = listOf(
        SOCIAL to listOf(
            "com.facebook.katana", "com.facebook.lite", "com.instagram.android",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",            "com.twitter.android",
            "com.x.android", "com.snapchat.android", "com.reddit.frontpage",
            "com.pinterest", "com.tumblr", "com.linkedin.android", "com.quora.android",
            "com.vkontakte.android"
        ),
        VIDEO to listOf(
            "com.google.android.youtube", "com.vanced.youtube", "app.revanced.android.youtube",
            "com.netflix.mediaclient", "com.spotify.music", "com.amazon.avod",
            "com.disney.disneyplus", "com.hulu.plus", "com.twitch.android.app",
            "com.mxtech.videoplayer", "com.plex.android", "tv.twitch.android.app"
        ),
        GAMES to listOf(
            "com.supercell.clashofclans", "com.supercell.clashroyale", "com.supercell.brawlstars",
            "com.mojang.minecraftpe", "com.dts.freefireth", "com.dts.freefiremax",
            "com.activision.callofduty.shooter", "com.garena.game.kgtw", "com.epicgames.fortnite",
            "com.riotgames.league.wildrift", "com.miHoYo.GenshinImpact", "com.tencent.ig",
            "com.kiloo.subwaysurf", "com.outfit7.mytalkingtom", "com.imangi.templetrun"
        ),
        MESSAGING to listOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "com.google.android.apps.messaging",
            "org.telegram.messenger", "org.thunderdog.challegram", "com.discord", "com.skype.raider",
            "jp.naver.line.android",            "com.viber.voip", "com.kakao.talk", "com.textnow",
            "com.samsung.android.messaging"
        ),
        NEWS to listOf(
            "com.google.android.apps.news", "com.feedly", "com.flipboard.app",
            "com.nytimes.android", "com.deep.silver.rdo", // placeholder harmless
            "org.npr.news", "com.bbc.stage.apps.news", "com.yahoo.mobile.client.mail",
            "com.opera.news", "com.cnn.android", "com.reuters.android"
        ),
        SHOPPING to listOf(
            "com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.aliexpress",
            "com.shopee.app", "com.lazada.android", "com.walmart.spark",
            "com.target.ui", "com.bestbuy.android", "com.etsy.android"
        ),
        PRODUCTIVITY to listOf(
            "com.microsoft.office.outlook", "com.microsoft.office.word",
            "com.microsoft.office.excel", "com.microsoft.office.powerpoint",
            "com.google.android.gm", "com.google.android.apps.docs",
            "com.notion.id", "com.todoist", "com.evernote", "org.nicenice.nicepage",
            "com.dropbox.android", "com.adobe.reader", "com.github.android",
            "com.atlassian.jira.core", "com.slack", "com.todo"
        )
    )

    private val labelRules = listOf(
        SOCIAL to listOf("instagram", "tiktok", "facebook", "snapchat", "twitter", " x ", "reddit", "pinterest", "linkedin", "tumblr", "threads", "social"),
        VIDEO to listOf("youtube", "netflix", "spotify", "twitch", "video", "player", "prime video", "disney", "hulu", "vlc", "music"),
        GAMES to listOf("game", "puzzle", "chess", "solitaire", "runner", "racing", "minecraft", "clash", "battle", "arcade"),
        MESSAGING to listOf("whatsapp", "messenger", "telegram", "signal", "viber", "kakaotalk", "line ", "chat", "messages", "discord"),
        NEWS to listOf("news", "times", "post", "journal", "daily", "headline", "feed", "magazine"),
        SHOPPING to listOf("shop", "store", "market", "amazon", "ebay", "aliexpress", "mall", "buy"),
        PRODUCTIVITY to listOf("mail", "calendar", "drive", "docs", "sheets", "notes", "notion", "todo", "task", "office", "excel", "word", "slack", "zoom", "teams"),
        TOOLS to listOf("settings", "manager", "calculator", "scanner", "cleaner", "file", "browser", "vpn", "clock", "weather", "torch", "flashlight")
    )

    private val gameByExt = setOf("puzzle", "game", "games")

    /**
     * @param packageName Android package id
     * @param label human app label (may equal packageName when unknown)
     * @return one of [defaultOrder] categories (never null)
     */
    fun classify(packageName: String, label: String): String {
        val pkg = packageName.lowercase()
        val lbl = " ${label.lowercase().trim()} "

        // 1. Exact-ish package rules.
        for ((category, rules) in packageRules) {
            for (raw in rules) {
                val rule = raw.lowercase()
                if (pkg == rule || pkg.startsWith("$rule.")) return category
            }
        }

        // 2. Package word heuristics ("com.rovio愤怒" style segments).
        val segments = pkg.split('.', '_', '-')
        for ((category, words) in labelRules) {
            for (w in words) {
                val key = w.trim()
                if (key.isEmpty()) continue
                if (segments.any { it == key || it.startsWith(key) }) return category
            }
        }

        // 3. Label word heuristics.
        for ((category, words) in labelRules) {
            for (w in words) {
                if (lbl.contains(w)) return category
            }
        }

        // 4. Weak signals.
        if (segments.any { it in gameByExt }) return GAMES
        if (pkg.startsWith("com.android") || pkg.startsWith("com.miui") ||
            pkg.startsWith("com.google.android") || pkg.startsWith("com.samsung") ||
            pkg.startsWith("com.qualcomm") || pkg.startsWith("com.mediatek")
        ) return TOOLS

        return UNSPECIFIED
    }
}
