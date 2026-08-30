package com.andrewtheguy.duocb.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andrewtheguy.duocb.BuildConfig
import com.andrewtheguy.duocb.SessionController
import com.andrewtheguy.duocb.SignalChannel
import kotlinx.coroutines.delay
import java.util.Date

val Orange = Color(0xFFEF6C00)
val Green = Color(0xFF2E7D32)

/** A screen: top bar, then a scrolling column that gives way to the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

/** A grouped block with an optional header above and footnote below (the iOS Form section). */
@Composable
fun Section(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), content = content)
        }
        if (footer != null) {
            Footnote(footer, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}

/** A label over a (usually monospace) value. */
@Composable
fun LabeledValue(label: String, value: String, mono: Boolean = true, valueColor: Color = Color.Unspecified) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = valueColor,
        )
    }
}

/** A full-width, start-aligned action row (the iOS list Button). */
@Composable
fun RowButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().let { if (testTag != null) it.testTag(testTag) else it },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                color = if (destructive && enabled) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        }
    }
}

@Composable
fun Footnote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier.padding(vertical = 2.dp))
}

@Composable
fun StatusDot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** A warning/error line with an icon, for banners. */
@Composable
fun WarningLine(text: String, color: Color = Orange) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * The unreadable-config banner: a settings file exists that could not be read,
 * so nothing will be saved until the user decides to discard it.
 */
@Composable
fun ConfigFailureSection(controller: SessionController) {
    val reason = controller.configError ?: return
    Section(
        header = "Settings could not be read",
        footer = "Nothing is saved while this is unresolved, so this device's name and trusted devices " +
            "cannot change. Discarding writes a fresh file and loses whatever the old one held — " +
            "including every trusted device, which then have to be traded again.",
    ) {
        WarningLine(reason)
        RowButton("Discard the stored settings and start over", destructive = true) {
            controller.discardUnreadableConfig()
        }
    }
}

/** The failed-session banner (message + Reconnect/Dismiss). */
@Composable
fun SessionFailureSection(controller: SessionController) {
    val failed = controller.phase as? SessionController.Phase.Failed ?: return
    Section {
        WarningLine(failed.message, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth()) {
            if (controller.lastSession != null) {
                TextButton(onClick = { controller.reconnect() }, modifier = Modifier.testTag("reconnect")) { Text("Reconnect") }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { controller.clearFailure() }) { Text("Dismiss") }
        }
    }
}

/** A non-fatal error line with a Dismiss. */
@Composable
fun ErrorSection(message: String, color: Color = Orange, onDismiss: (() -> Unit)? = null) {
    Section {
        WarningLine(message, color = color)
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** A one-line badge naming a non-default signaling channel; nothing on the default. */
@Composable
fun ChannelBadge(channel: SignalChannel) {
    val badge = channel.badge ?: return
    Footnote("Channel: $badge")
}

/** The app version, at the bottom of home screens. */
@Composable
fun AppVersionFooter() {
    Text(
        "duocb v${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/**
 * A copy button that acknowledges the tap — "✔ Copied" for two seconds.
 * `sensitive` flags the clip so the system preview hides it (a private key, a
 * PIN); ordinary content (a card, a received item) is meant to be pasted.
 */
@Composable
fun CopyButton(value: String, title: String = "Copy", sensitive: Boolean = false, testTag: String? = null) {
    val context = LocalContext.current
    var copiedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(copiedAt) {
        if (copiedAt != 0L) {
            delay(2000)
            copiedAt = 0L
        }
    }
    TextButton(
        onClick = {
            Clipboard.copy(context, "duocb", value, sensitive)
            copiedAt = System.currentTimeMillis()
        },
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
    ) {
        Text(if (copiedAt != 0L) "✔ Copied" else title)
    }
}

/**
 * A fingerprint or pairing code, rendered for eye-comparison across two
 * screens. The layout is fixed rather than fitted, and that is the point: two
 * screens must show the same *shape*, so groups are laid out [groupsPerLine]
 * at a time everywhere and a narrow screen shrinks the glyphs instead of
 * re-wrapping them. Five 4-hex groups is one key's whole fingerprint, so a
 * pairing code — two fingerprints end to end — takes two lines, one per key.
 */
@Composable
fun FingerprintText(fingerprint: String, groupsPerLine: Int = 5, modifier: Modifier = Modifier) {
    val groups = fingerprint.split(' ').filter { it.isNotEmpty() }
    val perLine = maxOf(1, groupsPerLine)
    val lines = groups.chunked(perLine).map { it.joinToString(" ") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Phase → user copy, mirroring the desktop's status_text(). */
val SessionController.Phase.statusText: String
    get() = when (this) {
        SessionController.Phase.Idle -> "Idle"
        SessionController.Phase.Starting -> "Starting…"
        SessionController.Phase.Waiting -> "Waiting for the other device…"
        SessionController.Phase.Resolving -> "Looking for the other device…"
        SessionController.Phase.Connecting -> "Connecting…"
        SessionController.Phase.Authenticating -> "Authenticating…"
        SessionController.Phase.Connected -> "Connected"
        is SessionController.Phase.Reconnecting -> "Reconnecting… (attempt $attempt of $max)"
        is SessionController.Phase.Failed -> "Disconnected"
    }

fun formatTime(context: Context, epochMillis: Long): String =
    DateFormat.getTimeFormat(context).format(Date(epochMillis))

/** The system clipboard, read at tap time and written with an optional sensitive flag. */
object Clipboard {
    fun copy(context: Context, label: String, value: String, isSecret: Boolean = false) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, value)
        if (isSecret) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
    }

    /** The clipboard's text, or null when empty. Only readable while this app has focus. */
    fun read(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
    }
}
