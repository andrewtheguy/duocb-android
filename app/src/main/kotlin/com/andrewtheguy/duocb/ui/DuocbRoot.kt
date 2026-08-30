package com.andrewtheguy.duocb.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.andrewtheguy.duocb.LocalNetworkPermission
import com.andrewtheguy.duocb.SessionController

/**
 * Where the home flow is, matching the desktop's `ConfigureStep` plus the
 * screens reached from the hub.
 */
enum class Step {
    CHOICE,
    IMPORT_IDENTITY,
    NAME,
    HUB,

    /** The trusted-device picker, shown only after choosing Connect. */
    CONNECT,

    /** Card setup's entry screen: show a PIN, or type one. */
    CARD_SETUP,
    SETTINGS,
}

/**
 * Root router, mirroring the desktop's `Screen` enum.
 *
 * The order of these branches is the contract. A received card outranks
 * everything: `peer_card_received` is guaranteed to arrive before the
 * session's closing `idle`, so the confirmation screen must be up before
 * teardown is processed, or the card would be dropped on the floor. Below
 * that, card setup and a clipboard session are different screens even though
 * both are "a session is running" — card setup shows a PIN and never carries
 * clipboard content.
 *
 * `testTagsAsResourceId` exposes every `testTag` as a resource-id to
 * `uiautomator dump`, which is how E2E runs find the CTAs.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DuocbRoot(controller: SessionController) {
    LocalNetworkPermissionPrompt(controller)
    Box(Modifier.semantics { testTagsAsResourceId = true }) {
        when {
            controller.incomingCard != null -> CardConfirmScreen(controller)
            controller.isCardSetupActive -> CardPairingScreen(controller)
            controller.isClipboardSessionActive -> SessionScreen(controller)
            else -> SetupFlow(controller)
        }
    }
}

/**
 * The home flow: set up this installation's application identity (generate a
 * keypair or restore one), name this device, then the hub — identity, trusted
 * devices, connect.
 */
@Composable
private fun SetupFlow(controller: SessionController) {
    // Where the stored identity puts us: no key → wizard start; a key but no
    // confirmed name (and so no self-card) → naming; both → the hub.
    val derived = when {
        controller.identitySecret == null -> Step.CHOICE
        controller.deviceName == null || controller.selfCard == null -> Step.NAME
        else -> Step.HUB
    }
    // null until the first user-driven transition; saved across recreation.
    var step by rememberSaveable { mutableStateOf<Step?>(null) }
    val current = step ?: derived

    // A reset drops the identity out from under whatever screen is showing, so
    // follow the stored state back to the wizard rather than stranding the user
    // on a hub with nothing behind it.
    LaunchedEffect(controller.hasIdentity) {
        if (!controller.hasIdentity) step = null
    }

    // Back from a screen reached from home returns home; back from home leaves
    // the app as usual.
    BackHandler(enabled = current != derived) {
        step = if (current == Step.IMPORT_IDENTITY) Step.CHOICE else null
    }

    val navigate: (Step) -> Unit = { step = if (it == derived) null else it }
    when (current) {
        Step.CHOICE -> IdentityChoiceScreen(controller, navigate)
        Step.IMPORT_IDENTITY -> IdentityImportScreen(controller, navigate)
        Step.NAME -> NameDeviceScreen(controller, navigate)
        Step.HUB -> HubScreen(controller, navigate)
        Step.CONNECT -> ConnectPickerScreen(controller, navigate)
        Step.CARD_SETUP -> CardSetupScreen(controller, navigate)
        Step.SETTINGS -> SettingsScreen(controller, navigate)
    }
}

/**
 * Android 17's local-network prompt, on behalf of a session start the
 * controller has parked ([SessionController.awaitingLocalNetworkPermission]).
 * It lives at the root because every session start goes through the same
 * parking spot — the connect picker, both card-setup roles, Reconnect and the
 * debug autostart alike.
 *
 * `requested` keeps a recreation while the dialog is up from asking twice: the
 * launcher's key is saved, so the answer reaches the new callback by itself.
 */
@Composable
private fun LocalNetworkPermissionPrompt(controller: SessionController) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        controller.onLocalNetworkPermissionResult(granted)
    }
    var requested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(controller.awaitingLocalNetworkPermission) {
        if (!controller.awaitingLocalNetworkPermission) {
            requested = false
        } else if (!requested) {
            requested = true
            launcher.launch(LocalNetworkPermission.NAME)
        }
    }
}
