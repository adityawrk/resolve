package com.cssupport.companion

import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

/**
 * Main issue submission screen.
 *
 * Flow:
 * 1. User selects target app, describes issue, attaches evidence, picks desired outcome.
 * 2. Taps "Resolve It".
 * 3. App launches the target app, starts the agent service, and navigates to MonitorActivity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager

    // Views
    private lateinit var greetingText: TextView
    private lateinit var appNameLayout: TextInputLayout
    private lateinit var appNameInput: TextInputEditText
    private lateinit var issueInput: TextInputEditText
    private lateinit var orderIdInput: TextInputEditText
    private lateinit var outcomeChipGroup: ChipGroup
    private lateinit var thumbnailScroll: HorizontalScrollView
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var mediaCountText: TextView
    private lateinit var btnResolveIt: MaterialButton
    private lateinit var otherOutcomeLayout: TextInputLayout
    private lateinit var otherOutcomeInput: TextInputEditText

    private val selectedMediaUris = mutableListOf<Uri>()

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedMediaUris.addAll(uris)
            updateAttachmentUI()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedMediaUris.addAll(uris)
            updateAttachmentUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        authManager = AuthManager(this)

        bindViews()
        setupListeners()

        // Time-aware greeting.
        greetingText.text = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 0..11 -> getString(R.string.greeting_morning)
            in 12..17 -> getString(R.string.greeting_afternoon)
            else -> getString(R.string.greeting_evening)
        }
    }

    private fun bindViews() {
        greetingText = findViewById(R.id.greetingText)
        appNameLayout = findViewById(R.id.appNameLayout)
        appNameInput = findViewById(R.id.appNameInput)
        issueInput = findViewById(R.id.issueInput)
        orderIdInput = findViewById(R.id.orderIdInput)
        outcomeChipGroup = findViewById(R.id.outcomeChipGroup)
        thumbnailScroll = findViewById(R.id.thumbnailScroll)
        thumbnailContainer = findViewById(R.id.thumbnailContainer)
        mediaCountText = findViewById(R.id.mediaCountText)
        btnResolveIt = findViewById(R.id.btnResolveIt)
        otherOutcomeLayout = findViewById(R.id.otherOutcomeLayout)
        otherOutcomeInput = findViewById(R.id.otherOutcomeInput)
    }

    private fun setupListeners() {
        // Settings button.
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Attachment buttons.
        findViewById<MaterialButton>(R.id.btnAddPhoto).setOnClickListener {
            photoPickerLauncher.launch(arrayOf("image/*"))
        }
        findViewById<MaterialButton>(R.id.btnAddFile).setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        // Show/hide "Other" outcome text input when chip selection changes.
        outcomeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val isOtherSelected = checkedIds.contains(R.id.chipOutcomeOther)
            otherOutcomeLayout.visibility = if (isOtherSelected) View.VISIBLE else View.GONE
            if (!isOtherSelected) {
                otherOutcomeLayout.error = null
            }
        }

        // Resolve It!
        btnResolveIt.setOnClickListener { resolveIt() }
    }

    private fun updateAttachmentUI() {
        if (selectedMediaUris.isEmpty()) {
            thumbnailScroll.visibility = View.GONE
            mediaCountText.visibility = View.GONE
            return
        }

        thumbnailScroll.visibility = View.VISIBLE
        mediaCountText.visibility = View.VISIBLE
        mediaCountText.text = "${selectedMediaUris.size} file${if (selectedMediaUris.size > 1) "s" else ""} attached"

        // Build thumbnail previews with remove buttons.
        thumbnailContainer.removeAllViews()
        val removeBtnSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics,
        ).toInt()

        for (uri in selectedMediaUris) {
            val sizePx = resources.getDimensionPixelSize(R.dimen.thumbnail_size)

            val wrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.element_gap)
                }
            }

            val thumbnail = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
                clipToOutline = true

                try {
                    setImageURI(uri)
                } catch (_: Exception) {
                    setImageResource(R.drawable.ic_attach_file)
                    scaleType = ImageView.ScaleType.CENTER
                }
            }

            val removeBtn = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(removeBtnSizePx, removeBtnSizePx, Gravity.TOP or Gravity.END)
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.WHITE)
                setOnClickListener {
                    selectedMediaUris.remove(uri)
                    updateAttachmentUI()
                }
            }

            wrapper.addView(thumbnail)
            wrapper.addView(removeBtn)
            thumbnailContainer.addView(wrapper)
        }
    }

    private fun resolveIt() {
        try {
            // Clear previous validation errors.
            val issueTitleLayout = findViewById<TextInputLayout>(R.id.issueTitleLayout)
            issueTitleLayout.error = null
            appNameLayout.error = null
            otherOutcomeLayout.error = null

            // Validate app name.
            val targetApp = appNameInput.text?.toString()?.trim().orEmpty()
            if (targetApp.isEmpty()) {
                appNameLayout.error = getString(R.string.validation_app_name)
                appNameInput.requestFocus()
                return
            }

            // Validate issue title.
            val issue = issueInput.text?.toString()?.trim().orEmpty()
            if (issue.length < 3) {
                issueTitleLayout.error = getString(R.string.validation_issue_too_short)
                issueInput.requestFocus()
                return
            }

            // Validate "Other" outcome text when that chip is selected.
            if (outcomeChipGroup.checkedChipId == R.id.chipOutcomeOther) {
                val otherText = otherOutcomeInput.text?.toString()?.trim().orEmpty()
                if (otherText.isBlank()) {
                    otherOutcomeLayout.error = getString(R.string.validation_other_outcome)
                    otherOutcomeInput.requestFocus()
                    return
                }
            }

            // Validate API key configuration.
            // Use var so the service can update it after an OAuth token refresh.
            var config = authManager.loadLLMConfig()
            if (config == null) {
                Snackbar.make(
                    btnResolveIt,
                    getString(R.string.validation_no_api_key),
                    Snackbar.LENGTH_LONG,
                ).setAction(R.string.settings_title) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }.show()
                return
            }

            // Validate accessibility service.
            if (!SupportAccessibilityService.isRunning()) {
                Snackbar.make(
                    btnResolveIt,
                    getString(R.string.validation_no_accessibility),
                    Snackbar.LENGTH_LONG,
                ).setAction(R.string.onboarding_allow_access) {
                    startActivity(Intent(this, OnboardingActivity::class.java))
                }.show()
                return
            }

            // Check network connectivity before starting the agent.
            if (!isNetworkAvailable()) {
                Snackbar.make(
                    btnResolveIt,
                    getString(R.string.validation_no_internet),
                    Snackbar.LENGTH_LONG,
                ).show()
                return
            }

            // Gather form data.
            val orderId = orderIdInput.text?.toString()?.trim().orEmpty()
            val desiredOutcome = getSelectedOutcome()
            val fullIssue = issue

            val caseId = UUID.randomUUID().toString().take(8)

            val targetPackage = mapAppToPackage(targetApp)
            val startTime = System.currentTimeMillis()

            // Start the agent service in standalone (on-device) mode.
            try {
                CompanionAgentService.start(
                    context = this,
                    caseId = caseId,
                    issue = fullIssue,
                    desiredOutcome = desiredOutcome,
                    orderId = orderId.ifBlank { null },
                    targetPlatform = targetPackage,
                    hasAttachments = selectedMediaUris.isNotEmpty(),
                )
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to start agent service", e)
                Snackbar.make(
                    btnResolveIt,
                    "Something went wrong. Please try again.",
                    Snackbar.LENGTH_LONG,
                ).show()
                return
            }

            // Navigate to MonitorActivity first so the user sees live progress.
            startActivity(Intent(this, MonitorActivity::class.java).apply {
                putExtra(MonitorActivity.EXTRA_TARGET_APP, targetApp)
                putExtra(MonitorActivity.EXTRA_OUTCOME, desiredOutcome)
                putExtra(MonitorActivity.EXTRA_START_TIME, startTime)
            })

            // Launch the target app so the agent can see it.
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(launchIntent)
                } catch (e: Exception) {
                    // Target app couldn't be launched -- agent will wait for it.
                    android.util.Log.w("MainActivity", "Could not launch $targetPackage", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "resolveIt crashed", e)
            Snackbar.make(btnResolveIt, "Something went wrong. Please try again.", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun getSelectedOutcome(): String {
        val checkedId = outcomeChipGroup.checkedChipId
        return when (checkedId) {
            R.id.chipRefund -> "Full refund"
            R.id.chipReplacement -> "Replacement"
            R.id.chipInfo -> "Information / status update"
            R.id.chipCancellation -> "Cancellation"
            R.id.chipOutcomeOther -> {
                val customText = otherOutcomeInput.text?.toString()?.trim().orEmpty()
                customText.ifBlank { "Resolve the issue" }
            }
            else -> "Full refund"
        }
    }

    /**
     * Map a friendly app name to an Android package name.
     * Searches installed apps dynamically — works for ANY app on the device.
     */
    private fun mapAppToPackage(appName: String): String {
        return findPackageByLabel(appName)
            ?: appName.lowercase().replace(" ", ".")
    }

    /**
     * Search installed apps for one whose label best matches [query].
     * Uses a multi-pass strategy: exact → normalized → contains → word overlap.
     * Strips punctuation and special chars for robust matching
     * (e.g. "Dominos" matches "Domino's Pizza").
     */
    private fun findPackageByLabel(query: String): String? {
        val apps = packageManager.getInstalledApplications(0)
        val normalizedQuery = normalize(query)

        // Pass 1: exact label match (case-insensitive).
        for (app in apps) {
            val label = packageManager.getApplicationLabel(app).toString()
            if (label.equals(query, ignoreCase = true)) {
                return app.packageName
            }
        }

        // Pass 2: normalized match (strip punctuation, whitespace).
        for (app in apps) {
            val label = packageManager.getApplicationLabel(app).toString()
            if (normalize(label) == normalizedQuery) {
                return app.packageName
            }
        }

        // Pass 3: one contains the other (normalized).
        for (app in apps) {
            val label = normalize(packageManager.getApplicationLabel(app).toString())
            if (label.contains(normalizedQuery) || normalizedQuery.contains(label)) {
                // Skip very short labels to avoid false positives (e.g. "Go" matching "Google").
                if (label.length >= 3) {
                    return app.packageName
                }
            }
        }

        // Pass 4: word overlap — query words appear in label words.
        val queryWords = normalizedQuery.split(" ").filter { it.length >= 3 }.toSet()
        if (queryWords.isNotEmpty()) {
            var bestMatch: String? = null
            var bestOverlap = 0
            for (app in apps) {
                val label = normalize(packageManager.getApplicationLabel(app).toString())
                val labelWords = label.split(" ").filter { it.length >= 3 }.toSet()
                val overlap = queryWords.intersect(labelWords).size
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    bestMatch = app.packageName
                }
            }
            if (bestMatch != null && bestOverlap > 0) return bestMatch
        }

        return null
    }

    /** Normalize a string for fuzzy matching: lowercase, strip punctuation. */
    private fun normalize(s: String): String {
        return s.lowercase()
            .replace(Regex("[''`]"), "")   // smart quotes, apostrophes
            .replace(Regex("[^a-z0-9 ]"), " ")  // non-alphanumeric → space
            .replace(Regex("\\s+"), " ")   // collapse whitespace
            .trim()
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return true
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE permission missing — assume network is available.
            true
        }
    }
}
