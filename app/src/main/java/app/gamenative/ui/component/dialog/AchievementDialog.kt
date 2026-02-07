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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.data.AchievementInfo
import app.gamenative.data.AchievementSchema
import app.gamenative.service.SteamService
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Achievement viewer dialog that displays achievements for a game.
 * Shows achievement name, description, icon, unlock status, and unlock timestamp.
 * Follows JavaSteam UserStatsCallback parsing pattern.
 */
@Composable
fun AchievementDialog(
    appId: Int,
    appName: String,
    settingsDir: Path,
    onDismiss: () -> Unit,
) {
    var schema by remember { mutableStateOf<AchievementSchema?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Load achievement data from Steam (following JavaSteam pattern)
    LaunchedEffect(appId) {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null

                // Fetch schema and user stats from Steam
                val fetchedSchema = withContext(Dispatchers.IO) {
                    SteamService.instance?.getAchievementSchema(appId)
                }

                if (fetchedSchema == null || fetchedSchema.achievements.isEmpty()) {
                    errorMessage = "No achievements found for this game"
                    isLoading = false
                    return@launch
                }

                schema = fetchedSchema
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
                        val achievements = schema!!.achievements
                        val unlockedCount = achievements.count { it.isUnlocked }
                        
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Achievement stats header
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Progress: $unlockedCount / ${achievements.size}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = String.format("%.1f%% Complete", 
                                            unlockedCount * 100.0 / achievements.size),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            
                            AchievementList(achievements = achievements)
                        }
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
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(achievements) { achievement ->
            AchievementItem(achievement = achievement)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AchievementItem(
    achievement: AchievementInfo,
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Achievement icon (following JavaSteam pattern - show colored icon if unlocked, gray if locked)
            CoilImage(
                modifier = Modifier.size(48.dp),
                imageModel = { if (achievement.isUnlocked) achievement.icon else achievement.iconGray },
                imageOptions = ImageOptions(contentScale = ContentScale.Fit),
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
                
                // Description or "Hidden achievement" text
                if (achievement.description.isNotEmpty()) {
                    Text(
                        text = if (achievement.hidden && !achievement.isUnlocked) {
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
                
                // Show unlock timestamp if unlocked (following JavaSteam pattern)
                if (achievement.isUnlocked && achievement.unlockTimestamp > 0) {
                    val unlockDate = dateFormat.format(Date(achievement.unlockTimestamp * 1000L))
                    Text(
                        text = "Unlocked: $unlockDate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }

            // Unlock status icon
            Icon(
                imageVector = if (achievement.isUnlocked) Icons.Default.CheckCircle else Icons.Default.Lock,
                contentDescription = if (achievement.isUnlocked) "✓ UNLOCKED" else "✗ LOCKED",
                tint = if (achievement.isUnlocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )
        }
    }
}
