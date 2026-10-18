package com.hermesconsole.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hermesconsole.lcars.LcarsBar
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsPill
import com.hermesconsole.lcars.LcarsSegment
import com.hermesconsole.lcars.LcarsTheme

/**
 * The settings screen's edit state: every value as the text it is while somebody is typing it
 * (T-18, REQ-17).
 *
 * The screen's one rule is that a draft may be half-*configured* but never half-*parsed*. A host
 * can be blank and a key unset while a console is being set up - completeness is
 * [BackendSettings.toBackends]' judgement, made when the loop dials, not this screen's - but a port
 * or an interval that is not a positive number has no honest integer to persist, and SAVE must
 * refuse rather than quietly write a default over what is stored.
 *
 * That rule lived inside the composable until the intervals arrived: a pair of booleans deciding
 * whether SAVE was enabled, and a separate run of elvis-returns deciding what it wrote - two places
 * that had to agree about the same thing, and neither reachable from a JVM test. Here they are one
 * thing. [toSettings] answers what SAVE would persist, or null; the pill is enabled exactly when it
 * is non-null, and what it saves is that same answer.
 */
internal data class SettingsDraft(
    val hermesHost: String,
    val hermesPort: String,
    val hermesKey: String,
    val vmHost: String,
    val vmPort: String,
    val vmKey: String,
    val followUpWindow: String,
    val sessionTimeout: String,
    val screensaverTimeout: String,
) {

    /** The screen as it opens (REQ-17): every stored value already in its field. */
    constructor(initial: BackendSettings) : this(
        hermesHost = initial.hermesHost,
        hermesPort = initial.hermesPort.toString(),
        hermesKey = initial.hermesKey,
        vmHost = initial.vmHost,
        vmPort = initial.vmPort.toString(),
        vmKey = initial.vmKey,
        followUpWindow = initial.followUpWindowSeconds.toString(),
        sessionTimeout = initial.sessionTimeoutSeconds.toString(),
        screensaverTimeout = initial.screensaverTimeoutSeconds.toString(),
    )

    /** What SAVE persists, or null while any number does not parse or a key cannot go in a header. */
    fun toSettings(): BackendSettings? = BackendSettings(
        hermesHost = hermesHost.trim(),
        hermesPort = parsePort(hermesPort.trim()) ?: return null,
        hermesKey = hermesKey.trim().takeIf(::isHeaderSafe) ?: return null,
        vmHost = vmHost.trim(),
        vmPort = parsePort(vmPort.trim()) ?: return null,
        vmKey = vmKey.trim().takeIf(::isHeaderSafe) ?: return null,
        followUpWindowSeconds = parseFollowUpWindow(followUpWindow.trim()) ?: return null,
        sessionTimeoutSeconds = parseIdleTimeout(sessionTimeout.trim()) ?: return null,
        screensaverTimeoutSeconds = parseIdleTimeout(screensaverTimeout.trim()) ?: return null,
    )
}

/**
 * The settings screen (T-23, REQ-28): the one place the console's runtime values - each backend's
 * host, port and API key, and the follow-up window (T-46, REQ-40) - are ever entered. Nothing
 * here is compiled in, and nothing typed here can be read back off the wall: the key fields
 * render masked, take a password-typed IME so the keyboard neither learns nor suggests a
 * credential, and the values themselves only ever move between this screen and the
 * [SettingsStore].
 *
 * Edits are local state until SAVE persists them as one [BackendSettings]; CANCEL walks away and
 * the store never hears about it. A half-configured draft saves fine - [BackendSettings.toBackends]
 * is where completeness is judged, not here - but a port or a window that does not parse never
 * leaves the screen, because there is no honest integer to persist for it.
 */
@Composable
fun SettingsScreen(
    initial: BackendSettings,
    onSave: (BackendSettings) -> Unit,
    onDone: () -> Unit,
    onOpenSystemSettings: () -> Unit = {},
) {
    LcarsTheme {
        var draft by remember { mutableStateOf(SettingsDraft(initial)) }

        // What SAVE would write, or null while some number in the draft does not parse. One answer,
        // used twice: it is what enables the pill and what the pill saves, so the screen cannot
        // offer a save it would then refuse to perform.
        val saveable = draft.toSettings()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LcarsColors.Space)
                .padding(LcarsMetrics.Gap * 2)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
        ) {
            LcarsBar(
                segments = listOf(
                    LcarsSegment("config", LcarsColors.Butterscotch, weight = 0.25f),
                    LcarsSegment(null, LcarsColors.Bluey, weight = 0.45f),
                    LcarsSegment("runtime only", LcarsColors.IceBlue, weight = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
                capStart = true,
            )

            // Two backends, two columns: the landscape frame (REQ-38) is wide enough to say so,
            // and the symmetry is the documentation - the same three values, per peer.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 6),
            ) {
                BackendColumn(
                    title = "HERMES GATEWAY",
                    host = draft.hermesHost,
                    onHost = { draft = draft.copy(hermesHost = it) },
                    port = draft.hermesPort,
                    onPort = { draft = draft.copy(hermesPort = it) },
                    key = draft.hermesKey,
                    onKey = { draft = draft.copy(hermesKey = it) },
                    modifier = Modifier.weight(1f),
                )
                BackendColumn(
                    title = "VOICE MANAGER",
                    host = draft.vmHost,
                    onHost = { draft = draft.copy(vmHost = it) },
                    port = draft.vmPort,
                    onPort = { draft = draft.copy(vmPort = it) },
                    key = draft.vmKey,
                    onKey = { draft = draft.copy(vmKey = it) },
                    keyLabel = "token",
                    modifier = Modifier.weight(1f),
                )
                // The console's own knobs, beside the backends they are not: how long a finished
                // reply keeps the console listening for a follow-up (REQ-40), how long a
                // conversation stands open with nobody speaking to it, and how long the wall stays
                // lit with nobody in front of it (REQ-16/REQ-17). All three in seconds, all three
                // refusing anything that is not a positive number - there is no honest integer to
                // persist for "soon", and a zero here is a console that returns instantly.
                Column(
                    modifier = Modifier.weight(0.6f),
                    verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
                ) {
                    BasicText(
                        "CONSOLE",
                        style = LcarsTheme.typography.header.copy(color = LcarsColors.IceBlue),
                    )
                    SettingsField(
                        label = "follow-up window (s)",
                        value = draft.followUpWindow,
                        onValueChange = { draft = draft.copy(followUpWindow = it) },
                        keyboardType = KeyboardType.Number,
                        valid = parseFollowUpWindow(draft.followUpWindow) != null,
                    )
                    SettingsField(
                        label = "session timeout (s)",
                        value = draft.sessionTimeout,
                        onValueChange = { draft = draft.copy(sessionTimeout = it) },
                        keyboardType = KeyboardType.Number,
                        valid = parseIdleTimeout(draft.sessionTimeout) != null,
                    )
                    SettingsField(
                        label = "screensaver timeout (s)",
                        value = draft.screensaverTimeout,
                        onValueChange = { draft = draft.copy(screensaverTimeout = it) },
                        keyboardType = KeyboardType.Number,
                        valid = parseIdleTimeout(draft.screensaverTimeout) != null,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap),
            ) {
                LcarsPill(
                    text = "save",
                    color = LcarsColors.Orange,
                    enabled = saveable != null,
                    onClick = {
                        onSave(saveable ?: return@LcarsPill)
                        onDone()
                    },
                )
                LcarsPill(
                    text = "cancel",
                    color = LcarsColors.Bluey,
                    onClick = onDone,
                )
                // Not a draft action like SAVE/CANCEL but the console's one touch path to device
                // config (T-52, REQ-41) - the console is HOME, so there is no system launcher to
                // borrow - so it sits apart at the far end, and fires the platform action like any
                // kit pill: with the touch chirp (REQ-39) LcarsPill plays for every live press.
                Spacer(modifier = Modifier.weight(1f))
                LcarsPill(
                    text = SYSTEM_SETTINGS_LABEL,
                    color = LcarsColors.Lilac,
                    onClick = onOpenSystemSettings,
                )
            }
        }
    }
}

/** One backend's three values, stacked under its name. */
@Composable
private fun BackendColumn(
    title: String,
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    key: String,
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The gateway takes an API key; VoiceManager takes a per-user token. */
    keyLabel: String = "api key",
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2)) {
        BasicText(title, style = LcarsTheme.typography.header.copy(color = LcarsColors.IceBlue))
        SettingsField(label = "host", value = host, onValueChange = onHost)
        SettingsField(
            label = "port",
            value = port,
            onValueChange = onPort,
            keyboardType = KeyboardType.Number,
            valid = parsePort(port) != null,
        )
        // Masked on screen and Password to the IME: the key is typed in, never echoed back, and
        // never offered to the keyboard's learning or suggestions (REQ-28).
        SettingsField(
            label = keyLabel,
            value = key,
            onValueChange = onKey,
            keyboardType = KeyboardType.Password,
            secret = true,
            valid = isHeaderSafe(key.trim()),
        )
    }
}

/** A labelled field in the debug screen's idiom: a bordered line of text on space. */
@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    valid: Boolean = true,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap / 2)) {
        BasicText(
            text = label.uppercase(),
            style = LcarsTheme.typography.readout.copy(color = LcarsColors.IceBlue),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(LcarsMetrics.BarHeight)
                // Tomato is the screen's whole validation story: an unparseable port cannot be
                // saved, and the border says which field is holding SAVE hostage.
                .border(
                    width = 2.dp,
                    color = if (valid) LcarsColors.Gray else LcarsColors.Tomato,
                    shape = RoundedCornerShape(LcarsMetrics.InnerRadius),
                )
                .padding(horizontal = LcarsMetrics.LabelPadding),
            textStyle = LcarsTheme.typography.body.copy(color = LcarsColors.OnSpace),
            cursorBrush = SolidColor(LcarsColors.Orange),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation =
                if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() }
            },
        )
    }
}
