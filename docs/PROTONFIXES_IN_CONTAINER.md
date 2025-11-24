# Running Protonfixes in Glibc Container

## Overview

GE-Proton includes 315+ Python-based game compatibility fixes (protonfixes) that can be executed directly within the glibc container instead of extracting them to JSON and reimplementing in Java/Kotlin.

## Why Run Protonfixes in Container?

**Advantages:**
- ✅ **No JSON extraction needed** - Use fixes directly from GE-Proton
- ✅ **No reimplementation** - Avoid translating 315+ Python scripts to Java/Kotlin
- ✅ **Full functionality** - Python fixes can do conditional logic, file patching, registry edits
- ✅ **Future-proof** - New GE-Proton releases work immediately
- ✅ **Maintainable** - Updates come from upstream GE-Proton
- ✅ **Proven** - Same fixes used by Linux Proton users

**Alternative (JSON extraction):**
- ❌ Requires parsing Python AST and converting to static JSON
- ❌ Need Java/Kotlin reimplementation of fix application logic
- ❌ Limited to static configuration (env vars, DLL overrides)
- ❌ Manual updates for each GE-Proton release
- ❌ Can't handle complex conditional logic or file patching

## Protonfixes Architecture

### What Protonfixes Does

Protonfixes applies game-specific compatibility tweaks before launching Wine/Proton:

1. **Environment Variables** - Set DXVK config, Wine settings, Proton options
2. **DLL Overrides** - Configure which DLLs to use (native vs builtin)
3. **Registry Modifications** - Set Windows registry keys for game compatibility
4. **File Patching** - Modify game configuration files
5. **Conditional Logic** - Apply fixes based on game version, system capabilities

### Protonfixes Structure

```
protonfixes/
├── __init__.py           # Main module initialization
├── fix.py                # Fix application utilities
├── logger.py             # Logging utilities
├── util.py               # Helper functions
├── gamefixes-steam/      # Steam game fixes (315+ files)
│   ├── 70420.py          # Half-Life 2
│   ├── 45750.py          # Sekiro
│   ├── 834530.py         # Elden Ring
│   └── ...               # Many more
├── gamefixes-gog/        # GOG game fixes
├── gamefixes-egs/        # Epic Games Store fixes
├── gamefixes-ea/         # EA fixes
└── ...                   # Other platforms
```

### Example Game Fix

From `70420.py` (Half-Life 2):

```python
from protonfixes import util

def main():
    """Half-Life 2 - DXVK fixes"""
    util.set_environment('DXVK_FRAME_RATE', '60')
    util.protontricks('d3dx9')
```

## Implementation Plan

### 1. Container Requirements

Add Python3 to the glibc Ubuntu container:

```dockerfile
# In container build script
apt-get install -y python3 python3-pip
```

**Estimated size:** ~10-15MB (much smaller than Chaquopy's 20MB+ Android embedding)

### 2. Protonfixes Location

Protonfixes are bundled in the .wcp file and extracted to:
```
/opt/proton-ge-10-25-x86_64/protonfixes/
```

### 3. Environment Variables

Protonfixes expects these environment variables:

```bash
# Required
STEAM_COMPAT_DATA_PATH="/data/data/com.example.app/wine-prefixes/<container>/<game>"

# Optional (disable fixes)
PROTONFIXES_DISABLE=1

# Proton environment
PYTHONPATH="/opt/proton-ge-10-25-x86_64"
PROTON_DLL_COPY="*"
```

### 4. Integration Points

#### Implementation in GlibcProgramLauncherComponent

Add protonfix execution before launching Wine in `GlibcProgramLauncherComponent.java`:

```java
private int execGuestProgram() {
    Context context = environment.getContext();
    ImageFs imageFs = ImageFs.find(context);
    File rootDir = imageFs.getRootDir();

    // ... existing setup code ...

    // Apply protonfixes BEFORE launching Wine (for glibc containers only)
    if (Container.GLIBC.equals(container.getContainerVariant()) && wineProfile != null) {
        applyProtonfixes(context, rootDir, imageFs.wineprefix);
    }

    // ... existing Wine launch code ...
}

private void applyProtonfixes(Context context, File rootDir, String winePrefix) {
    // Extract Steam App ID from container ID
    Integer steamAppId = null;
    try {
        String containerId = container.getId();
        steamAppId = app.gamenative.utils.ContainerUtils.INSTANCE.extractGameIdFromContainerId(containerId);
    } catch (Exception e) {
        Log.d(TAG, "Could not extract Steam App ID from container: " + e.getMessage());
        return;
    }

    if (steamAppId == null) {
        Log.d(TAG, "No Steam App ID available for protonfixes");
        return;
    }

    // Get Proton path
    String protonPath = wineProfile == null ? null
        : ContentsManager.getSourceFile(context, wineProfile, "").getAbsolutePath();
    if (protonPath == null || protonPath.isEmpty()) {
        Log.d(TAG, "No Proton path available");
        return;
    }

    // Check if protonfix exists
    File protonfixFile = new File(protonPath, "protonfixes/gamefixes-steam/" + steamAppId + ".py");
    if (!protonfixFile.exists()) {
        Log.d(TAG, "No protonfix found for Steam App ID: " + steamAppId);
        return;
    }

    Log.i(TAG, "Applying protonfix for Steam App ID: " + steamAppId);

    // Check Python3 availability
    File python3 = new File(rootDir, "usr/bin/python3");
    if (!python3.exists()) {
        Log.w(TAG, "Python3 not found in container, cannot apply protonfixes");
        return;
    }

    // Build and execute Python command via Box64
    String box64Path = rootDir.getPath() + "/usr/local/bin/box64";
    String pythonCmd = String.format(
        "STEAM_COMPAT_DATA_PATH='%s' PYTHONPATH='%s' PROTON_DLL_COPY='*' %s %s/usr/bin/python3 -c \"" +
        "import sys; sys.path.insert(0, '%s'); " +
        "from protonfixes.gamefixes_steam._%d import main; main()\"",
        winePrefix, protonPath, box64Path, rootDir.getPath(), protonPath, steamAppId
    );

    try {
        // Execute protonfix
        EnvVars protonfixEnv = new EnvVars();
        protonfixEnv.put("HOME", imageFs.home_path);
        protonfixEnv.put("USER", ImageFs.USER);
        protonfixEnv.put("TMPDIR", rootDir.getPath() + "/tmp");

        int exitCode = ProcessHelper.exec(pythonCmd, protonfixEnv.toStringArray(), rootDir);
        if (exitCode == 0) {
            Log.i(TAG, "Successfully applied protonfix for Steam App ID: " + steamAppId);
        } else {
            Log.w(TAG, "Protonfix exited with code: " + exitCode);
        }
    } catch (Exception e) {
        Log.e(TAG, "Failed to apply protonfix: " + e.getMessage(), e);
    }
}
```

**Key Points:**
- Protonfixes run **before** Wine launches
- Only execute for glibc containers with Proton builds
- Extract Steam App ID from container ID
- Use Box64 to run Python3 in the container
- Set required environment variables (STEAM_COMPAT_DATA_PATH, PYTHONPATH, PROTON_DLL_COPY)
- Log results for debugging

### 5. Game ID Mapping

GameNative needs to map games to Steam App IDs:

```kotlin
// In ContentProfile or game metadata
data class GameMetadata(
    val name: String,
    val steamAppId: String?,  // For protonfix lookup
    val exePath: String
)

// Example mappings
val gameIds = mapOf(
    "Half-Life 2" to "220",
    "Elden Ring" to "1245620",
    "Sekiro" to "814380",
    "Hades" to "1145360"
)
```

## Testing Plan

### 1. Verify Protonfixes in .wcp

```bash
tar -tJf ge-proton-10-25-x86_64.wcp | grep protonfixes | head -20
```

Expected output:
```
protonfixes/
protonfixes/__init__.py
protonfixes/gamefixes-steam/
protonfixes/gamefixes-steam/70420.py
...
```

### 2. Test Python in Container

```bash
# Enter container
adb shell
su
/system/bin/box64 /opt/proton-ge-10-25-x86_64/bin/wine --version

# Test Python
python3 --version
python3 -c "import sys; print(sys.path)"
```

### 3. Test Protonfix Loading

```bash
export PYTHONPATH=/opt/proton-ge-10-25-x86_64
python3 -c "
from protonfixes.gamefixes_steam import 70420
print('Fix loaded successfully')
70420.main()
"
```

### 4. Test with Actual Game

Launch a game with protonfixes enabled and verify:
- Fixes are applied (check environment variables)
- No Python errors in logs
- Game launches successfully

## File Size Impact

**Protonfixes size in .wcp:**
- Python scripts: ~2-3MB (315+ files)
- Total .wcp size: ~420MB (vs 417MB without fixes)

**Container size impact:**
- Python3 runtime: ~10-15MB
- No additional dependencies needed (pure Python)

**Total overhead:** ~13-18MB (much better than 20MB+ Chaquopy + Java reimplementation)

## Benefits Summary

| Approach | Complexity | Maintenance | Flexibility | Size |
|----------|-----------|-------------|-------------|------|
| **Container Python** | Low | Auto-updates | Full | +15MB |
| JSON Extraction | High | Manual | Limited | +5MB |
| No Fixes | N/A | N/A | None | 0MB |

**Recommendation:** Run protonfixes in container - best balance of simplicity, functionality, and maintainability.

## Future Enhancements

1. **Fix Caching** - Cache which fixes work for specific games
2. **Custom Fixes** - Allow users to add custom protonfixes
3. **Fix Reporting** - Log which fixes were applied for debugging
4. **Selective Application** - UI toggle to enable/disable fixes per game
5. **Performance Profiling** - Measure fix impact on game performance

## References

- [GE-Proton GitHub](https://github.com/GloriousEggroll/proton-ge-custom)
- [Protonfixes Documentation](https://github.com/simons-public/protonfixes)
- Original design: `GE_PROTON_FIXES_PORTING_DESIGN.md` (JSON extraction approach)
