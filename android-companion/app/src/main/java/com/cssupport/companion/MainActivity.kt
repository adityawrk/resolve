package com.cssupport.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var modelChip: Chip
    private lateinit var appChipGroup: ChipGroup
    private lateinit var issueInput: TextInputEditText
    private lateinit var descriptionInput: TextInputEditText
    private lateinit var orderIdInput: TextInputEditText
    private lateinit var outcomeChipGroup: ChipGroup
    private lateinit var thumbnailScroll: HorizontalScrollView
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var mediaCountText: TextView
    private lateinit var btnResolveIt: MaterialButton

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
        refreshModelChip()
    }

    override fun onResume() {
        super.onResume()
        refreshModelChip()
    }

    private fun bindViews() {
        greetingText = findViewById(R.id.greetingText)
        modelChip = findViewById(R.id.modelChip)
        appChipGroup = findViewById(R.id.appChipGroup)
        issueInput = findViewById(R.id.issueInput)
        descriptionInput = findViewById(R.id.descriptionInput)
        orderIdInput = findViewById(R.id.orderIdInput)
        outcomeChipGroup = findViewById(R.id.outcomeChipGroup)
        thumbnailScroll = findViewById(R.id.thumbnailScroll)
        thumbnailContainer = findViewById(R.id.thumbnailContainer)
        mediaCountText = findViewById(R.id.mediaCountText)
        btnResolveIt = findViewById(R.id.btnResolveIt)
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

        // Resolve It!
        btnResolveIt.setOnClickListener { resolveIt() }
    }

    private fun refreshModelChip() {
        val config = authManager.loadLLMConfig()
        if (config != null) {
            modelChip.text = config.model
        } else {
            modelChip.text = getString(R.string.model_chip_label)
        }
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

        // Build thumbnail previews.
        thumbnailContainer.removeAllViews()
        for (uri in selectedMediaUris) {
            val thumbnail = ImageView(this).apply {
                val sizePx = resources.getDimensionPixelSize(R.dimen.thumbnail_size)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.element_gap)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
                clipToOutline = true

                // Try to load image thumbnail.
                try {
                    setImageURI(uri)
                } catch (_: Exception) {
                    setImageResource(R.drawable.ic_attach_file)
                    scaleType = ImageView.ScaleType.CENTER
                }

                // Long press to remove.
                setOnLongClickListener {
                    selectedMediaUris.remove(uri)
                    updateAttachmentUI()
                    true
                }
            }
            thumbnailContainer.addView(thumbnail)
        }
    }

    private fun resolveIt() {
        // Validate.
        val issue = issueInput.text?.toString()?.trim().orEmpty()
        if (issue.length < 3) {
            Toast.makeText(this, "Describe your issue", Toast.LENGTH_SHORT).show()
            return
        }

        val config = authManager.loadLLMConfig()
        if (config == null) {
            Toast.makeText(this, "Configure your API key in Settings first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        if (!SupportAccessibilityService.isRunning()) {
            Toast.makeText(this, "Enable the Accessibility Service first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }

        // Gather form data.
        val targetApp = getSelectedApp()
        val description = descriptionInput.text?.toString()?.trim().orEmpty()
        val orderId = orderIdInput.text?.toString()?.trim().orEmpty()
        val desiredOutcome = getSelectedOutcome()

        val fullIssue = buildString {
            append(issue)
            if (description.isNotBlank()) {
                append("\n\n")
                append(description)
            }
        }

        val caseId = UUID.randomUUID().toString().take(8)

        // Start the agent service in standalone (on-device) mode.
        CompanionAgentService.startLocal(
            context = this,
            caseId = caseId,
            issue = fullIssue,
            desiredOutcome = desiredOutcome,
            orderId = orderId.ifBlank { null },
            targetPlatform = mapAppToPackage(targetApp),
            hasAttachments = selectedMediaUris.isNotEmpty(),
        )

        // Navigate to monitor.
        val monitorIntent = Intent(this, MonitorActivity::class.java).apply {
            putExtra(MonitorActivity.EXTRA_TARGET_APP, targetApp)
            putExtra(MonitorActivity.EXTRA_OUTCOME, desiredOutcome)
            putExtra(MonitorActivity.EXTRA_START_TIME, System.currentTimeMillis())
        }
        startActivity(monitorIntent)
    }

    private fun getSelectedApp(): String {
        val checkedId = appChipGroup.checkedChipId
        return when (checkedId) {
            R.id.chipAmazon -> "Amazon"
            R.id.chipSwiggy -> "Swiggy"
            R.id.chipUber -> "Uber"
            R.id.chipWhatsApp -> "WhatsApp"
            R.id.chipOtherApp -> "Other"
            else -> "Amazon"
        }
    }

    private fun getSelectedOutcome(): String {
        val checkedId = outcomeChipGroup.checkedChipId
        return when (checkedId) {
            R.id.chipRefund -> "Full refund"
            R.id.chipReplacement -> "Replacement"
            R.id.chipInfo -> "Information / status update"
            R.id.chipCancellation -> "Cancellation"
            R.id.chipOutcomeOther -> "Resolve the issue"
            else -> "Full refund"
        }
    }

    /**
     * Map a friendly app name to an Android package name.
     * This is a best-effort mapping for common apps.
     */
    private fun mapAppToPackage(appName: String): String {
        return when (appName) {
            "Amazon" -> "in.amazon.mShop.android.shopping"
            "Swiggy" -> "in.swiggy.android"
            "Uber" -> "com.ubercab"
            "WhatsApp" -> "com.whatsapp"
            else -> appName.lowercase().replace(" ", ".")
        }
    }

    private fun displayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0 || !cursor.moveToFirst()) null
                else cursor.getString(idx)
            }
    }
}
