package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.data.AchievementInfo
import app.gamenative.data.AchievementSchema
import app.gamenative.service.SteamService
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Achievement viewer dialog that displays achievements for a game.
 * Shows achievement name, description, icon, and unlock status.
 */
@Composable
fun AchievementDialog(
    appId: Int,
    appName: String,
    settingsDir: Path,
    onDismiss: () -> Unit,
) {
    var schema by remember { mutableStateOf<AchievementSchema?>(null) }
    var achievementStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Load achievement data
    LaunchedEffect(appId) {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null

                // Fetch schema
                val fetchedSchema = withContext(Dispatchers.IO) {
                    SteamService.instance?.getAchievementSchema(appId)
                }

                if (fetchedSchema == null || fetchedSchema.achievements.isEmpty()) {
                    errorMessage = "No achievements found for this game"
                    isLoading = false
                    return@launch
                }

                schema = fetchedSchema

                // Load achievement states from achievements.ini
                val states = withContext(Dispatchers.IO) {
                    loadAchievementStates(settingsDir)
                }
                achievementStates = states

                isLoading = false
            } catch (e: Exception) {
                Timber.e(e, "Failed to load achievements for app $appId")
                errorMessage = "Failed to load achievements: ${e.message}"
                isLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Achievements - $appName") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    errorMessage != null -> {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    schema != null -> {
                        AchievementList(
                            achievements = schema!!.achievements,
                            achievementStates = achievementStates,
                            onToggleAchievement = { apiName ->
                                scope.launch {
                                    val newState = !(achievementStates[apiName] ?: false)
                                    achievementStates = achievementStates + (apiName to newState)
                                    withContext(Dispatchers.IO) {
                                        saveAchievementState(settingsDir, apiName, newState)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = null
    )
}

@Composable
private fun AchievementList(
    achievements: List<AchievementInfo>,
    achievementStates: Map<String, Boolean>,
    onToggleAchievement: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(achievements) { achievement ->
            AchievementItem(
                achievement = achievement,
                isUnlocked = achievementStates[achievement.apiName] ?: false,
                onToggle = { onToggleAchievement(achievement.apiName) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AchievementItem(
    achievement: AchievementInfo,
    isUnlocked: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Achievement icon
            AsyncImage(
                model = if (isUnlocked) achievement.icon else achievement.iconGray,
                contentDescription = achievement.displayName,
                modifier = Modifier.size(48.dp)
            )

            // Achievement info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = achievement.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (achievement.description.isNotEmpty()) {
                    Text(
                        text = if (achievement.hidden && !isUnlocked) {
                            "Hidden achievement"
                        } else {
                            achievement.description
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Unlock status icon
            Icon(
                imageVector = if (isUnlocked) Icons.Default.CheckCircle else Icons.Default.Lock,
                contentDescription = if (isUnlocked) "Unlocked" else "Locked",
                tint = if (isUnlocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
        }
    }
}

/**
 * Load achievement states from achievements.ini file
 * Format: [ACHIEVEMENTS]
 *         achievement_name=0/1
 */
private fun loadAchievementStates(settingsDir: Path): Map<String, Boolean> {
    val achievementsFile = settingsDir.resolve("achievements.ini").toFile()
    if (!achievementsFile.exists()) {
        return emptyMap()
    }

    val states = mutableMapOf<String, Boolean>()
    try {
        achievementsFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith('[') || trimmed.isEmpty()) return@forEach

            val parts = trimmed.split('=', limit = 2)
            if (parts.size == 2) {
                val apiName = parts[0].trim()
                val value = parts[1].trim()
                states[apiName] = value == "1"
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to load achievement states")
    }

    return states
}

/**
 * Save achievement state to achievements.ini file
 */
private fun saveAchievementState(settingsDir: Path, apiName: String, isUnlocked: Boolean) {
    val achievementsFile = settingsDir.resolve("achievements.ini").toFile()
    if (!achievementsFile.exists()) {
        return
    }

    try {
        val lines = achievementsFile.readLines().toMutableList()
        var updated = false

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith(apiName + "=")) {
                lines[i] = "$apiName=${if (isUnlocked) 1 else 0}"
                updated = true
                break
            }
        }

        if (updated) {
            achievementsFile.writeText(lines.joinToString("\n"))
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to save achievement state for $apiName")
    }
}
