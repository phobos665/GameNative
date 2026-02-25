package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.dialog.PhysicalControllerConfigSection
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.InputControlsManager

@Composable
fun SettingsGroupController() {
    val context = LocalContext.current

    var showOrderDialog by rememberSaveable { mutableStateOf(false) }
    // Step 1: show player picker
    var showPlayerPicker by rememberSaveable { mutableStateOf(false) }
    // Step 2: show rebind dialog for the chosen slot (-1 = none chosen yet)
    var rebindSlot by rememberSaveable { mutableIntStateOf(-1) }

    if (showOrderDialog) {
        ControllerOrderDialog(
            open = showOrderDialog,
            onDismiss = { showOrderDialog = false },
        )
    }

    // Player picker — lists connected controllers with their player slot and device name
    if (showPlayerPicker) {
        val manager = remember { ControllerManager.getInstance().also { it.init(context) } }
        manager.scanForDevices()

        // Build the list of (slotIndex, InputDevice) for every slot that has a connected device
        val connectedSlots = remember(manager.detectedDevices.size) {
            (0..3).mapNotNull { slot ->
                val device = manager.getAssignedDeviceForSlot(slot)
                if (device != null) slot to device else null
            }
        }

        AlertDialog(
            onDismissRequest = { showPlayerPicker = false },
            title = { Text(stringResource(R.string.settings_controller_rebind_pick_player)) },
            text = {
                if (connectedSlots.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_controller_rebind_no_controllers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .selectableGroup()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        connectedSlots.forEach { (slot, device) ->
                            val label = stringResource(
                                R.string.settings_controller_rebind_player_entry,
                                slot + 1,
                                device.name ?: stringResource(R.string.controller_order_unassigned),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .selectable(
                                        selected = false,
                                        onClick = {
                                            showPlayerPicker = false
                                            rebindSlot = slot
                                        },
                                        role = Role.RadioButton,
                                    )
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = false,
                                    onClick = null,
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlayerPicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    // Rebind dialog — opened after a player is chosen from the picker
    if (rebindSlot >= 0) {
        val manager = InputControlsManager(context)
        val profile = manager.getProfile(0)
        if (profile != null) {
            PhysicalControllerConfigSection(
                profile = profile,
                onDismiss = { rebindSlot = -1 },
                onSave = { rebindSlot = -1 },
            )
        } else {
            rebindSlot = -1
        }
    }

    SettingsGroup(title = { Text(text = stringResource(R.string.settings_controller_title)) }) {
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_controller_order_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_controller_order_subtitle)) },
            onClick = { showOrderDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_controller_rebind_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_controller_rebind_subtitle)) },
            onClick = { showPlayerPicker = true },
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_SettingsGroupController() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        SettingsGroupController()
    }
}

