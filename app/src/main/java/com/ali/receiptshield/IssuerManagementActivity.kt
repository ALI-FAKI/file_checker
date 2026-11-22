
package com.ali.receiptshield

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ali.receiptshield.crypto.IssuerKeyExporter
import com.ali.receiptshield.crypto.IssuerKeyImporter
import com.ali.receiptshield.crypto.ReceiptKeyManager
import com.ali.receiptshield.crypto.TrustedIssuerRegistry
import com.ali.receiptshield.data.StampRepository

class IssuerManagementActivity : AppCompatActivity() {

    private lateinit var registry: TrustedIssuerRegistry
    private lateinit var repository: StampRepository
    private lateinit var exporter: IssuerKeyExporter
    private lateinit var importer: IssuerKeyImporter

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registry = TrustedIssuerRegistry(this)
        repository = StampRepository(this)
        exporter = IssuerKeyExporter(ReceiptKeyManager())
        importer = IssuerKeyImporter(this)

        buildInterface()
    }

    private fun buildInterface() {

        val scrollView = ScrollView(this)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }

        scrollView.addView(container)

        setContentView(scrollView)

        addText(
            "ReceiptShield Issuer Management",
            24f,
            Color.BLACK,
            true
        )

        addText(
            "Manage your issuer identity and trusted public keys.",
            14f,
            Color.DKGRAY
        )

        addButton("View My Issuer Public Key") {
            showOwnPublicKey()
        }

        addButton("Export My Registration Package") {
            exportMyPackage()
        }

        addButton("Import Trusted Issuer") {
            showImportDialog()
        }

        addButton("View Trusted Issuers") {
            showTrustedIssuers()
        }

        addButton("Refresh Issuer Information") {
            refreshScreen()
        }

        addButton("Back") {
            finish()
        }

        refreshScreen()
    }

    private fun refreshScreen() {

        val issuerId = repository.getIssuerId()

        addText(
            "Active issuer: $issuerId",
            16f,
            Color.rgb(230, 95, 35),
            true
        )
    }

    private fun showOwnPublicKey() {

        try {
            val keyManager = ReceiptKeyManager()

            val publicKey = keyManager.getPublicKeyBase64()
            val fingerprint = exporter.getPublicKeyFingerprint()

            val message = """
                Active issuer:
                ${repository.getIssuerId()}

                Public key:
                $publicKey

                SHA-256 fingerprint:
                $fingerprint

                Keep the private key on this device.
                Share only the public key and fingerprint.
            """.trimIndent()

            showCopyDialog(
                "My Public Key",
                message
            )

        } catch (e: Exception) {
            showError(e.message)
        }
    }

    private fun exportMyPackage() {

        try {
            val issuerId = repository.getIssuerId()

            val registrationPackage =
                exporter.exportRegistrationPackage(issuerId)

            showCopyDialog(
                "Issuer Registration Package",
                registrationPackage
            )

        } catch (e: Exception) {
            showError(e.message)
        }
    }

    private fun showImportDialog() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }

        val packageInput = EditText(this).apply {
            hint = "Paste registration package JSON"
            minLines = 4
            gravity = android.view.Gravity.TOP
        }

        val fingerprintInput = EditText(this).apply {
            hint = "Independently confirmed SHA-256 fingerprint"
            minLines = 2
            gravity = android.view.Gravity.TOP
        }

        layout.addView(packageInput)
        layout.addView(fingerprintInput)

        AlertDialog.Builder(this)
            .setTitle("Import Trusted Issuer")
            .setMessage(
                "Paste the issuer registration package and " +
                    "enter its independently confirmed fingerprint."
            )
            .setView(layout)
            .setPositiveButton("Validate and Register") { _, _ ->

                val packageJson =
                    packageInput.text.toString().trim()

                val fingerprint =
                    fingerprintInput.text.toString().trim()

                if (
                    packageJson.isBlank() ||
                    fingerprint.isBlank()
                ) {
                    showError(
                        "Both fields are required."
                    )
                    return@setPositiveButton
                }

                val result = importer.importRegistrationPackage(
                    packageJson,
                    fingerprint
                )

                AlertDialog.Builder(this)
                    .setTitle(
                        if (result.success) {
                            "Registration Successful"
                        } else {
                            "Registration Failed"
                        }
                    )
                    .setMessage(result.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTrustedIssuers() {

        val issuers = registry.getAllTrustedIssuers()

        if (issuers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Trusted Issuers")
                .setMessage(
                    "No trusted issuers have been registered."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val options = issuers.map {
            "${it.issuerId}\n${it.fingerprint.take(20)}..."
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Trusted Issuers")
            .setItems(options) { _, index ->

                val issuer = issuers[index]

                AlertDialog.Builder(this)
                    .setTitle(issuer.issuerId)
                    .setMessage(
                        """
                        Issuer ID:
                        ${issuer.issuerId}

                        SHA-256 fingerprint:
                        ${issuer.fingerprint}

                        Registered:
                        ${java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date(issuer.registeredAt))}
                        """.trimIndent()
                    )
                    .setPositiveButton("Remove Issuer") { _, _ ->

                        AlertDialog.Builder(this)
                            .setTitle("Confirm Removal")
                            .setMessage(
                                "Remove ${issuer.issuerId} from this device's trusted issuer registry?"
                            )
                            .setPositiveButton("Remove") { _, _ ->

                                val removed = registry.removeIssuer(
                                    issuer.issuerId,
                                    issuer.fingerprint
                                )

                                Toast.makeText(
                                    this,
                                    if (removed) {
                                        "Trusted issuer removed."
                                    } else {
                                        "Unable to remove issuer."
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCopyDialog(
        title: String,
        content: String
    ) {

        val textView = TextView(this).apply {
            text = content
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(24, 16, 24, 16)
            textIsSelectable = true
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(
                ScrollView(this).apply {
                    addView(textView)
                }
            )
            .setPositiveButton("Copy") { _, _ ->

                val clipboard =
                    getSystemService(CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager

                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        title,
                        content
                    )
                )

                Toast.makeText(
                    this,
                    "Copied to clipboard",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) {

        val textView = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)

            if (bold) {
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
                )
            }

            setPadding(0, 16, 0, 16)
        }

        container.addView(
            textView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun addButton(
        label: String,
        action: () -> Unit
    ) {

        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                action()
            }
        }

        container.addView(
            button,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun showError(message: String?) {
        Toast.makeText(
            this,
            message ?: "An unexpected error occurred.",
            Toast.LENGTH_LONG
        ).show()
    }
}
