package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.dialog.PhysicalControllerConfigSection
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.winlator.inputcontrols.InputControlsManager

@Composable
fun SettingsGroupController() {
    val context = LocalContext.current

    var showOrderDialog by rememberSaveable { mutableStateOf(false) }
    var showRebindDialog by rememberSaveable { mutableStateOf(false) }

    if (showOrderDialog) {
        ControllerOrderDialog(
            open = showOrderDialog,
            onDismiss = { showOrderDialog = false },
        )
    }

    if (showRebindDialog) {
        val manager = InputControlsManager(context)
        val profile = manager.getProfile(0)
        if (profile != null) {
            PhysicalControllerConfigSection(
                profile = profile,
                onDismiss = { showRebindDialog = false },
                onSave = { showRebindDialog = false },
            )
        } else {
            showRebindDialog = false
        }
    }

    SettingsGroup(title = { Text(text = stringResource(R.string.settings_controller_title)) }) {
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_controller_rebind_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_controller_rebind_subtitle)) },
            onClick = { showRebindDialog = true },
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

