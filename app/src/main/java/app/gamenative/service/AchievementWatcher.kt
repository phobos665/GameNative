package app.gamenative.service
import android.os.FileObserver
import app.gamenative.ui.util.AchievementNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.gamenative.PrefManager
import org.json.JSONObject
import timber.log.Timber
import java.io.File

class AchievementWatcher(
    private val appId: Int,
    private val watchDirs: List<File>,
    private val displayNameMap: Map<String, String>,
    private val iconUrlMap: Map<String, String?>,
    private val onUpload: (suspend (unlockedNames: Set<String>, watchDirs: List<File>) -> Unit)? = null,
) {
    private val observers = mutableListOf<FileObserver>()
    private val notifiedNames = mutableSetOf<String>()
    private val uploadedNames = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var uploadJob: Job? = null
    fun start() {
        for (dir in watchDirs) {
            dir.mkdirs()
            val achFile = File(dir, "achievements.json")
            if (achFile.exists()) {
                try {
                    val json = JSONObject(achFile.readText(Charsets.UTF_8))
                    for (achievementName in json.keys()) {
                        val entry = json.optJSONObject(achievementName) ?: continue
                        if (entry.optBoolean("earned", false)) {
                            notifiedNames.add(achievementName)
                            uploadedNames.add(achievementName)
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("achievements").w(e, "Failed to snapshot existing achievements.json in ${dir.absolutePath}")
                }
            }
        }
        Timber.tag("achievements").d("AchievementWatcher seeded ${notifiedNames.size} pre-existing achievements")
        for (dir in watchDirs) {
            @Suppress("DEPRECATION")
            val observer = object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "achievements.json") {
                        checkForNewUnlocks(File(dir, "achievements.json"))
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
        Timber.tag("achievements").d("AchievementWatcher started, watching ${watchDirs.size} dirs")
    }
    fun stop() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        scope.cancel()
        Timber.tag("achievements").d("AchievementWatcher stopped")
    }
    private fun checkForNewUnlocks(achFile: File) {
        if (!achFile.exists()) return
        var hasNewUnlocks = false
        try {
            val json = JSONObject(achFile.readText(Charsets.UTF_8))
            for (name in json.keys()) {
                val entry = json.optJSONObject(name) ?: continue
                if (!entry.optBoolean("earned", false)) continue
                if (name in notifiedNames) continue
                notifiedNames.add(name)
                hasNewUnlocks = true
                val displayName = displayNameMap[name] ?: name
                val iconUrl = iconUrlMap[name]
                if (PrefManager.achievementShowNotification) {
                    AchievementNotificationManager.show(displayName, iconUrl)
                }
                Timber.tag("achievements").i("Achievement unlocked: $name ($displayName)")
            }
        } catch (e: Exception) {
            Timber.tag("achievements").w(e, "Failed to parse achievements.json for watcher")
        }
        if (hasNewUnlocks) {
            scheduleUpload()
        }
    }
    private fun scheduleUpload() {
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(UPLOAD_DEBOUNCE_MS)
            performUpload()
        }
    }
    private suspend fun performUpload() {
        if (onUpload == null) {
            Timber.tag("achievements").d("No upload callback set for appId=$appId, skipping upload")
            return
        }
        val allUnlocked = collectUnlockedNames()
        val newToUpload = allUnlocked - uploadedNames
        if (newToUpload.isEmpty()) return
        Timber.tag("achievements").d("Uploading ${newToUpload.size} new achievements (${allUnlocked.size} total) for appId=$appId")
        try {
            onUpload.invoke(allUnlocked, watchDirs)
            uploadedNames.addAll(allUnlocked)
            Timber.tag("achievements").i("Achievement upload succeeded for appId=$appId")
        } catch (e: Exception) {
            Timber.tag("achievements").e(e, "Achievement upload failed for appId=$appId, will retry on next unlock")
        }
    }
    private fun collectUnlockedNames(): Set<String> {
        val unlocked = mutableSetOf<String>()
        for (dir in watchDirs) {
            val achFile = File(dir, "achievements.json")
            if (!achFile.exists()) continue
            try {
                val json = JSONObject(achFile.readText(Charsets.UTF_8))
                for (name in json.keys()) {
                    val entry = json.optJSONObject(name) ?: continue
                    if (entry.optBoolean("earned", false)) unlocked.add(name)
                }
            } catch (e: Exception) {
                Timber.tag("achievements").w(e, "Failed to read achievements.json in ${dir.absolutePath}")
            }
        }
        return unlocked
    }
    companion object {
        private const val UPLOAD_DEBOUNCE_MS = 5_000L
    }
}
