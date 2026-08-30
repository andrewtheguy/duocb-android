package com.andrewtheguy.duocb.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.andrewtheguy.duocb.SessionController

/**
 * The configured home hub: this device's identity and card, the actions that
 * start a session, and the way in to card setup.
 *
 * **Nothing runs here.** The trusted-device list is local state read from this
 * app's own storage — there is no broadcast, no discovery, and no relay
 * connection — so the hub holds no FFI handle at all. A runtime instance
 * appears only when the user connects to a device or trades cards.
 */
@Composable
fun HubScreen(controller: SessionController, navigate: (Step) -> Unit) {
    val isFailed = controller.phase is SessionController.Phase.Failed
    ScreenScaffold(
        title = "duocb",
        actions = {
            IconButton(onClick = { navigate(Step.SETTINGS) }, modifier = Modifier.testTag("settings")) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    ) {
        SessionFailureSection(controller)
        ConfigFailureSection(controller)
        // Only when the failure banner is not already up: `fail` passes
        // lastError through as the phase's message, so the two would say the
        // same sentence twice.
        val error = controller.lastError
        if (!isFailed && error != null) {
            ErrorSection(error) { controller.lastError = null }
        }

        Section(
            header = "This device",
            footer = "Give another device this card and it will trust you. Cards last 30 days; at launch, " +
                "this device renews its card once less than seven days remain. Copy a fresh one if the " +
                "other device says yours has expired. Your private key never expires — a renewal is the " +
                "same key signing a new card. The fingerprint is this device's half of the pairing code " +
                "shown when trading cards, and it does not change when the card renews.",
        ) {
            LabeledValue("Identity", controller.displayIdentity ?: "")
            controller.ownFingerprint?.let {
                LabeledValue("Fingerprint", "", mono = false)
                FingerprintText(it)
            }
            controller.selfCardInfo?.let { info ->
                LabeledValue("Card", info.expiryText, mono = false, valueColor = if (info.expired) Orange else androidx.compose.ui.graphics.Color.Unspecified)
            }
            controller.selfCard?.let { CopyButton(it, title = "Copy this device's card", testTag = "copy_card") }
            ChannelBadge(controller.channel)
            RowButton("Rename this device", testTag = "rename") { navigate(Step.NAME) }
        }

        Section(
            header = "Share the clipboard",
            footer = if (controller.peers.isEmpty()) {
                "No trusted devices yet. Trade cards with your other device below — after that, each of " +
                    "you picks the other and presses Connect."
            } else {
                "Pick the device you want to share with, and have it pick this one. Neither side has to " +
                    "go first or agree who hosts — duocb settles that from the two identity keys, and " +
                    "whoever is ready first waits for the other."
            },
        ) {
            RowButton(
                "Connect to a device",
                icon = Icons.Filled.Share,
                enabled = controller.peers.isNotEmpty(),
                testTag = "connect",
            ) { navigate(Step.CONNECT) }
        }

        Section(
            header = "Trust",
            footer = "Trading cards is how two devices come to trust each other: one shows a PIN, the other " +
                "types it, and you check that one pairing code reads identically on both screens before " +
                "either card is kept. It carries no clipboard content and ends as soon as the cards have " +
                "crossed.",
        ) {
            RowButton("Trade cards", icon = Icons.Filled.VerifiedUser, testTag = "trade_cards") { navigate(Step.CARD_SETUP) }
            if (controller.peers.isNotEmpty()) {
                RowButton("Trusted devices (${controller.peers.size})", testTag = "trusted_devices") { navigate(Step.CONNECT) }
            }
        }
        AppVersionFooter()
    }
}
