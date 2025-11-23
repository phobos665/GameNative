# GLIBC Container Support Requirements

**Document Version:** 1.0
**Date:** November 23, 2025
**Status:** Planning Document

## Executive Summary

This document outlines the requirements and implementation steps needed to enable full GLIBC container support in GameNative, including local import, online download, and game launching capabilities.

Currently, GLIBC Wine/Proton binaries are **blocked during import** with the message: "This Wine/Proton build requires GLIBC containers and is not compatible with GameNative. Please use ARM64/bionic builds only."

## Current Architecture Overview

### Container Variants

GameNative supports two container variants that determine the execution environment:

1. **Bionic Variant** (`Container.BIONIC = "bionic"`)
   - Default for non-Turnip-capable devices
   - Uses `BionicProgramLauncherComponent`
   - Emulation: Box86/Box64 for x86 translation or FEXCore for ARM64EC
   - Compatible with ARM64/bionic Wine/Proton builds

2. **GLIBC Variant** (`Container.GLIBC = "glibc"`)
   - Default for Turnip-capable devices
   - Uses `GlibcProgramLauncherComponent`
   - Emulation: FEXCore emulator for x86_64
   - Library paths: `/usr/lib/arm-linux-gnueabihf` (32-bit), `/usr/lib` (64-bit)
   - **Currently only supports built-in Wine** (no custom Wine/Proton imports)

### Binary Variant Detection

The system detects Wine/Proton binary type by reading ELF headers:
- **Bionic binaries**: Contain `/system/bin/linker` interpreter
- **GLIBC binaries**: Contain `/lib64/ld-linux` or `/lib/ld-linux` interpreter

Location: `WineProtonManagerDialog.kt:detectBinaryVariant()`

```kotlin
private fun detectBinaryVariant(installDir: File): String {
    val bytes = binaryFile.inputStream().use { ... }
    val content = String(bytes, Charsets.ISO_8859_1)

    return when {
        content.contains("/system/bin/linker") -> "bionic"
        content.contains("/lib64/ld-linux") || content.contains("/lib/ld-linux") -> "glibc"
        else -> "unknown"
    }
}
```

---

## Phase 1: Enable GLIBC Wine/Proton Import

### 1.1 Remove Import Blocking

**Location:** `app/src/main/java/app/gamenative/ui/screen/settings/WineProtonManagerDialog.kt`

**Current Code (lines 987-1000):**
```kotlin
//! Currently we are filteirng out GLIBC - May support them in future.
if (binaryVariant == "glibc") {
    Timber.tag(tag).w("Glibc Variant Detected: Currently we do not support GLIBC Imports")
    try {
        ContentsManager.cleanTmpDir(context)
    } catch (e: Exception) {
        Timber.tag(tag).e(e, "Failed to clean tmp dir")
    }
    return false to context.getString(R.string.wine_proton_glibc_incompatible)
}
```

**Required Changes:**
1. Remove the GLIBC rejection block entirely
2. Allow GLIBC profiles to pass validation
3. Update the validation logic to accept both binary types

**Modified Code:**
```kotlin
val tmpDir = ContentsManager.getTmpDir(context)
val binaryVariant = detectBinaryVariant(tmpDir)
Timber.tag(tag).d("Detected binary variant: $binaryVariant")

// Store variant metadata in profile for later container compatibility checks
// (Future enhancement: tag ContentProfile with detected variant)

return true to null
```

### 1.2 Profile Metadata Enhancement

**Location:** `app/src/main/java/com/winlator/contents/ContentProfile.java`

**Recommended Addition:**
Add a field to track binary variant in the profile:

```java
public class ContentProfile {
    // Existing fields...

    private String binaryVariant; // "bionic", "glibc", or "unknown"

    public String getBinaryVariant() { return binaryVariant; }
    public void setBinaryVariant(String variant) { this.binaryVariant = variant; }
}
```

**Update profile.json schema:**
```json
{
    "wine": "wine-10.0-glibc",
    "vercode": 1,
    "verName": "wine-10.0-glibc",
    "binaryVariant": "glibc",
    "desc": "Wine 10.0 for GLIBC containers",
    "wineBinPath": "bin",
    "wineLibPath": "lib64"
}
```

### 1.3 Update String Resources

**Location:** `app/src/main/res/values/strings.xml`

**Current String (line 399):**
```xml
<string name="wine_proton_glibc_incompatible">
    This Wine/Proton build requires GLIBC containers and is not compatible with GameNative.
    Please use ARM64/bionic builds only.
</string>
```

**Modified String (make it informational rather than blocking):**
```xml
<string name="wine_proton_glibc_detected">
    GLIBC Wine/Proton detected. This version can only be used with GLIBC containers.
</string>
```

---

## Phase 2: Online Download Support

### 2.1 Manifest Integration

**Location:** `https://github.com/utkarshdalal/gamenative-landing-page/data/manifest.json`

**Required Manifest Entries:**
Add GLIBC Wine/Proton variants to the manifest:

```json
{
    "wine-10.0-glibc": "wine-10.0-glibc.txz",
    "proton-10.0-glibc": "proton-10.0-glibc.txz"
}
```

### 2.2 Dropdown UI Enhancement

**Location:** `WineProtonManagerDialog.kt`

**Current Code (lines 530-575):**
- Dropdown only shows filename keys from manifest
- No variant filtering or labeling

**Required Changes:**
1. Add variant labels to dropdown display
2. Group by variant type (optional UX improvement)
3. Show compatibility warning when selecting mismatched variant

**Enhanced Dropdown:**
```kotlin
// Parse manifest with variant detection
val wineProtonManifestWithVariants = jsonObject.entries
    .filter { it.key.startsWith("wine", ignoreCase = true) ||
             it.key.startsWith("proton", ignoreCase = true) }
    .associate {
        val filename = it.value.toString().removeSurrounding("\"")
        val variant = detectVariantFromFilename(it.key)
        val label = "${it.key} [$variant]"
        label to filename
    }

// In dropdown rendering
DropdownMenuItem(
    text = {
        Row {
            Text(key)
            Spacer(modifier = Modifier.width(8.dp))
            Badge(containerColor = if (variant == "glibc")
                MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.secondary
            ) {
                Text(variant.uppercase())
            }
        }
    },
    onClick = { ... }
)
```

### 2.3 Variant Detection Helper

Add to `WineProtonManagerDialog.kt`:

```kotlin
private fun detectVariantFromFilename(filename: String): String {
    return when {
        filename.contains("glibc", ignoreCase = true) -> "glibc"
        filename.contains("bionic", ignoreCase = true) -> "bionic"
        filename.contains("arm64ec", ignoreCase = true) -> "bionic"
        filename.contains("x86_64", ignoreCase = true) &&
            !filename.contains("glibc", ignoreCase = true) -> "bionic"
        else -> "unknown"
    }
}
```

---

## Phase 3: Container Creation & Configuration

### 3.1 Container Variant Selection

**Location:** `app/src/main/java/app/gamenative/ui/component/dialog/ContainerConfigDialog.kt`

**Current Implementation (lines 728-820):**
- Variant dropdown already exists
- Switching variant resets graphics driver and Wine version
- **Wine version only shown for Bionic variant** (line 818-831)

**Required Changes:**
1. Show Wine version dropdown for GLIBC variant
2. Load GLIBC-compatible Wine/Proton versions
3. Filter installed profiles by binary variant

**Enhanced Wine Version Dropdown:**
```kotlin
// Load GLIBC Wine versions (currently only base, needs custom imports)
LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
        try {
            val mgr = ContentsManager(context)
            mgr.syncContents()

            // Filter profiles by binary variant
            val customWineGlibc = profilesToDisplay(
                mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE)
                    ?.filter { it.binaryVariant == "glibc" }
            )
            val customProtonGlibc = profilesToDisplay(
                mgr.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON)
                    ?.filter { it.binaryVariant == "glibc" }
            )

            glibcWineEntries = (glibcWineEntriesBase + customProtonGlibc + customWineGlibc).distinct()
        } catch (_: Exception) {}
    }
}

// In UI (replace lines 818-831)
if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
    val wineIndex = bionicWineEntries.indexOfFirst { it == config.wineVersion }.coerceAtLeast(0)
    SettingsListDropdown(
        colors = settingsTileColors(),
        title = { Text(text = stringResource(R.string.wine_version)) },
        value = wineIndex,
        items = bionicWineEntries,
        onItemSelected = { idx ->
            config = config.copy(wineVersion = bionicWineEntries[idx])
        },
    )
} else if (config.containerVariant.equals(Container.GLIBC, ignoreCase = true)) {
    val wineIndex = glibcWineEntries.indexOfFirst { it == config.wineVersion }.coerceAtLeast(0)
    SettingsListDropdown(
        colors = settingsTileColors(),
        title = { Text(text = stringResource(R.string.wine_version)) },
        value = wineIndex,
        items = glibcWineEntries,
        onItemSelected = { idx ->
            config = config.copy(wineVersion = glibcWineEntries[idx])
        },
    )
}
```

### 3.2 Compatibility Validation

Add validation when creating/editing containers:

```kotlin
// In ContainerConfigDialog save handler
val selectedProfile = mgr.getProfileByEntryName(config.wineVersion)
val profileVariant = selectedProfile?.binaryVariant ?: "unknown"
val containerVariant = config.containerVariant

if (profileVariant != "unknown" && profileVariant != containerVariant) {
    // Show error dialog
    showCompatibilityError = true
    errorMessage = "Wine/Proton variant ($profileVariant) does not match container variant ($containerVariant)"
    return@launch
}
```

---

## Phase 4: Game Launch Support

### 4.1 Component Selection Logic

**Location:** `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`

**Current Implementation (lines 1068-1091):**
```kotlin
val usrGlibc: Boolean = container.getContainerVariant().equals(Container.GLIBC, ignoreCase = true)
val guestProgramLauncherComponent = if (usrGlibc) {
    Timber.i("Setting guestProgramLauncherComponent to GlibcProgramLauncherComponent")
    GlibcProgramLauncherComponent(
        contentsManager,
        contentsManager.getProfileByEntryName(container.wineVersion),
    )
} else {
    Timber.i("Setting guestProgramLauncherComponent to BionicProgramLauncherComponent")
    BionicProgramLauncherComponent(
        contentsManager,
        contentsManager.getProfileByEntryName(container.wineVersion),
    )
}
```

**Status:** ✅ Already correct - selects launcher based on container variant

**Verification Needed:**
Ensure `GlibcProgramLauncherComponent` properly loads custom Wine profiles:

```java
// In GlibcProgramLauncherComponent.java (line 189-192)
String winePath = wineProfile == null ? imageFs.getWinePath() + "/bin"
    : ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineBinPath).getAbsolutePath();
```

This already supports custom Wine profiles ✅

### 4.2 Profile Resolution

**Location:** `app/src/main/java/com/winlator/core/WineInfo.java`

**Current Code (lines 158-216):**
```java
public static WineInfo fromIdentifier(Context ctx, String identifier) {
    if (identifier == null || identifier.isEmpty()) {
        return MAIN_WINE_VERSION;
    }

    ContentsManager mgr = new ContentsManager(ctx);
    ContentProfile profile = mgr.getProfileByEntryName(identifier);

    if (profile != null) {
        // Custom profile found
        String path = ContentsManager.getSourceFile(ctx, profile, profile.wineBinPath).getAbsolutePath();
        return new WineInfo(identifier, path, profile.type);
    }

    // Fallback to built-in
    return MAIN_WINE_VERSION;
}
```

**Status:** ✅ Should work for GLIBC profiles once imported

**Testing Needed:**
- Verify profile lookup works with GLIBC identifiers
- Ensure path resolution returns correct GLIBC Wine paths
- Test fallback behavior when profile not found

### 4.3 Library Path Configuration

**Location:** `app/src/main/java/com/winlator/xenvironment/components/GlibcProgramLauncherComponent.java`

**Current Library Paths (lines 189-201):**
```java
envVars.put("LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib");
envVars.put("BOX64_LD_LIBRARY_PATH", imageFs.getRootDir().getPath() + "/usr/lib/x86_64-linux-gnu");

if ((new File(imageFs.getGlibc64Dir(), "libandroid-sysvshm.so")).exists() ||
    (new File(imageFs.getGlibc32Dir(), "libandroid-sysvshm.so")).exists())
    envVars.put("LD_PRELOAD", "libredirect.so libandroid-sysvshm.so");
```

**Required Changes:**
1. Append custom Wine lib paths when using imported Wine/Proton
2. Ensure proper precedence in `LD_LIBRARY_PATH`

**Enhanced Configuration:**
```java
private int execGuestProgram() {
    // ... existing setup ...

    String winePath = wineProfile == null ? imageFs.getWinePath() + "/bin"
            : ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineBinPath).getAbsolutePath();
    envVars.put("PATH", winePath + ":" +
            imageFs.getRootDir().getPath() + "/usr/bin:" +
            imageFs.getRootDir().getPath() + "/usr/local/bin");

    // Add custom Wine library paths
    String ldLibraryPath = imageFs.getRootDir().getPath() + "/usr/lib";
    if (wineProfile != null && wineProfile.wineLibPath != null) {
        File wineLibDir = ContentsManager.getSourceFile(context, wineProfile, wineProfile.wineLibPath);
        if (wineLibDir.exists()) {
            ldLibraryPath = wineLibDir.getAbsolutePath() + ":" + ldLibraryPath;
        }
    }
    envVars.put("LD_LIBRARY_PATH", ldLibraryPath);

    // ... rest of existing code ...
}
```

---

## Phase 5: Testing & Validation

### 5.1 Test Matrix

| Test Case | Variant | Wine Type | Expected Result |
|-----------|---------|-----------|-----------------|
| Import GLIBC Wine from file | GLIBC | wine-10.0-glibc.txz | ✅ Import succeeds |
| Import GLIBC Proton from file | GLIBC | proton-10.0-glibc.txz | ✅ Import succeeds |
| Download GLIBC Wine online | GLIBC | manifest entry | ✅ Download & install succeeds |
| Create GLIBC container | GLIBC | wine-10.0-glibc | ✅ Container created |
| Launch game in GLIBC container | GLIBC | wine-10.0-glibc | ✅ Game launches |
| Variant mismatch warning | BIONIC | wine-10.0-glibc | ⚠️ Warning shown, blocked |
| Variant mismatch warning | GLIBC | proton-9.0-arm64ec | ⚠️ Warning shown, blocked |

### 5.2 Validation Checklist

#### Import Validation
- [ ] GLIBC Wine/Proton files pass `detectBinaryVariant()` check
- [ ] `ContentProfile` stores binary variant metadata
- [ ] Installed GLIBC profiles appear in manager list
- [ ] Deletion of GLIBC profiles works correctly

#### Container Configuration
- [ ] GLIBC Wine versions appear in Wine version dropdown
- [ ] Switching between variants resets Wine version appropriately
- [ ] Compatibility validation prevents mismatched Wine/container variants
- [ ] Container saves with correct `containerVariant` field

#### Game Launch
- [ ] `GlibcProgramLauncherComponent` selected for GLIBC containers
- [ ] Custom GLIBC Wine profile loaded correctly
- [ ] Wine binary path resolved from profile
- [ ] Library paths include custom Wine lib directories
- [ ] Game process starts without errors

#### Error Handling
- [ ] Graceful fallback if GLIBC profile not found
- [ ] Clear error messages for variant mismatches
- [ ] Proper cleanup if launch fails

---

## Phase 6: Documentation & User Guidance

### 6.1 In-App Help Text

Add explanatory text in the Wine/Proton manager:

```kotlin
if (wineProtonManifest.isNotEmpty()) {
    InfoCard(
        icon = Icons.Filled.Info,
        title = "About Wine/Proton Variants",
        description = """
            Wine/Proton packages come in two variants:

            • Bionic: For ARM64EC and x86_64 translation (Box64/FEXCore)
            • GLIBC: For native GLIBC containers with Turnip driver support

            Make sure to select a Wine/Proton version that matches your container variant.
        """.trimIndent()
    )
}
```

### 6.2 Container Creation Guidance

Add variant selection help text:

```kotlin
SettingsListDropdown(
    colors = settingsTileColors(),
    title = { Text(text = stringResource(R.string.container_variant)) },
    subtitle = {
        Text(
            text = "GLIBC recommended for Snapdragon 8 Gen 2+ devices. Bionic for others.",
            style = MaterialTheme.typography.bodySmall
        )
    },
    value = variantIndex.value,
    items = containerVariants,
    onItemSelected = { ... }
)
```

---

## Implementation Priority

### High Priority (Core Functionality)
1. ✅ **Phase 1.1**: Remove GLIBC import blocking
2. ✅ **Phase 2.2**: Update dropdown to show GLIBC versions
3. ✅ **Phase 3.1**: Enable GLIBC Wine selection in container config
4. ✅ **Phase 4.3**: Verify library path configuration

### Medium Priority (User Experience)
5. ⚠️ **Phase 1.2**: Add binary variant metadata to profiles
6. ⚠️ **Phase 3.2**: Add compatibility validation
7. ⚠️ **Phase 2.2**: Enhanced dropdown with variant badges
8. ⚠️ **Phase 6**: Add in-app documentation

### Low Priority (Polish)
9. ⚙️ **Phase 2.2**: Group manifest entries by variant
10. ⚙️ **Phase 5**: Comprehensive testing suite

---

## Known Limitations & Risks

### Technical Limitations
1. **GLIBC library dependencies**: Custom GLIBC Wine may require additional system libraries not present in imagefs
2. **FEXCore compatibility**: GLIBC variant relies on FEXCore emulator for x86_64 translation
3. **Performance**: GLIBC containers may perform differently than Bionic due to emulation layer differences

### Risk Mitigation
1. **Variant mismatch crashes**: Implement strict validation before container launch
2. **Missing dependencies**: Detect missing libs and show helpful error messages
3. **Profile corruption**: Add version code to GLIBC profiles for migration safety

### Testing Gaps
- No automated tests for variant detection
- Manual testing required for each GLIBC Wine/Proton version
- Device-specific compatibility unknowns (especially non-Snapdragon devices)

---

## Success Criteria

GLIBC support is considered complete when:
1. ✅ Users can import GLIBC Wine/Proton packages locally
2. ✅ Users can download GLIBC Wine/Proton from online manifest
3. ✅ Users can create containers with GLIBC variant
4. ✅ Users can select GLIBC Wine/Proton versions in container config
5. ✅ Games launch successfully in GLIBC containers with custom Wine
6. ✅ Clear error messages shown for variant mismatches
7. ✅ No regressions in Bionic variant functionality

---

## References

### Code Locations
- **Variant detection**: `WineProtonManagerDialog.kt:1091-1109`
- **Launcher selection**: `XServerScreen.kt:1068-1091`
- **GLIBC component**: `GlibcProgramLauncherComponent.java`
- **Container variant**: `Container.java:55-56, 127, 433-438`
- **Profile management**: `ContentsManager.java`

### Related Documentation
- [Proton File Structure Reference](./PROTON_FILE_STRUCTURE_REFERENCE.md)
- [Proton Comparison and Conversion](./PROTON_COMPARISON_AND_CONVERSION.md)
- [Copilot Instructions](./.github/copilot-instructions.md)

---

**Document End**
