# Proton 10.0 x86_64 Auto-Detection Fix

## Problem
Proton 10.0 x86_64 builds have hardcoded build paths (`/build/install-x86_64/`) compiled into the Wine binaries instead of using the deployment paths. This causes Wine to fail loading `ntdll.so` with errors like:

```
wine: could not load ntdll.so: cannot open shared object file: No such file or directory
```

## Solution
Auto-detect when Proton 10.0 x86_64 is being used and automatically set the `WINEDLLPATH` environment variable to override Wine's hardcoded library search paths.

## Implementation
Modified `BionicProgramLauncherComponent.java` in two places:

### 1. Main Process Launch (`execGuestProgram`)
**Location:** Lines 312-327

Detects Proton 10 installation path and sets `WINEDLLPATH` before launching Wine:

```java
// Auto-detect Proton 10.0 x86_64 and set correct WINEDLLPATH
if (wineInfo != null && wineInfo.path != null) {
    String winePath = wineInfo.path.toLowerCase();
    // Match both proton-10.0-x86_64 and proton-10-x86_64 patterns
    if (winePath.contains("proton-10") && winePath.contains("x86_64")) {
        String protonLibPath = wineInfo.path + "/lib/wine";
        // Only set if not already overridden by user
        if (!envVars.has("WINEDLLPATH")) {
            envVars.put("WINEDLLPATH", protonLibPath);
            Log.d("BionicProgramLauncherComponent", "Auto-detected Proton 10.0 x86_64, setting WINEDLLPATH=" + protonLibPath);
        }
    }
}
```

### 2. Shell Commands (`execShellCommand`)
**Location:** Lines 496-508

Same logic for `wineserver -k` and other Wine utilities:

```java
// Auto-detect Proton 10.0 x86_64 and set correct WINEDLLPATH for shell commands
if (wineInfo != null && wineInfo.path != null) {
    String winePathLower = wineInfo.path.toLowerCase();
    if (winePathLower.contains("proton-10") && winePathLower.contains("x86_64")) {
        String protonLibPath = wineInfo.path + "/lib/wine";
        if (!envVars.has("WINEDLLPATH")) {
            envVars.put("WINEDLLPATH", protonLibPath);
            Log.d("BionicProgramLauncherComponent", "Auto-detected Proton 10.0 x86_64 (shell), setting WINEDLLPATH=" + protonLibPath);
        }
    }
}
```

## How It Works

1. **Detection:** Checks if `wineInfo.path` contains both "proton-10" and "x86_64"
   - Matches: `/opt/proton-10.0-x86_64`, `/opt/proton-10-x86_64`, etc.

2. **Path Construction:** 
   - Takes the Wine installation path (e.g., `/data/data/app.gamenative/files/imagefs/opt/proton-10.0-x86_64`)
   - Appends `/lib/wine` to get the library directory

3. **Environment Override:**
   - Sets `WINEDLLPATH` environment variable
   - Wine checks this variable before using hardcoded paths
   - Only sets if user hasn't already overridden it manually

4. **Result:**
   - Wine finds libraries at `/opt/proton-10.0-x86_64/lib/wine/x86_64-unix/ntdll.so`
   - Container launches successfully!

## Benefits

✅ **Automatic:** No manual configuration needed
✅ **Backward Compatible:** Doesn't affect Proton 9 or other Wine versions
✅ **User Override:** Respects manual `WINEDLLPATH` if already set
✅ **Complete Coverage:** Works for both main process and shell commands
✅ **Flexible Pattern Matching:** Handles both `proton-10.0-x86_64` and `proton-10-x86_64`

## Testing

After rebuilding GameNative with this fix:

1. Import `proton-10.0-x86_64.wcp` package
2. Create a new container or edit existing one
3. Select Proton 10.0 x86_64 as Wine version
4. Launch container
5. Check logcat for: `Auto-detected Proton 10.0 x86_64, setting WINEDLLPATH=/opt/proton-10.0-x86_64/lib/wine`
6. Verify container launches without "could not load ntdll.so" errors

## Alternative Manual Method

Users can also manually set the environment variable in container settings:

1. Open container settings
2. Go to Environment Variables
3. Add: `WINEDLLPATH=/data/data/app.gamenative/files/imagefs/opt/proton-10.0-x86_64/lib/wine`

But with this fix, it's automatic! 🎉

## Technical Details

### Why WINEDLLPATH?

Wine uses this search order for loading DLLs:
1. `WINEDLLPATH` environment variable (if set)
2. Hardcoded paths compiled into wine64 binary
3. System library paths

Our Proton 10 build has `/build/install-x86_64/x86_64/lib` hardcoded, which doesn't exist on Android devices. By setting `WINEDLLPATH`, we override the broken hardcoded paths.

### Why Not Rebuild?

While rebuilding Wine with correct `--prefix` paths would be ideal, this fix:
- Works immediately without waiting for rebuild
- Handles edge cases (different deployment paths, custom installations)
- Provides fallback for any future builds with path issues
- Allows testing before committing to full rebuild

## Files Modified

- `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java`
  - Lines 312-327: Main process launch auto-detection
  - Lines 496-508: Shell command auto-detection

## Date
January 11, 2026
