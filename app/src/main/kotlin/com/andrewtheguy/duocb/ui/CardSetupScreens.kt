package com.andrewtheguy.duocb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrewtheguy.duocb.SessionController
import kotlinx.coroutines.delay

/**
 * Card setup's entry screen: show a PIN on this device, or type the one shown
 * on the other.
 *
 * This is the trust-bootstrap path and it never carries clipboard content. A
 * rotating PIN authenticates a short-lived connection, the two devices swap
 * signed identity cards across it, and the session ends. Which channel carries
 * the rendezvous comes from Settings and governs both devices' halves, so
 * **both must be set to a channel they share**; the channel is not encoded in
 * the PIN.
 */
@Composable
fun CardSetupScreen(controller: SessionController, navigate: (Step) -> Unit) {
    var pinDraft by rememberSaveable { mutableStateOf("") }
    var ipDraft by rememberSaveable { mutableStateOf("") }
    val canonicalPin = remember(pinDraft) { SessionController.normalizePin(pinDraft) }
    // The manual host-IP entry only exists on a channel that uses the local
    // network — it is the LAN half of the lookup, and the FFI refuses the
    // combination rather than silently dropping the address.
    val showsHostIp = controller.channel.usesLan
    // Read this device's subnet once, so the host-IP entry can lock the network
    // part and range-check the rest.
    val ipContext = remember(showsHostIp) {
        if (showsHostIp) SessionController.joinIpContext() else SessionController.JoinIpContext.EMPTY
    }
    val ipOutcome = remember(ipDraft, showsHostIp) {
        if (showsHostIp) SessionController.resolveJoinIp(ipDraft) else SessionController.JoinIpOutcome.Empty
    }
    // Blank (→ browse DNS-SD) or an in-range address is ready; out-of-range or
    // malformed blocks Join.
    val ipReady = ipOutcome is SessionController.JoinIpOutcome.Empty || ipOutcome is SessionController.JoinIpOutcome.InRange
    val resolvedIp = (ipOutcome as? SessionController.JoinIpOutcome.InRange)?.ip

    ScreenScaffold(title = "Trade cards", onBack = { navigate(Step.HUB) }) {
        SessionFailureSection(controller)
        Section(footer = controller.channel.note + "\n\nBoth devices must be set to a channel they share.") {
            LabeledValue("Channel", controller.channel.title, mono = false)
            RowButton("Change in Settings") { navigate(Step.SETTINGS) }
        }

        Section(
            header = "Show a PIN",
            footer = "A short PIN appears here and renews every 60 seconds until the other device pairs. " +
                "Type it on that device to trade cards.",
        ) {
            RowButton("Show a PIN on this device", icon = Icons.Filled.Wifi, testTag = "show_pin") {
                controller.startCardHost()
            }
        }

        Section(
            header = "Enter a PIN",
            footer = if (showsHostIp) {
                "Type the PIN shown on the other device. If it isn't found automatically, add the local IP " +
                    "that device is showing."
            } else {
                "Type the PIN shown on the other device."
            },
        ) {
            OutlinedTextField(
                value = pinDraft,
                onValueChange = { pinDraft = formatPinDraft(it) },
                label = { Text("XXXX-XXXX") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("pin_field"),
            )
            pinHint(pinDraft, canonicalPin)?.let { Footnote(it, color = Orange) }
            // An optional host IP pairs over the unicast side channel when the
            // device isn't found automatically (multicast blocked). The entry is
            // constrained to this device's subnet — the network part is locked
            // ahead of the field and an out-of-range address is rejected.
            if (showsHostIp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (ipContext.prefix.isNotEmpty()) {
                        Text(
                            ipContext.prefix,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    OutlinedTextField(
                        value = ipDraft,
                        onValueChange = { ipDraft = it },
                        label = { Text(if (ipContext.prefix.isEmpty()) "Host IP (optional)" else ipContext.placeholder) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp).testTag("ip_field"),
                    )
                }
                if (ipContext.hint.isNotEmpty()) Footnote(ipContext.hint)
                when (ipOutcome) {
                    SessionController.JoinIpOutcome.OutOfRange -> Footnote("IP out of range for ${ipContext.label}", color = Orange)
                    SessionController.JoinIpOutcome.Malformed -> Footnote("Not a valid IPv4 address.", color = Orange)
                    else -> {}
                }
            }
            RowButton(
                "Trade cards",
                icon = Icons.Filled.VerifiedUser,
                enabled = canonicalPin != null && (!showsHostIp || ipReady),
                testTag = "join_pin",
            ) {
                canonicalPin?.let { controller.joinCardSetup(it, if (showsHostIp) resolvedIp else null) }
            }
        }
    }
}

/**
 * Keep the field to the PIN's shape, displayed as two dash-separated groups.
 * Character filtering and the alias mapping (I/L→1, O→0) come from the Rust
 * core, so the field can never hold something the code omits. Also capped at
 * the PIN's length: a ninth keystroke is far more likely a double tap than a
 * different PIN.
 */
private fun formatPinDraft(value: String): String {
    val total = SessionController.pinProgress(value).second
    val raw = SessionController.sanitizePin(value).take(total)
    return if (raw.length > 4) raw.substring(0, 4) + "-" + raw.substring(4) else raw
}

/** Neutral while the code is still being typed; a warning once it is full-length but fails its check digit. */
private fun pinHint(draft: String, canonical: String?): String? {
    val (entered, total) = SessionController.pinProgress(draft)
    if (entered == 0) return null
    if (entered < total) return "keep typing — $entered of $total characters"
    return if (canonical == null) "Check the PIN — it is not valid." else null
}

/**
 * Card setup in progress: the rotating PIN on the hosting device, or the dial
 * status on the joining one. Replaced by [CardConfirmScreen] the moment the
 * peer's card arrives.
 */
@Composable
fun CardPairingScreen(controller: SessionController) {
    ScreenScaffold(
        title = "Trade cards",
        actions = {
            IconButton(onClick = { controller.stop() }, modifier = Modifier.testTag("cancel_session")) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel")
            }
        },
    ) {
        val pin = controller.pinDisplay
        if (pin != null) {
            Section(
                header = "PIN",
                footer = if (controller.hostLanIp == null) {
                    "Type this PIN on the other device. Renewal keeps only the current and immediately " +
                        "previous PIN valid; New PIN stops every earlier one working right away."
                } else {
                    "Type this PIN on the other device. If it isn't found automatically, also enter the " +
                        "local IP shown above. Renewal keeps only the current and immediately previous " +
                        "PIN valid; New PIN stops every earlier one working right away."
                },
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        pin,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 40.sp,
                        modifier = Modifier.testTag("pin_display"),
                    )
                    controller.pinDeadlineMillis?.let { deadline -> RenewalCountdown(deadline) }
                    // If the other device can't find this one automatically,
                    // the joiner can type this address.
                    controller.hostLanIp?.let {
                        Text(
                            "Local IP: $it",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                CopyButton(pin, title = "Copy PIN", sensitive = true)
                RowButton("New PIN", icon = Icons.Filled.Refresh, testTag = "new_pin") { controller.refreshPin() }
            }
        }
        Section(header = "Status") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (controller.phase == SessionController.Phase.Connected) Green else Orange)
                Spacer(Modifier.width(10.dp))
                Text(controller.phase.statusText, modifier = Modifier.testTag("status"))
            }
            if (controller.phase is SessionController.Phase.Failed) {
                RowButton("Try again", icon = Icons.Filled.Refresh) { controller.reconnect() }
            }
        }
        controller.lastError?.let { ErrorSection(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** "renews in Ns", ticking once a second. */
@Composable
private fun RenewalCountdown(deadlineMillis: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadlineMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val secs = maxOf(0L, (deadlineMillis - now + 500) / 1000)
    Text("renews in ${secs}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The confirmation step, and the only thing standing between a PIN and a
 * trusted device.
 *
 * The card below is verified as well-formed and correctly signed — that is all
 * the protocol can prove. A PIN is short and its rendezvous record is
 * offline-attackable, so possession of the PIN alone must never be enough to
 * become trusted; an interposer who has it would reach exactly this screen.
 * What catches them is the pairing code: one value built from both devices'
 * keys, rendered identically on the two screens, so an interposed card makes
 * them disagree. Each half is one key's fingerprint, computed locally — the
 * code never crosses the network.
 */
@Composable
fun CardConfirmScreen(controller: SessionController) {
    val incoming = controller.incomingCard ?: return
    val code = controller.incomingPairingCode
    ScreenScaffold(title = "Check the pairing code") {
        Section(
            header = "Pairing code",
            footer = "Both devices now show a pairing code built from both keys. The code above must be " +
                "identical on the two screens. If it differs anywhere, cancel — something else answered " +
                "your PIN.",
        ) {
            if (code != null) FingerprintText(code, modifier = Modifier.padding(vertical = 8.dp).testTag("pairing_code"))
            controller.displayIdentity?.let { LabeledValue("This device", it) }
            LabeledValue("Incoming card", incoming.info.name)
            Footnote(
                incoming.info.expiryText,
                color = if (incoming.info.expired) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Section(
            footer = "Importing adds this device to the trusted list. Do the same on the other device, then " +
                "select each other with Connect on both home screens to share the clipboard.",
        ) {
            // No code (an echo of this device's own card) means there is
            // nothing to have compared, and an expired card could never be
            // stored — in either case there is nothing to trust.
            RowButton(
                "Codes match — trust this device",
                icon = Icons.Filled.VerifiedUser,
                enabled = code != null && !incoming.info.expired,
                testTag = "trust_incoming",
            ) { controller.importIncomingCard() }
            RowButton("Cancel", destructive = true, testTag = "reject_incoming") { controller.dismissIncomingCard() }
        }
    }
}
