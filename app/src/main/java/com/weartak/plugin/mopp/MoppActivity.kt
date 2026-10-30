package com.weartak.plugin.mopp

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.RadioButton
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import com.weartak.plugin.mopp.host.MoppPluginBinder
import com.weartak.plugin.mopp.host.MoppRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MoppActivity : ComponentActivity() {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var launchError by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by MoppRuntime.editor.state.collectAsState()
                LaunchedEffect(state.availability) {
                    if (state.availability == Availability.READY) {
                        worker.launch { MoppRuntime.editor.read() }
                    }
                }
                MoppScreen(
                    state = state,
                    launchError = launchError,
                    onBack = ::openWearTak,
                    onSelect = { posture -> worker.launch { MoppRuntime.editor.save(posture) } },
                    onRead = { worker.launch { MoppRuntime.editor.read() } },
                    onEnable = { enabled -> worker.launch { MoppRuntime.editor.setEnabled(enabled) } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        worker.launch { MoppRuntime.editor.read() }
    }

    override fun onDestroy() {
        worker.cancel()
        MoppRuntime.editor.cancelDraft()
        super.onDestroy()
    }

    private fun openWearTak() {
        val candidates = listOfNotNull(MoppRuntime.hostPackage) + MoppPluginBinder.HOST_PACKAGES
        val intent = candidates.distinct().firstNotNullOfOrNull {
            packageManager.getLaunchIntentForPackage(it)
        }
        if (intent == null) {
            launchError = true
            return
        }
        try {
            startActivity(intent)
            finish()
        } catch (error: ActivityNotFoundException) {
            Log.w("MoppActivity", "WearTAK launch activity unavailable", error)
            launchError = true
        }
    }
}

@Composable
private fun postureLabel(posture: Posture?): String = when (posture) {
    null -> stringResource(R.string.never_saved)
    Posture.NONE -> stringResource(R.string.none)
    else -> stringResource(R.string.mopp_level, posture.wireValue.toInt())
}

@Composable
private fun MoppScreen(
    state: EditorState,
    launchError: Boolean,
    onBack: () -> Unit,
    onSelect: (Posture) -> Unit,
    onRead: () -> Unit,
    onEnable: (Boolean) -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        autoCentering = null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
                colors = ButtonDefaults.secondaryButtonColors(backgroundColor = MaterialTheme.colors.background),
            ) {
                Icon(
                    painterResource(R.drawable.mopp_back),
                    stringResource(R.string.back_to_weartak),
                    Modifier.size(18.dp),
                )
            }
        }
        item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.title2) }
        if (launchError) item { Notice(R.string.host_unavailable, error = true) }
        state.message?.let { message -> item { Notice(message.resource, error = message.isError) } }
        when (state.availability) {
            Availability.DISCONNECTED -> item { Notice(R.string.disconnected) }
            Availability.UPDATE_WEARTAK -> item { Notice(R.string.update_weartak, error = true) }
            Availability.READY -> {
                val saved = state.saved
                if (saved != null) {
                    item {
                        Text(
                            stringResource(R.string.saved_selection, postureLabel(saved.selection)),
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (saved.selection != null) {
                        item {
                            val timestamp = saved.savedAtEpochMillis
                            if (timestamp == null) Notice(R.string.unknown_saved_time) else Text(
                                stringResource(
                                    R.string.saved_time,
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(timestamp)),
                                ),
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item {
                        ToggleChip(
                            checked = saved.enabled,
                            onCheckedChange = onEnable,
                            enabled = state.canToggle,
                            label = {
                                Text(stringResource(if (saved.enabled) R.string.enabled else R.string.disabled))
                            },
                            toggleControl = {
                                androidx.wear.compose.material.Switch(checked = saved.enabled)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state.busy) item { Notice(R.string.working) }
                Posture.entries.forEach { posture ->
                    item(key = posture.wireValue) {
                        ToggleChip(
                            checked = saved?.selection == posture,
                            onCheckedChange = { onSelect(posture) },
                            enabled = state.canSelect,
                            label = { Text(postureLabel(posture)) },
                            toggleControl = { RadioButton(selected = saved?.selection == posture) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.busy,
                        label = { Text(stringResource(R.string.refresh)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(resource: Int, error: Boolean = false) {
    Text(
        stringResource(resource),
        style = MaterialTheme.typography.body2,
        color = if (error) MaterialTheme.colors.error else MaterialTheme.colors.onBackground,
        textAlign = TextAlign.Center,
    )
}

private val EditorMessage.resource: Int
    get() = when (this) {
        EditorMessage.SAVED -> R.string.saved
        EditorMessage.RECONCILED -> R.string.reconciled
        EditorMessage.NOT_RECONCILED -> R.string.not_reconciled
        EditorMessage.CONFLICT -> R.string.error_conflict
        EditorMessage.INVALID -> R.string.error_invalid
        EditorMessage.DENIED -> R.string.error_denied
        EditorMessage.PERSISTENCE -> R.string.error_persistence
        EditorMessage.TRANSPORT -> R.string.error_transport
        EditorMessage.UNCONFIRMED -> R.string.error_unconfirmed
        EditorMessage.PROTOCOL -> R.string.error_protocol
    }

private val EditorMessage.isError: Boolean
    get() = this !in setOf(EditorMessage.SAVED, EditorMessage.RECONCILED)
