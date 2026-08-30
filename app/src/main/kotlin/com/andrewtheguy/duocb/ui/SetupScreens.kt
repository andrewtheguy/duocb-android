package com.andrewtheguy.duocb.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.andrewtheguy.duocb.SessionController

/** Wizard entry: mint a fresh application keypair, or restore an existing one. */
@Composable
fun IdentityChoiceScreen(controller: SessionController, navigate: (Step) -> Unit) {
    ScreenScaffold(title = "duocb") {
        SessionFailureSection(controller)
        ConfigFailureSection(controller)
        Section(
            header = "Set up this device",
            footer = "Every device gets its own identity — there is no shared secret to copy around. " +
                "Devices come to trust each other by trading signed cards, which you do once per pair " +
                "from the hub. Restore is for moving *this* device's identity to a replacement phone; " +
                "the trusted-device list is not restored with it and has to be rebuilt by trading " +
                "cards again.",
        ) {
            // Persist immediately and go straight to naming: the key is always
            // copyable later from settings. Only advance once it is stored.
            RowButton("Create this device's identity", icon = Icons.Filled.Key, testTag = "create_identity") {
                if (controller.setIdentity(SessionController.generateIdentity())) navigate(Step.NAME)
            }
            RowButton("Restore a saved private key", icon = Icons.Filled.ContentPaste, testTag = "restore_identity") {
                navigate(Step.IMPORT_IDENTITY)
            }
        }
        AppVersionFooter()
    }
}

/**
 * Restore a saved private key, with live validation and the resulting
 * fingerprint to confirm against what the peers have on file.
 */
@Composable
fun IdentityImportScreen(controller: SessionController, navigate: (Step) -> Unit) {
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    val trimmed = draft.trim()
    val keyError = if (trimmed.isEmpty()) null else SessionController.validateIdentity(trimmed)

    ScreenScaffold(title = "Restore this device's identity", onBack = { navigate(Step.CHOICE) }) {
        Section(
            footer = "Paste the private key itself (from “Copy private key”), not the fingerprint. " +
                "Your other devices already trust this key, so the fingerprint shown here should " +
                "match what they list for this device.",
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Private key (nsec1…)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("identity_field"),
            )
            if (keyError != null) {
                Footnote(keyError, color = MaterialTheme.colorScheme.error)
            } else if (trimmed.isNotEmpty()) {
                SessionController.identityFingerprint(trimmed)?.let {
                    LabeledValue("Fingerprint", "", mono = false)
                    FingerprintText(it)
                }
            }
            // Read at tap time: a button disabled on an empty clipboard at first
            // render would stay disabled after the user copies the key.
            RowButton("Paste", icon = Icons.Filled.ContentPaste) {
                val pasted = Clipboard.read(context)?.trim()
                if (pasted.isNullOrEmpty()) {
                    pasteError = "The clipboard is empty"
                } else {
                    pasteError = null
                    draft = pasted
                }
            }
            pasteError?.let { Footnote(it, color = Orange) }
        }
        Section(
            footer = "This replaces any identity already on this device, including its trusted-device " +
                "list — those entries name the old key and would be meaningless under the new one.",
        ) {
            RowButton("Use this key", enabled = trimmed.isNotEmpty() && keyError == null, testTag = "use_key") {
                if (controller.setIdentity(trimmed)) navigate(Step.NAME)
            }
            RowButton("Cancel") { navigate(Step.CHOICE) }
        }
    }
}

/**
 * Name this device: a short name plus the permanent suffix, previewed as the
 * identity that goes on the signed card.
 */
@Composable
fun NameDeviceScreen(controller: SessionController, navigate: (Step) -> Unit) {
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(controller.deviceName ?: defaultDeviceName(context)) }
    val trimmed = draft.trim()
    val nameError = if (trimmed.isEmpty()) "enter a name" else SessionController.validateName(trimmed)
    val suffix = controller.suffix

    ScreenScaffold(
        title = "Name this device",
        onBack = if (controller.hasIdentity) ({ navigate(Step.HUB) }) else null,
    ) {
        ConfigFailureSection(controller)
        if (suffix == null) {
            Section {
                WarningLine(
                    "This device's permanent id could not be stored, so it cannot be named. The secret " +
                        "store refused the write — reinstalling the app usually clears it.",
                )
            }
        }
        Section(
            footer = "A short name plus this device's permanent id. Letters, digits, and '-' only " +
                "(max 24 characters).",
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("e.g. phone") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("name_field"),
            )
            if (nameError != null && trimmed.isNotEmpty()) {
                Footnote(nameError, color = Orange)
            } else if (nameError == null && suffix != null) {
                LabeledValue("Card name", SessionController.displayIdentity(trimmed, suffix))
            }
        }
        Section(
            footer = "The name is signed into this device's card, so renaming issues a new one. Devices " +
                "that already trust you keep showing the old name until you trade cards with them again.",
        ) {
            // Advance only once the name *and* the card it mints are committed.
            RowButton(
                if (controller.selfCard == null) "Save name" else "Rename and re-issue card",
                enabled = nameError == null && suffix != null,
                testTag = "save_name",
            ) {
                if (controller.saveName(trimmed)) navigate(Step.HUB)
            }
            if (controller.hasIdentity) {
                RowButton("Cancel") { navigate(Step.HUB) }
            }
        }
    }
}

/**
 * A reasonable default from the device name: lowercased, non-alphanumerics
 * collapsed to single dashes (e.g. "Bob's Pixel" → "bob-s-pixel"). Falls back
 * to a fixed name when nothing survives normalization.
 */
private fun defaultDeviceName(context: Context): String {
    val raw = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
    val collapsed = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
    val name = collapsed.split('-').filter { it.isNotEmpty() }.joinToString("-")
    return if (name.isEmpty()) "phone" else name.take(24)
}

/** Monospace body text, for identities and cards. */
@Composable
fun MonoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
}
