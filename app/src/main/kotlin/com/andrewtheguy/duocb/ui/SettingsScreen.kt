package com.andrewtheguy.duocb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.andrewtheguy.duocb.SessionController
import com.andrewtheguy.duocb.SignalChannel

/**
 * Settings: the signaling channel, and the destructive identity actions.
 *
 * The channel is the desktop's `--lan-only` / `--nostr-only`, which that build
 * fixes at launch so both flows always agree. A phone has no command line, so
 * it is a setting read when a session starts — a running session never changes
 * channel underneath itself, which is the same guarantee in practice.
 */
@Composable
fun SettingsScreen(controller: SessionController, navigate: (Step) -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    var revealPrivateKey by remember { mutableStateOf(false) }

    // Hide the key before the app leaves the foreground: the recents screen
    // shows a snapshot of the window, and a revealed private key would be in it.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { revealPrivateKey = false }

    ScreenScaffold(title = "Settings", onBack = { navigate(Step.HUB) }) {
        Section(
            header = "How devices find each other",
            footer = controller.channel.note + "\n\nThis applies to trading cards and to clipboard sessions " +
                "alike, and takes effect on the next connection. Both devices must be set to a channel they share.",
        ) {
            SignalChannel.entries.forEach { channel ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = controller.channel == channel, onClick = { controller.setChannel(channel) })
                        .padding(vertical = 6.dp)
                        .testTag("channel_${channel.wire}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = controller.channel == channel, onClick = null)
                    Text(channel.title, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        controller.identitySecret?.let { nsec ->
            Section(
                header = "Private key",
                footer = "Save this somewhere safe to restore *this* device's identity onto a replacement " +
                    "phone — the trusted-device list is not part of it and has to be rebuilt by trading " +
                    "cards. Anyone holding this key can impersonate this device to everyone that trusts it.",
            ) {
                if (revealPrivateKey) {
                    Text(nsec, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 4.dp))
                    CopyButton(nsec, title = "Copy private key", sensitive = true)
                    RowButton("Hide") { revealPrivateKey = false }
                } else {
                    RowButton("Show private key", testTag = "show_key") { revealPrivateKey = true }
                }
            }
        }

        Section {
            RowButton("Reset identity…", destructive = true, testTag = "reset_identity") { confirmReset = true }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Start over with a new identity?") },
            text = {
                Column {
                    Text(
                        "This device gets a brand-new key, loses its name and card, and forgets every trusted " +
                            "device — those entries name the old key. Your other devices keep trusting the old " +
                            "key until you remove it there, and pairing again means trading cards from scratch. " +
                            "This device's permanent id is kept.",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        controller.resetIdentity()
                        navigate(Step.CHOICE)
                    },
                    modifier = Modifier.testTag("confirm_reset"),
                ) { Text("Reset identity", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
