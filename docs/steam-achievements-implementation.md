# Steam Achievements Implementation

## Overview

GameNative implements Steam achievement support using the JavaSteam library to fetch achievement data directly from Steam's servers. This document describes the mechanics of how achievements are listed, fetched, and stored.

## Architecture

### Core Components

1. **SteamService** - Long-running service managing Steam client connection
2. **SteamUserStats Handler** - JavaSteam handler for achievement/stats operations  
3. **AchievementInfo** - Data model for individual achievements
4. **AchievementSchema** - Container for achievements and stats for a game

### Data Flow

```
Steam Server → JavaSteam Protocol → SteamUserStats Handler → getAchievementSchema() → AchievementInfo objects
```

## Fetching Achievements

### Method: `SteamService.getAchievementSchema(appId: Int)`

This suspend function fetches achievement data for a specific Steam app ID.

#### Prerequisites

- Active Steam connection (`SteamService` running)
- User logged in (valid `steamId`)
- `SteamUserStats` handler initialized

#### Process

1. **Request User Stats from Steam**
   ```kotlin
   val job = userStats.getUserStats(appId, steamId)
   val callback = job.await()
   ```
   - Uses Steam protocol to request achievement/stats data
   - Returns `UserStatsCallback` containing schema and unlock data

2. **Parse Raw Achievement Blocks**
   - Steam returns achievements as "blocks" (typically one per game, more for DLC)
   - Each block contains:
     - `achievementId`: Block identifier
     - `unlockTime`: Array of Unix timestamps (one per achievement)
     - Schema metadata in KeyValue format

3. **Expand Achievements**
   - JavaSteam's `getExpandedAchievements()` parses schema to create individual achievement objects
   - **IMPORTANT BUG**: JavaSteam's method doesn't correctly map unlock times
   - **Workaround**: We use the array index to map unlock times from raw block:
     ```kotlin
     val correctUnlockTime = rawUnlockTimes.getOrNull(index) ?: 0
     ```

4. **Construct Achievement Objects**
   - For each expanded achievement:
     - Extract metadata (name, display name, description, icons)
     - Map correct unlock time using index
     - Build CDN URLs for icons:
       ```
       https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/{appId}/{iconHash}
       ```
     - Create `AchievementInfo` with unlock status

5. **Sort Achievements**
   - Sorted by `unlockTimestamp` descending
   - Unlocked achievements appear first (most recent first)
   - Locked achievements (timestamp = 0) appear last

## Data Structures

### AchievementInfo

```kotlin
data class AchievementInfo(
    val apiName: String,              // Internal name (e.g., "ACH_LEGENDARY_WEAPON")
    val displayName: String,          // Localized name (e.g., "Legendary Weapon")
    val description: String,          // Localized description
    val hidden: Boolean,              // Whether achievement is hidden
    val icon: String,                 // CDN URL for colored icon
    val iconGray: String,             // CDN URL for grayscale (locked) icon
    val isUnlocked: Boolean,          // Unlock status (timestamp > 0)
    val unlockTimestamp: Long,        // Unix timestamp in seconds (0 if locked)
    val defaultValue: Int,            // 1 if unlocked, 0 if locked
    val statName: String = "",        // Associated stat name (optional)
)
```

### AchievementSchema

```kotlin
data class AchievementSchema(
    val achievements: List<AchievementInfo>,
    val stats: List<StatInfo>,        // Basic stat support (WIP)
)
```

## Storage

### Current Implementation: No Persistence

**Achievements are NOT stored in the local database.** They are fetched on-demand from Steam servers each time they are requested.

#### Rationale

1. **Single Source of Truth**: Steam servers are authoritative
2. **Real-time Data**: Always shows current unlock status
3. **No Sync Issues**: No need to sync local DB with Steam
4. **Reduced Complexity**: No local cache invalidation logic

#### Implications

- Requires active internet connection to view achievements
- Requires active Steam session (user logged in)
- Small latency when opening achievement dialog (~500ms-2s)

### Future Consideration: Offline Caching

If offline support is needed, achievements could be cached in Room database:

```kotlin
@Entity(tableName = "steam_achievements")
data class CachedAchievement(
    @PrimaryKey val id: String,       // "appId_apiName"
    val appId: Int,
    val apiName: String,
    val displayName: String,
    val description: String,
    val isUnlocked: Boolean,
    val unlockTimestamp: Long,
    val cachedAt: Long,               // Cache timestamp
    // ... other fields
)
```

## Achievement Triggering

### Current Implementation: READ-ONLY

**GameNative currently does NOT support unlocking achievements.** The implementation is read-only for viewing achievement status.

### How Achievement Unlocking Would Work

If achievement triggering were to be implemented, here's how it would function:

#### 1. In-Game Unlocking (Wine/Proton)

Games running under Wine/Proton can unlock achievements if:

1. **Steam API DLL Available**
   - `steam_api.dll` / `steam_api64.dll` present in game directory
   - Game uses Steamworks SDK

2. **Steam Client Integration**
   - GameNative's SteamService acts as Steam client
   - Wine games communicate with "Steam" via IPC/sockets
   - Requires implementing Steam API hook layer in Wine environment

3. **Current Limitation**
   - Wine games are sandboxed and cannot directly communicate with SteamService
   - Would require IPC bridge (named pipes, Unix sockets, or shared memory)
   - Complex integration with Wine's Steam stub implementation

#### 2. Manual Unlocking (Future Feature)

For testing or offline mode, achievements could be manually unlocked via JavaSteam:

```kotlin
// Hypothetical implementation
suspend fun unlockAchievement(appId: Int, achievementName: String): Boolean {
    val userStats = _steamUserStats ?: return false
    
    // Request current stats
    val statsJob = userStats.getUserStats(appId, userSteamId)
    val callback = statsJob.await()
    
    // Set achievement as unlocked
    userStats.setAchievement(achievementName, true)
    
    // Store stats to Steam server
    val storeJob = userStats.storeUserStats(appId)
    val result = storeJob.await()
    
    return result.result == EResult.OK
}
```

#### 3. Server-Side Requirements

For achievements to persist on Steam:

1. **Authentication**: Valid Steam session token
2. **Ownership Verification**: User must own the game
3. **Stat Storage**: Call `storeUserStats()` to push changes to Steam
4. **VAC Compliance**: Official games may reject non-genuine unlocks

### Why Triggering Isn't Implemented

1. **Technical Complexity**
   - Requires Wine↔Android IPC bridge
   - Steam API DLL interception in Wine
   - Complex multi-process communication

2. **Account Safety**
   - Manual unlocking could trigger VAC/game bans
   - Would need clear warnings to users

3. **Limited Value**
   - Most users want to *view* achievements, not unlock them manually
   - Organic unlocks should come from gameplay

4. **Alternative Solution**
   - Games running under Wine with working Steam integration can already unlock achievements if proper Steam API DLLs are present
   - GameNative focuses on displaying status rather than manipulating it

## JavaSteam Integration

### Library Version

- **Dependency**: `io.github.joshuatam:javasteam:1.8.0-11-SNAPSHOT`
- **Handler**: `SteamUserStats` (from `in.dragonbra.javasteam.steam.handlers.steamuserstats`)

### Key Methods Used

1. **getUserStats(appId, steamId)**
   - Fetches achievement schema and user's unlock data
   - Returns `UserStatsCallback`

2. **getExpandedAchievements()**
   - Expands bit-level achievement schema to individual objects
   - Contains bug: unlock times not mapped correctly (workaround applied)

### Known Issues

#### JavaSteam Bug: Unlock Time Mapping

**Problem**: `getExpandedAchievements()` returns all achievements with `unlockTimestamp = 0` even when unlocked.

**Root Cause**: Library doesn't correctly map bit indices to unlock time array positions.

**Workaround**: 
```kotlin
// Use array index instead of relying on library's unlock time
val correctUnlockTime = rawUnlockTimes.getOrNull(index) ?: 0
```

**Evidence**:
- Raw block: `[0, 0, 1520684312, 0, ...]` (achievement at index 2 unlocked)
- Expanded achievement: `unlockTimestamp = 0` (incorrect)
- After workaround: `unlockTimestamp = 1520684312` (correct)

## Error Handling

### Failure Scenarios

1. **No Internet Connection**
   - `getUserStats()` call fails
   - Return `null` from `getAchievementSchema()`

2. **User Not Logged In**
   - `userSteamId` is `null`
   - Return `null` immediately

3. **Game Not Owned**
   - Steam returns `EResult` != OK
   - Return `null` and log warning

4. **No Achievements**
   - `achievementBlocks` is empty
   - Return `null` (game has no achievement support)

5. **Handler Not Initialized**
   - `_steamUserStats` is `null`
   - Return `null` and log warning

### Logging

All operations logged with Timber:
```kotlin
Timber.tag("SteamService\$getAchievementSchema")
    .d("Fetching achievements for app $appId")
```

Debug builds include verbose logging:
- Raw block data
- Expanded achievement details
- Unlock time mapping verification

## Performance Considerations

### Network Latency

- Average fetch time: 500ms-2s (depending on connection)
- Blocking call: uses `withContext(Dispatchers.IO)`
- UI shows loading state during fetch

### Memory Usage

- Schema size: ~5-10KB for typical game (10-50 achievements)
- Images loaded on-demand via Coil
- Not cached in memory after dialog closes

### CDN Image Loading

- Icons loaded from Cloudflare Steam CDN
- Automatic caching by Coil library
- Grayscale icons shown for locked achievements
- Colored icons shown for unlocked achievements

## Security & Privacy

### Data Transmission

- All communication via JavaSteam's encrypted protocol
- No achievement data sent to third parties
- No local storage of achievement data

### Account Safety

- Read-only implementation: no risk of triggering anti-cheat
- No modification of Steam account data
- Official Steam protocol usage (no scraping/reverse engineering)

## Future Enhancements

### Potential Features

1. **Offline Caching**
   - Store last-fetched achievement state in Room database
   - Display cached data when offline
   - Sync on reconnection

2. **Achievement Statistics**
   - Show global unlock percentages (if Steam API provides)
   - Achievement rarity indicators
   - Progress tracking for multi-step achievements

3. **Notification System**
   - Toast notifications when achievements unlock during gameplay
   - Achievement unlock sound effects
   - Integration with Android notification system

4. **Achievement Tracking**
   - Track progress toward incomplete achievements
   - Show hints for hidden achievements (if unlocked)
   - Achievement guides integration

5. **Multi-Game View**
   - Aggregate achievement stats across library
   - Completion percentage per game
   - Achievement showcase (favorite achievements)

### Implementation Challenges

1. **IPC Bridge for Wine**
   - Requires Wine modification or proxy DLL
   - Complex multi-process architecture
   - Performance overhead considerations

2. **Server-Side Push**
   - Implementing `storeUserStats()` correctly
   - Handling race conditions with game client
   - Testing without risking account bans

3. **Offline Sync**
   - Conflict resolution (local vs. server state)
   - Cache invalidation strategy
   - Delta sync for performance

## Conclusion

GameNative's achievement system prioritizes:
- **Simplicity**: On-demand fetching, no local state management
- **Accuracy**: Always shows current Steam server state
- **Safety**: Read-only implementation prevents account issues

The implementation successfully works around JavaSteam library bugs and provides a clean architecture for future enhancements like achievement triggering and offline caching.
