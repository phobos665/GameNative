package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import com.winlator.inputcontrols.ControllerManager

/**
 * Dialog for assigning physical controllers to player slots (P1–P4).
 *
 * Uses [ControllerManager] to scan for connected devices and persist slot assignments.
 */
@Composable
fun ControllerOrderDialog(
    open: Boolean,
    onDismiss: () -> Unit,
) {
    if (!open) return

    val context = LocalContext.current
    val manager = remember { ControllerManager.getInstance().also { it.init(context) } }

    // Refresh detected devices every time the dialog opens
    manager.scanForDevices()
    val detectedDevices = remember { mutableStateListOf<InputDevice>().also { list -> list.addAll(manager.detectedDevices) } }

    ControllerOrderDialogContent(
        detectedDevices = detectedDevices,
        getAssignedDeviceForSlot = { slot -> manager.getAssignedDeviceForSlot(slot) },
        onAssign = { slot, device -> manager.assignDeviceToSlot(slot, device) },
        onUnassign = { slot -> manager.unassignSlot(slot) },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControllerOrderDialogContent(
    detectedDevices: List<InputDevice>,
    getAssignedDeviceForSlot: (Int) -> InputDevice?,
    onAssign: (slot: Int, device: InputDevice) -> Unit,
    onUnassign: (slot: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Track slot dropdown expanded state for each of the 4 player slots
    val expandedSlots = remember { mutableStateListOf(false, false, false, false) }

    // The "Unassigned" option label
    val unassignedLabel = stringResource(R.string.controller_order_unassigned)

    // Build the list of items shown in each dropdown: [Unassigned, device1, device2, ...]
    val deviceLabels = listOf(unassignedLabel) + detectedDevices.map { it.name ?: it.id.toString() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_controller_order_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_controller_order_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Only show as many player rows as there are connected controllers (max 4).
                val slotCount = detectedDevices.size.coerceIn(0, 4)

                for (slot in 0 until slotCount) {
                    val playerLabel = stringResource(R.string.controller_order_player_slot, slot + 1)

                    // Determine currently assigned device for this slot
                    val assignedDevice = getAssignedDeviceForSlot(slot)
                    val currentIndex = if (assignedDevice != null) {
                        val deviceIdx = detectedDevices.indexOfFirst { it.id == assignedDevice.id }
                        if (deviceIdx >= 0) deviceIdx + 1 else 0
                    } else {
                        0
                    }

                    var selectedIndex by remember(slot, detectedDevices.size) { mutableStateOf(currentIndex) }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = playerLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        ExposedDropdownMenuBox(
                            expanded = expandedSlots[slot],
                            onExpandedChange = { expandedSlots[slot] = it },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                readOnly = true,
                                value = deviceLabels.getOrElse(selectedIndex) { unassignedLabel },
                                onValueChange = {},
                                label = { Text(stringResource(R.string.controller_order_assigned_controller)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSlots[slot]) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                singleLine = true,
                            )
                            ExposedDropdownMenu(
                                expanded = expandedSlots[slot],
                                onDismissRequest = { expandedSlots[slot] = false },
                            ) {
                                deviceLabels.forEachIndexed { idx, label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            selectedIndex = idx
                                            expandedSlots[slot] = false
                                            if (idx == 0) {
                                                onUnassign(slot)
                                            } else {
                                                onAssign(slot, detectedDevices[idx - 1])
                                            }
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                    )
                                }
                            }
                        }
                    }

                    if (slot < slotCount - 1) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                if (detectedDevices.isEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.controller_order_no_devices),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ControllerOrderDialog_NoDevices() {
    PluviaTheme {
        ControllerOrderDialogContent(
            detectedDevices = emptyList(),
            getAssignedDeviceForSlot = { null },
            onAssign = { _, _ -> },
            onUnassign = { },
            onDismiss = { },
        )
    }
}

/** Preview-only variant that accepts plain string device names instead of [InputDevice]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControllerOrderDialogContentPreview(
    deviceNames: List<String>,
    assignedSlots: Map<Int, Int> = emptyMap(),
) {
    val expandedSlots = remember { mutableStateListOf(false, false, false, false) }
    val unassignedLabel = stringResource(R.string.controller_order_unassigned)
    val deviceLabels = listOf(unassignedLabel) + deviceNames

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.settings_controller_order_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_controller_order_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val slotCount = deviceNames.size.coerceIn(0, 4)
                for (slot in 0 until slotCount) {
                    val playerLabel = stringResource(R.string.controller_order_player_slot, slot + 1)
                    var selectedIndex by remember { mutableStateOf(assignedSlots[slot] ?: 0) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = playerLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        ExposedDropdownMenuBox(
                            expanded = false,
                            onExpandedChange = {},
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                readOnly = true,
                                value = deviceLabels.getOrElse(selectedIndex) { unassignedLabel },
                                onValueChange = {},
                                label = { Text(stringResource(R.string.controller_order_assigned_controller)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = false) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                singleLine = true,
                            )
                        }
                    }
                    if (slot < slotCount - 1) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {}) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ControllerOrderDialog_WithDevices() {
    PluviaTheme {
        ControllerOrderDialogContentPreview(
            deviceNames = listOf("Xbox Controller", "DualSense Wireless Controller"),
            assignedSlots = mapOf(0 to 1, 1 to 2),
        )
    }
}
