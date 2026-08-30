package com.andrewtheguy.duocb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andrewtheguy.duocb.IdentityCardInfo
import com.andrewtheguy.duocb.SessionController
import com.andrewtheguy.duocb.TrustedPeer

/**
 * The trusted-device picker: every device whose card this one holds. Tap one
 * to connect to it — the person on that device taps this one, and the core
 * works out which of the two listens.
 *
 * The list is purely local — these are stored cards, not discovered devices —
 * so there is no refresh, no "last seen", and no online/offline verdict.
 * Starting the session is the liveness check, exactly as on the desktop.
 */
@Composable
fun ConnectPickerScreen(controller: SessionController, navigate: (Step) -> Unit) {
    val context = LocalContext.current
    var showImport by rememberSaveable { mutableStateOf(false) }
    var importDraft by rememberSaveable { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<TrustedPeer?>(null) }
    // The pasted card decoded, or null while it is empty or invalid. Decoding
    // verifies the signature, so it is done once per edit, not per frame.
    val preview = remember(importDraft) {
        importDraft.trim().takeIf { it.isNotEmpty() }?.let { IdentityCardInfo.parse(it) }
    }

    ScreenScaffold(title = "Trusted devices", onBack = { navigate(Step.HUB) }) {
        Section(
            header = "Trusted devices",
            footer = "Tap a device to connect to it, and tap this one over there — the order does not " +
                "matter, and whoever is ready first waits. Use the bin to stop trusting a device. An " +
                "expired card can no longer pair — ask that device for a fresh one, or trade cards again.",
        ) {
            if (controller.peers.isEmpty()) {
                Footnote("No trusted devices yet. Trade cards with your other device, or paste its card below.")
            }
            controller.peers.forEach { peer ->
                PeerRow(
                    peer,
                    onConnect = { controller.connect(peer) },
                    onRemove = { pendingRemoval = peer },
                )
            }
        }

        // Paste-import, the copy-and-paste half of trust bootstrapping — for
        // when the two devices *do* have a way to move text between them and a
        // PIN session would be ceremony.
        Section(
            footer = if (showImport) {
                "Check the fingerprint above matches the one shown on that device before trusting it — " +
                    "that comparison is the only thing standing between you and trusting the wrong device."
            } else {
                null
            },
        ) {
            if (showImport) {
                OutlinedTextField(
                    value = importDraft,
                    onValueChange = {
                        importDraft = it
                        val trimmed = it.trim()
                        // Desktop parity: an entry that holds something but does
                        // not verify says why, rather than leaving a disabled
                        // Trust button that reads as an ignored paste.
                        importError = if (trimmed.isNotEmpty() && IdentityCardInfo.parse(trimmed) == null) {
                            SessionController.validateIdentityCard(trimmed) ?: "invalid identity card"
                        } else {
                            null
                        }
                    },
                    label = { Text("Paste the other device's card") },
                    minLines = 2,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("card_field"),
                )
                val error = importError
                if (error != null) {
                    Footnote(error, color = MaterialTheme.colorScheme.error)
                } else if (preview != null) {
                    LabeledValue("Device", preview.name)
                    LabeledValue("Fingerprint", "", mono = false)
                    FingerprintText(preview.fingerprint)
                    Footnote(preview.expiryText, color = if (preview.expired) Orange else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RowButton("Paste", icon = Icons.Filled.ContentPaste) {
                    val pasted = Clipboard.read(context)?.trim()
                    if (pasted.isNullOrEmpty()) importError = "The clipboard is empty" else importDraft = pasted
                }
                // An expired card is refused by the import itself, and the
                // expiry line above says so.
                RowButton("Trust this device", enabled = preview != null, testTag = "trust_pasted") {
                    val err = controller.importPeerCard(importDraft)
                    if (err != null) {
                        importError = err
                    } else {
                        importDraft = ""
                        showImport = false
                    }
                }
                RowButton("Cancel") {
                    importDraft = ""
                    importError = null
                    showImport = false
                }
            } else {
                RowButton("Paste a card", icon = Icons.Filled.ContentPaste, testTag = "paste_card") { showImport = true }
            }
        }
    }

    pendingRemoval?.let { peer ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Stop trusting ${peer.info.name}?") },
            text = {
                Text(
                    "This device will no longer connect to it. The other device keeps your card until it " +
                        "removes it too. To pair again you have to trade cards.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.removePeer(peer.id)
                    pendingRemoval = null
                }) { Text("Stop trusting", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PeerRow(peer: TrustedPeer, onConnect: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // A lapsed card cannot pair either way round, and the hosting half
        // fails silently at that — it simply publishes no record. The row
        // marks the expiry and tapping it would only start a session that can
        // never connect, so it is disabled; removing stays available.
        TextButton(
            onClick = onConnect,
            enabled = !peer.info.expired,
            modifier = Modifier.weight(1f).testTag("peer_${peer.info.name}"),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(peer.info.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(
                    peer.info.expiryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peer.info.expired) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Connect", style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Stop trusting ${peer.info.name}")
        }
    }
}
