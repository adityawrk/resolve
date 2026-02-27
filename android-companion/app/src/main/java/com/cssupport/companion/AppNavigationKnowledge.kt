package com.cssupport.companion

/**
 * App-specific navigation knowledge base.
 *
 * Provides human-curated, step-by-step navigation paths for known apps.
 * Used by the agent loop to inject app-specific hints into the system prompt,
 * dramatically improving navigation accuracy for supported apps.
 *
 * Each [AppNavProfile] describes:
 * - **package name** for matching
 * - **supportPath**: ordered steps from home screen to customer support chat
 * - **pitfalls**: common wrong turns the LLM must avoid
 * - **profileLocation**: where the profile/account icon is (bottom tab vs top-right)
 *
 * Sources: Manual app analysis + UI mapping research (Feb 2026).
 */
object AppNavigationKnowledge {

    /**
     * Look up navigation hints for a package name.
     * Returns null for unknown apps (the generic system prompt handles those).
     */
    fun lookup(packageName: String): AppNavProfile? {
        val normalized = packageName.lowercase().trim()
        return profiles[normalized]
            ?: profiles.entries.firstOrNull { normalized.contains(it.key) }?.value
    }

    /**
     * Look up by user-provided app name (fuzzy match).
     */
    fun lookupByName(appName: String): AppNavProfile? {
        val normalized = appName.lowercase().trim()
        return nameIndex.entries.firstOrNull { (key, _) ->
            normalized.contains(key) || key.contains(normalized)
        }?.value
    }

    private val profiles = mapOf(
        // ── Domino's India ──────────────────────────────────────────────
        "com.dominos" to AppNavProfile(
            appName = "Domino's",
            supportPath = listOf(
                "Look for unlabeled icon-btn elements in the TOP BAR marked (far-right) — the RIGHTMOST one opens the sidebar menu. Do NOT click wallet/₹/cash icons.",
                "In the sidebar, tap \"Order History\"",
                "Tap the specific order card (e.g., Order #28)",
                "On the Order Details page, tap \"Help\" at the TOP-RIGHT corner",
                "The Virtual Customer Assistant chat opens — tap \"Queries & feedback\" or type your issue",
            ),
            pitfalls = listOf(
                "Bottom bar only has \"Menu\" and \"Offers\" — NEITHER leads to support or profile",
                "\"Menu\" tab shows food categories (Pizza, Sides, etc.) — NOT navigation to support",
                "Do NOT click food items, deal images, category images, or \"Reorder\" buttons",
                "The sidebar is accessed via the RIGHTMOST unlabeled icon in the top bar, NOT a bottom tab",
                "₹ CASH / Wallet icon is NOT the sidebar — it opens the wallet. IGNORE it.",
                "\"Rate your last order\" section on home screen is NOT the path to support",
                "If on a combo/menu page with location set, look for a hamburger/sidebar icon in the top bar",
            ),
            profileLocation = "Top-right: RIGHTMOST unlabeled icon opens sidebar with Order History, Contact Us, etc. (NOT the wallet/₹ icon)",
            orderHistoryLocation = "Sidebar icon (rightmost top-right) → Order History",
        ),

        // ── Swiggy ──────────────────────────────────────────────────────
        "in.swiggy.android" to AppNavProfile(
            appName = "Swiggy",
            supportPath = listOf(
                "Tap \"ACCOUNT\" in the BOTTOM navigation bar (rightmost tab)",
                "Tap \"My Orders\" or \"Your Orders\" in the account menu",
                "Select the specific order with the issue",
                "Tap \"Help\" on the order details page",
                "Choose the relevant issue category",
                "Tap \"Chat with Us\" for live chat support",
            ),
            pitfalls = listOf(
                "Bottom bar has SWIGGY, SEARCH, CART, ACCOUNT — tap ACCOUNT (rightmost)",
                "Profile/Account is NOT at the top-right — it is the ACCOUNT tab in the BOTTOM bar",
                "If the app shows a LOGIN screen, the user is not logged in — call request_human_review immediately",
                "Do NOT click \"Send Feedback\" — it opens app feedback, not customer support",
                "\"SWIGGY\" tab (leftmost in bottom bar) shows the food home page — not support",
            ),
            profileLocation = "Bottom bar: \"ACCOUNT\" tab (rightmost)",
            orderHistoryLocation = "ACCOUNT (bottom bar) → My Orders",
        ),

        // ── Zomato ──────────────────────────────────────────────────────
        "com.application.zomato" to AppNavProfile(
            appName = "Zomato",
            supportPath = listOf(
                "Tap the profile icon at the TOP-RIGHT corner of the screen",
                "Scroll down and tap \"Help\" or \"Support\"",
                "Select \"Orders\" to get help for a specific order",
                "Select the relevant order",
                "Tap \"Chat with Us\" at the bottom of help articles",
            ),
            pitfalls = listOf(
                "Bottom bar (Delivery, Dining, Reorder) are SERVICE tabs — not profile/help",
                "\"Reorder\" shows past orders but does NOT lead to support",
                "Profile is a small icon at TOP-RIGHT, not a bottom tab",
            ),
            profileLocation = "Top-right corner: profile/person icon",
            orderHistoryLocation = "Profile icon (top-right) → Help → Orders",
        ),

        // ── Amazon India ────────────────────────────────────────────────
        "in.amazon.mshop.android.shopping" to AppNavProfile(
            appName = "Amazon",
            supportPath = listOf(
                "Tap the \"You\" or person icon in the BOTTOM navigation bar",
                "Tap \"Your Orders\" at the top of the profile screen",
                "Select the specific order with the issue",
                "Tap \"Get help\" or \"Problem with order\"",
                "Select the issue category, then tap \"Chat\" for instant support",
            ),
            pitfalls = listOf(
                "\"Home\" tab shows recommendations/deals — not the path to support",
                "Do NOT use the search bar — it's for product search",
                "The hamburger menu also has \"Your Orders\" but the \"You\" tab is faster",
            ),
            profileLocation = "Bottom bar: \"You\" or person icon",
            orderHistoryLocation = "You (bottom bar) → Your Orders",
        ),

        // ── Flipkart ────────────────────────────────────────────────────
        "com.flipkart.android" to AppNavProfile(
            appName = "Flipkart",
            supportPath = listOf(
                "Tap \"Account\" in the BOTTOM-RIGHT of the navigation bar",
                "Tap \"My Orders\" near the top of the Account section",
                "Select the specific order",
                "Tap \"Need Help\" on the order detail page",
                "Connect to a live agent via chat from the help screen",
            ),
            pitfalls = listOf(
                "\"Home\" and \"Categories\" tabs show products — not support",
                "\"Notifications\" tab is for alerts — not support",
                "Alternative: three-dot menu (top-right) → \"24x7 Customer Care\"",
            ),
            profileLocation = "Bottom bar: \"Account\" tab (rightmost)",
            orderHistoryLocation = "Account (bottom bar) → My Orders",
        ),

        // ── Uber ────────────────────────────────────────────────────────
        "com.ubercab" to AppNavProfile(
            appName = "Uber",
            supportPath = listOf(
                "Tap \"Account\" in the BOTTOM navigation bar (rightmost tab)",
                "Tap \"Help\" in the account menu",
                "Tap \"Help with a trip\"",
                "Select the specific trip",
                "Choose the issue category and follow prompts",
            ),
            pitfalls = listOf(
                "\"Services\" tab shows ride types — not support",
                "\"Activity\" tab shows trip history but go through Account → Help for support",
                "Home screen is for booking rides — not the path to support",
            ),
            profileLocation = "Bottom bar: \"Account\" tab (rightmost)",
            orderHistoryLocation = "Activity (bottom bar) for history, but Account → Help for support",
        ),

        // ── Ola ─────────────────────────────────────────────────────────
        "com.olacabs.customer" to AppNavProfile(
            appName = "Ola",
            supportPath = listOf(
                "Tap the hamburger menu icon (top-left) or profile icon",
                "Tap \"My Rides\" or \"Ride History\"",
                "Select the specific ride",
                "Tap \"Help\" or \"Report Issue\"",
                "Select issue category and describe the problem",
            ),
            pitfalls = listOf(
                "Home screen is for booking rides — not the path to support",
                "Do NOT tap \"Book Now\" or ride type options",
            ),
            profileLocation = "Top-left: hamburger menu or profile icon",
            orderHistoryLocation = "Hamburger menu → My Rides",
        ),
    )

    /** Index by common app names for fuzzy matching. */
    private val nameIndex: Map<String, AppNavProfile> = buildMap {
        put("domino", profiles["com.dominos"]!!)
        put("swiggy", profiles["in.swiggy.android"]!!)
        put("zomato", profiles["com.application.zomato"]!!)
        put("amazon", profiles["in.amazon.mshop.android.shopping"]!!)
        put("flipkart", profiles["com.flipkart.android"]!!)
        put("uber", profiles["com.ubercab"]!!)
        put("ola", profiles["com.olacabs.customer"]!!)
    }
}

/**
 * Navigation profile for a specific app.
 */
data class AppNavProfile(
    val appName: String,
    val supportPath: List<String>,
    val pitfalls: List<String>,
    val profileLocation: String,
    val orderHistoryLocation: String,
) {
    /**
     * Format as a compact instruction block for inclusion in the system prompt.
     */
    fun toPromptBlock(): String = buildString {
        appendLine("## ${appName} Navigation (app-specific)")
        appendLine("Profile/Account: $profileLocation")
        appendLine("Orders: $orderHistoryLocation")
        appendLine("Steps to support:")
        supportPath.forEachIndexed { i, step ->
            appendLine("  ${i + 1}. $step")
        }
        appendLine("AVOID:")
        pitfalls.forEach { appendLine("  - $it") }
    }
}
