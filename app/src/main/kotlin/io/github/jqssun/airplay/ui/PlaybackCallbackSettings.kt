package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.reporting.CurlRequest
import io.github.jqssun.airplay.viewmodel.MainViewModel

@Composable
internal fun PlaybackCallbackSettings(viewModel: MainViewModel) {
    val enabled by viewModel.pref(Prefs.PLAYBACK_CALLBACK_ENABLED).collectAsState()
    val onCommand by viewModel.pref(Prefs.PLAYBACK_CALLBACK_ON).collectAsState()
    val offCommand by viewModel.pref(Prefs.PLAYBACK_CALLBACK_OFF).collectAsState()
    val configured = remember(onCommand, offCommand) {
        runCatching { CurlRequest.parse(onCommand); CurlRequest.parse(offCommand) }.isSuccess
    }
    var editing by remember { mutableStateOf(false) }
    var enableOnSave by remember { mutableStateOf(false) }
    SettingSwitch(
        title = stringResource(R.string.setting_playback_callback),
        description = stringResource(R.string.setting_playback_callback_desc),
        checked = enabled,
        onCheckedChange = { value ->
            if (value && !configured) { enableOnSave = true; editing = true }
            else viewModel.set(Prefs.PLAYBACK_CALLBACK_ENABLED, value)
        }
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.callback_commands)) },
        supportingContent = { Text(stringResource(if (configured) R.string.callback_configured else R.string.callback_not_configured)) },
        trailingContent = {
            TextButton(onClick = { enableOnSave = false; editing = true }, modifier = Modifier.dpadFocus()) {
                Text(stringResource(R.string.callback_edit))
            }
        }
    )
    if (editing) {
        var playing by remember { mutableStateOf(onCommand) }
        var paused by remember { mutableStateOf(offCommand) }
        val onRequest = remember(playing) { runCatching { CurlRequest.parse(playing) }.getOrNull() }
        val offRequest = remember(paused) { runCatching { CurlRequest.parse(paused) }.getOrNull() }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.callback_commands)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.callback_import_desc))
                    CallbackCommandField(stringResource(R.string.callback_playing_command), playing, onRequest) { playing = it }
                    CallbackCommandField(stringResource(R.string.callback_paused_command), paused, offRequest) { paused = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = onRequest != null && offRequest != null,
                    onClick = {
                        viewModel.setPlaybackCallbackCommands(playing, paused)
                        if (enableOnSave) viewModel.set(Prefs.PLAYBACK_CALLBACK_ENABLED, true)
                        editing = false
                    },
                    modifier = Modifier.dpadFocus()
                ) { Text(stringResource(R.string.callback_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }, modifier = Modifier.dpadFocus()) { Text(stringResource(R.string.callback_cancel)) }
            }
        )
    }
}

@Composable
private fun CallbackCommandField(label: String, command: String, request: CurlRequest?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = command,
        onValueChange = { onChange(it.take(65536)) },
        label = { Text(label) },
        minLines = 3,
        maxLines = 5,
        isError = command.isNotBlank() && request == null,
        supportingText = {
            Text(if (request != null) stringResource(R.string.callback_request_preview, request.summary(), request.headers.size)
                else stringResource(if (command.isBlank()) R.string.callback_paste_hint else R.string.callback_parse_error))
        },
        modifier = Modifier.fillMaxWidth().dpadFocus()
    )
}
