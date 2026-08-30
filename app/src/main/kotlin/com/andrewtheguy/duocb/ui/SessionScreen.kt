package com.andrewtheguy.duocb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andrewtheguy.duocb.ClipItem
import com.andrewtheguy.duocb.SessionController

/**
 * The live clipboard session: status, sending, and the inbox.
 *
 * Received text is never auto-revealed or auto-copied — each item shows only
 * size + CRC + time until the user peeks, and reaches the clipboard only via
 * an explicit Copy (matching the desktop model).
 */
@Composable
fun SessionScreen(controller: SessionController) {
    val context = LocalContext.current
    var composeText by rememberSaveable { mutableStateOf("") }
    var showConnPath by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "duocb",
        actions = {
            IconButton(
                onClick = {
                    controller.connPaths = emptyList()
                    controller.queryConnPath()
                    showConnPath = true
                },
                enabled = controller.phase == SessionController.Phase.Connected,
                modifier = Modifier.testTag("conn_path"),
            ) { Icon(Icons.Filled.Info, contentDescription = "Connection path") }
            IconButton(onClick = { controller.stop() }, modifier = Modifier.testTag("stop")) {
                Icon(Icons.Filled.Close, contentDescription = "Stop")
            }
        },
    ) {
        // Green while connected, red once the session gave up, orange for every
        // transient in between (the reconnect states stay on this screen with
        // the inbox and outbox in place).
        val statusColor = when (controller.phase) {
            SessionController.Phase.Connected -> Green
            is SessionController.Phase.Failed -> MaterialTheme.colorScheme.error
            else -> Orange
        }
        Section(header = "Status") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(statusColor)
                Spacer(Modifier.width(10.dp))
                Text(controller.phase.statusText, modifier = Modifier.testTag("status"))
            }
            // A failed session is parked, not stopped: the runtime keeps the
            // node id, so reconnecting resumes without re-pairing.
            if (controller.phase is SessionController.Phase.Failed) {
                RowButton("Retry", icon = Icons.Filled.Refresh, testTag = "retry") { controller.reconnect() }
            }
            controller.displayIdentity?.let { LabeledValue("This device", it) }
            controller.sessionPeer?.let { LabeledValue("With", it) }
            // Which device is setting the link up: explains what the status
            // line is waiting for; nothing here can change it.
            controller.sessionRoleNote?.let { Footnote(it) }
            controller.peerNodeId?.let { LabeledValue("Peer", shortNodeId(it)) }
            ChannelBadge(controller.channel)
        }
        controller.lastError?.let { ErrorSection(it, color = MaterialTheme.colorScheme.error) }

        Section(header = "Send") {
            // Read the clipboard at tap time — Android only lets the focused app
            // read it, and a button gated on its state would go stale.
            RowButton("Send clipboard", icon = Icons.Filled.ContentPaste, enabled = controller.canSend, testTag = "send_clipboard") {
                val text = Clipboard.read(context)
                if (text != null) controller.send(text) else controller.lastError = "The clipboard is empty"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = composeText,
                    onValueChange = { composeText = it },
                    label = { Text("Or type text to send…") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp).testTag("compose_field"),
                )
                IconButton(
                    onClick = {
                        controller.send(composeText)
                        composeText = ""
                    },
                    enabled = controller.canSend && composeText.isNotEmpty(),
                    modifier = Modifier.testTag("send_text"),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }

        controller.outbox?.let { outbox ->
            Section(header = "Last sent") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ItemSummary(outbox, Modifier.weight(1f).testTag("outbox"))
                    CopyButton(outbox.text)
                }
            }
        }

        Section(header = if (controller.inbox.isEmpty()) "Received" else "Received (${controller.inbox.size})") {
            if (controller.inbox.isEmpty()) {
                Footnote("Nothing received yet")
            } else {
                TextButton(onClick = { controller.clearInbox() }) { Text("Clear") }
            }
            controller.inbox.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ItemSummary(item, Modifier.weight(1f).testTag("inbox_item"))
                        TextButton(onClick = { controller.togglePeek(item.id) }) { Text(if (item.expanded) "Hide" else "Peek") }
                        CopyButton(item.text, testTag = "copy_item")
                    }
                    if (item.expanded) {
                        Text(
                            item.peekText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                                .padding(8.dp)
                                .testTag("peek_text"),
                        )
                    }
                }
            }
        }
    }

    if (showConnPath) {
        ConnPathSheet(
            controller,
            onDismiss = {
                showConnPath = false
                controller.connPaths = null
            },
        )
    }
}

@Composable
private fun ItemSummary(item: ClipItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        Text("${item.sizeDisplay} · ${item.crcDisplay}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        Text(formatTime(context, item.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun shortNodeId(id: String): String = if (id.length > 16) "${id.take(8)}…${id.takeLast(8)}" else id

/**
 * Point-in-time snapshot of the connection's paths (direct vs relay), fetched
 * on demand — the ● marker is the path iroh currently routes over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnPathSheet(controller: SessionController, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connection path", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { controller.queryConnPath() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
            val paths = controller.connPaths
            if (paths.isNullOrEmpty()) {
                Footnote("No path information yet")
            } else {
                paths.forEach { path ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (path.selected) "●" else "○",
                            color = when (path.kind) {
                                "direct" -> Green
                                "relay" -> Orange
                                else -> Color.Gray
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(path.display, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
