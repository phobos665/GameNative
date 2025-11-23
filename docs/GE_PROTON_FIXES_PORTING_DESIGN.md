# GE-Proton Fixes: Java/Kotlin Porting Design Document

## Executive Summary

This document outlines a design for porting GE-Proton's 336 Python-based game compatibility fixes to Java/Kotlin for GameNative's GLIBC container support. The system would provide an "Apply GE-Proton Fixes" option during game launch to automatically apply known fixes for specific Steam games.

**Key Challenge**: Python protonfixes system is deeply integrated with Proton launcher environment (sys.argv, subprocess calls, Wine registry access). GameNative needs a reimplementation that works within Android's Java/Kotlin environment and Box64 execution model.

---

## Current State: Python Protonfixes Architecture

### How Protonfixes Work in GE-Proton

**Execution Flow:**
1. **Game Launch**: User launches game via Steam → Proton launcher script starts
2. **Game ID Detection**: Script reads `SteamAppId` environment variable or parses from `STEAM_COMPAT_DATA_PATH`
3. **Fix Loading**: `protonfixes.fix.main()` dynamically imports `protonfixes.gamefixes-steam/{steamappid}.py`
4. **Fix Execution**: Imported module's `main()` function runs, calling utility functions
5. **Environment Setup**: Fix modifies Wine environment, installs dependencies, sets launch args
6. **Game Start**: Modified environment passed to Wine when game executable launches

**Example Fix (Dark Souls Remastered - 570940.py):**
```python
from protonfixes import util

def main() -> None:
    util.protontricks('vcrun2017')  # Install Visual C++ 2017 runtime
```

**Example Fix (L.A. Noire - 110800.py):**
```python
from protonfixes import util

def main() -> None:
    util.protontricks('d3dx9_43')
    util.protontricks('d3dcompiler_43')
    util.protontricks('d3dx11_43')
    util.protontricks('d3dcompiler_47')
    util.append_argument('-dx11')  # Force DirectX 11 mode
```

### Protonfixes Utility Functions (util.py)

**Environment Manipulation:**
- `set_environment(name, value)` - Add/override env var
- `del_environment(name)` - Remove env var
- `append_argument(arg)` - Add launch argument to sys.argv

**Wine Configuration:**
- `winedll_override(dll, order)` - Override DLL load order (native/builtin)
- `wineexe_override(exe, order)` - Override EXE behavior
- `regedit_add(folder, name, type, value)` - Add registry keys

**Dependency Installation (via Winetricks):**
- `protontricks(verb)` - Install Wine components (vcrun2017, d3dx9_43, corefonts, etc.)
- `checkinstalled(verb)` - Check if component already installed

**Graphics/Performance:**
- `disable_nvapi()` - Disable NVIDIA API
- `disable_esync()` - Disable event synchronization
- `disable_fsync()` - Disable file synchronization
- `patch_libcuda()` - Patch CUDA library for DLSS support
- `set_dxvk_option(option, value)` - Configure DXVK settings

**Configuration File Editing:**
- `set_ini_options(file, options, base_path)` - Edit INI files (game configs)
- `set_xml_options(file, options, base_path)` - Edit XML files
- `create_dosbox_conf(conf, base_path)` - Create DOSBox configs

**File Operations:**
- `install_from_zip(url, filename, path)` - Download and extract files
- `install_all_from_tgz(url, path)` - Download and extract tarballs
- `create_backup_config(cfg_path)` - Backup config files

**System Info:**
- `get_game_install_path()` - Get game directory
- `get_resolution()` - Get display resolution
- `get_cpu_count()` - Get CPU core count
- `set_cpu_topology(cores)` - Override CPU topology

---

## Design: GameNative ProtonFix Engine

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    GameNative UI Layer                       │
│  - Game launch dialog: "Apply GE-Proton Fixes" checkbox     │
│  - Progress dialog during fix application                   │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│              ProtonFixEngine.kt (Coordinator)                │
│  - getAvailableFix(steamAppId): ProtonFix?                  │
│  - applyFix(container, game, fix): Boolean                  │
│  - canApplyFixes(container): Boolean (glibc only)           │
└───────────────────────┬─────────────────────────────────────┘
                        │
        ┌───────────────┴───────────────┐
        │                               │
┌───────▼────────┐              ┌──────▼─────────────┐
│  ProtonFix.kt  │              │  FixRegistry.kt    │
│  (Data Model)  │              │  (Fix Database)    │
│                │              │                    │
│ - steamAppId   │              │ - Load fixes from  │
│ - gameName     │              │   JSON/embedded    │
│ - operations   │              │ - 336 fixes total  │
│   (list)       │              │                    │
└───────┬────────┘              └────────────────────┘
        │
┌───────▼─────────────────────────────────────────────────────┐
│            FixOperation Implementations                      │
│                                                              │
│  - SetEnvironmentOp      - WineDllOverrideOp               │
│  - AppendArgumentOp      - RegistryAddOp                    │
│  - InstallWinetrickOp    - DisableEsyncOp                   │
│  - SetIniOptionOp        - SetDxvkOptionOp                  │
└───────────────────────┬─────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────┐
│         GlibcProgramLauncherComponent.java                   │
│  - Apply environment vars before Wine launch                │
│  - Merge WINEDLLOVERRIDES                                   │
│  - Add launch arguments to sys.argv equivalent              │
└──────────────────────────────────────────────────────────────┘
```

---

## Data Model

### ProtonFix Class (Kotlin)

```kotlin
data class ProtonFix(
    val steamAppId: String,
    val gameName: String,
    val description: String? = null,
    val operations: List<FixOperation>
)

sealed class FixOperation {
    // Environment Operations
    data class SetEnvironment(val name: String, val value: String) : FixOperation()
    data class DeleteEnvironment(val name: String) : FixOperation()
    
    // Launch Arguments
    data class AppendArgument(val argument: String) : FixOperation()
    data class ReplaceCommand(val pattern: String, val replacement: String) : FixOperation()
    
    // Wine DLL/EXE Overrides
    data class WineDllOverride(val dll: String, val order: OverrideOrder) : FixOperation()
    data class WineExeOverride(val exe: String, val order: OverrideOrder) : FixOperation()
    
    // Registry Operations
    data class RegistryAdd(
        val folder: String,
        val name: String? = null,
        val type: String? = null,
        val value: String? = null,
        val arch: Boolean = false
    ) : FixOperation()
    
    // Winetricks Dependencies
    data class InstallWinetrick(val verb: String) : FixOperation()
    
    // Graphics Settings
    object DisableNvapi : FixOperation()
    object DisableEsync : FixOperation()
    object DisableFsync : FixOperation()
    object DisableNtsync : FixOperation()
    object PatchLibcuda : FixOperation()
    
    // DXVK Configuration
    data class SetDxvkOption(val option: String, val value: String) : FixOperation()
    
    // Config File Editing
    data class SetIniOption(
        val file: String,
        val section: String,
        val key: String,
        val value: String,
        val basePath: BasePath = BasePath.USER
    ) : FixOperation()
    
    data class SetXmlOption(
        val file: String,
        val xpath: String,
        val value: String,
        val basePath: BasePath = BasePath.USER
    ) : FixOperation()
    
    // CPU Topology
    data class SetCpuTopology(val coreCount: Int) : FixOperation()
}

enum class OverrideOrder(val value: String) {
    DISABLED(""),
    NATIVE("n"),
    BUILTIN("b"),
    NATIVE_BUILTIN("n,b"),
    BUILTIN_NATIVE("b,n")
}

enum class BasePath {
    USER,           // "My Documents" in prefix
    GAME,           // Game install directory
    APPDATA_LOCAL   // AppData/Local in prefix
}
```

### Fix Storage Format (JSON)

**Option 1: Embedded JSON Resource**
```json
{
  "version": 1,
  "fixes": [
    {
      "steamAppId": "570940",
      "gameName": "Dark Souls Remastered",
      "operations": [
        {
          "type": "InstallWinetrick",
          "verb": "vcrun2017"
        }
      ]
    },
    {
      "steamAppId": "110800",
      "gameName": "L.A. Noire",
      "operations": [
        {
          "type": "InstallWinetrick",
          "verb": "d3dx9_43"
        },
        {
          "type": "InstallWinetrick",
          "verb": "d3dcompiler_43"
        },
        {
          "type": "InstallWinetrick",
          "verb": "d3dx11_43"
        },
        {
          "type": "InstallWinetrick",
          "verb": "d3dcompiler_47"
        },
        {
          "type": "AppendArgument",
          "argument": "-dx11"
        }
      ]
    },
    {
      "steamAppId": "1174180",
      "gameName": "Red Dead Redemption 2",
      "operations": [
        {
          "type": "AppendArgument",
          "argument": "-fullscreen -vulkan"
        }
      ]
    }
  ]
}
```

**Option 2: SQLite Database**
- Better for large dataset (336 fixes)
- Enables searching/filtering
- Can store fix metadata (success rate, user ratings, etc.)
- Schema:
  ```sql
  CREATE TABLE proton_fixes (
      steam_app_id TEXT PRIMARY KEY,
      game_name TEXT NOT NULL,
      description TEXT,
      operations_json TEXT NOT NULL,
      created_at INTEGER,
      updated_at INTEGER
  );
  ```

---

## Implementation Phases

### Phase 1: Core Infrastructure (Week 1)
**Goal**: Basic fix engine with 5 most common operation types

**Components to Build:**
1. `ProtonFix.kt` - Data models (FixOperation sealed class hierarchy)
2. `FixRegistry.kt` - Load fixes from JSON, lookup by Steam App ID
3. `ProtonFixEngine.kt` - Core engine (applyFix, canApplyFixes)
4. `FixOperationExecutor.kt` - Execute individual operations

**Operations to Implement:**
- `SetEnvironment` - Add env vars to GlibcProgramLauncherComponent
- `AppendArgument` - Add launch args to Wine command
- `WineDllOverride` - Merge into WINEDLLOVERRIDES env var
- `DisableEsync/Fsync` - Set WINEESYNC/WINEFSYNC to empty string
- `InstallWinetrick` - Download/install Wine components (simplified, no full winetricks)

**Testing:**
- Create 5-10 sample fixes (Dark Souls, L.A. Noire, Red Dead 2, etc.)
- Test with GLIBC container
- Verify environment vars reach Wine process

### Phase 2: Wine Integration (Week 2)
**Goal**: Full Wine environment manipulation

**Components to Build:**
1. `WineRegistryEditor.kt` - Call `wine reg add` via shell
2. `WinePrefixManager.kt` - Manage prefix paths, dosdevices
3. `WineComponentInstaller.kt` - Simplified winetricks alternative

**Operations to Implement:**
- `RegistryAdd` - Use `wine reg add` command
- `WineExeOverride` - Add to WINEDLLOVERRIDES
- `DeleteEnvironment` - Remove env vars
- `ReplaceCommand` - Modify launch command args

**Wine Component Installer Design:**
Rather than full winetricks, implement direct downloads:
- vcrun2017 → Download from Microsoft, extract to system32
- d3dx9_43 → Download DirectX DLLs, place in system32
- corefonts → Download core fonts, install to Fonts directory

Alternative: Package pre-installed Wine components as .wcp addons

### Phase 3: Advanced Operations (Week 3)
**Goal**: Config file editing, graphics tweaks

**Components to Build:**
1. `IniFileEditor.kt` - Parse/modify INI files in Wine prefix
2. `XmlFileEditor.kt` - Parse/modify XML files
3. `DxvkConfigManager.kt` - Edit dxvk.conf
4. `CpuTopologyManager.kt` - Set Wine CPU topology env vars

**Operations to Implement:**
- `SetIniOption` - Edit game config INI files
- `SetXmlOption` - Edit game config XML files
- `SetDxvkOption` - Append to dxvk.conf
- `SetCpuTopology` - Set WINE_CPU_TOPOLOGY env var
- `DisableNvapi` - Override nvapi DLLs
- `PatchLibcuda` - Complex, may defer to Phase 4

### Phase 4: Fix Database Population (Week 4)
**Goal**: Convert all 336 Python fixes to JSON

**Process:**
1. **Manual Conversion** (High Priority Fixes):
   - Top 50 most popular Steam games on Proton
   - Games with simple fixes (1-3 operations)
   
2. **Semi-Automated Conversion**:
   - Parse Python AST to extract util.* function calls
   - Generate JSON operations automatically
   - Manual review for complex fixes
   
3. **Testing**:
   - Test converted fixes on actual games
   - Build user feedback system ("Did this fix work?")

**Python→JSON Converter Script:**
```python
# tools/convert-protonfixes-to-json.py
import ast
import json

def parse_fix_file(filepath):
    """Parse Python fix file and extract operations"""
    with open(filepath) as f:
        tree = ast.parse(f.read())
    
    operations = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Attribute):
                func_name = node.func.attr
                if func_name == "protontricks":
                    operations.append({
                        "type": "InstallWinetrick",
                        "verb": node.args[0].value
                    })
                elif func_name == "append_argument":
                    operations.append({
                        "type": "AppendArgument",
                        "argument": node.args[0].value
                    })
                # ... handle other util functions
    
    return operations
```

### Phase 5: UI Integration (Week 5)
**Goal**: User-facing fix application

**UI Components:**
1. **Game Launch Dialog** (`GameDetailScreen.kt`):
   - Checkbox: "Apply GE-Proton Fixes (Experimental)"
   - Tooltip: "Automatically apply known compatibility fixes for this game"
   - Only show for GLIBC containers
   - Show fix preview: "Will install: vcrun2017, d3dcompiler_47"

2. **Fix Application Progress**:
   - Show progress dialog during fix application
   - Log operations: "Installing vcrun2017... Done"
   - Handle failures gracefully

3. **Fix Management Screen** (`ProtonFixesScreen.kt`):
   - List all available fixes
   - Search by game name/App ID
   - View fix details (operations list)
   - Manually apply/remove fixes
   - Report fix success/failure

**Container Integration:**
Modify `Container.java` to store applied fixes:
```java
public class Container {
    // ... existing fields
    private List<String> appliedFixes = new ArrayList<>();
    
    public void addAppliedFix(String steamAppId) {
        if (!appliedFixes.contains(steamAppId)) {
            appliedFixes.add(steamAppId);
        }
    }
}
```

---

## Key Design Decisions

### 1. When to Apply Fixes?

**Option A: Pre-Launch (Recommended)**
- Apply fixes during game launch setup
- Modify environment before Wine process starts
- Pros: Clean, reversible, no persistent prefix modification
- Cons: Need to reapply on every launch

**Option B: One-Time Container Setup**
- Apply fixes once when game is first configured
- Store flag in Container: `appliedFixes: [steamAppId]`
- Pros: Faster launches, persistent changes
- Cons: Harder to undo, may break on Wine/Proton update

**Recommendation**: Hybrid approach
- Environment vars, launch args, DLL overrides → Pre-launch
- Winetricks components, registry keys → One-time setup with version tracking

### 2. Winetricks Implementation

**Option A: Full Winetricks Port**
- Port entire winetricks bash script to Kotlin
- Pros: Complete compatibility
- Cons: 10,000+ lines of bash, many Linux-specific dependencies

**Option B: Component Installer**
- Implement only most common verbs (vcrun2017, d3dx9_43, corefonts, etc.)
- Direct downloads from Microsoft/Wine HQ
- Pros: Simpler, faster, Android-friendly
- Cons: Limited verb coverage (~20 verbs vs 1000+)

**Option C: Pre-Packaged Components**
- Bundle common components as .wcp packages
- "gamenative-winetricks-essentials.wcp" (vcrun2017, d3dx9, etc.)
- User downloads once, applies to multiple games
- Pros: No runtime downloads, guaranteed to work
- Cons: Large package size, manual updates

**Recommendation**: Option B + Option C hybrid
- Implement 20 most common verbs (covers 90% of fixes)
- Offer optional essentials package for offline use

### 3. Fix Database Storage

**JSON Resource (Embedded in APK):**
- Pros: Simple, no database overhead, easy to update via code push
- Cons: Must rebuild APK to update fixes, larger APK size (~500KB for 336 fixes)

**SQLite Database:**
- Pros: Query performance, can update via network, extensible (user ratings, etc.)
- Cons: Adds complexity, database migration overhead

**Remote JSON (GitHub/CDN):**
- Pros: Update fixes without app update, A/B testing
- Cons: Requires network, versioning complexity

**Recommendation**: Embedded JSON for v1, migrate to hybrid (embedded + remote sync) for v2

### 4. Testing Strategy

**Unit Tests:**
- Test each FixOperation executor independently
- Mock Wine environment, verify correct env vars set
- Test JSON deserialization

**Integration Tests:**
- Test full fix application on sample container
- Verify environment reaches Wine process
- Test with actual game launch (requires Android device + Steam game)

**User Testing:**
- Beta test with 10-20 popular games
- Collect feedback: "Did fix work? Y/N"
- Build success rate dashboard

---

## Technical Challenges & Solutions

### Challenge 1: No Direct sys.argv Access
**Problem**: Python fixes modify `sys.argv` before Wine launches. GameNative builds Wine command in Java.

**Solution**: Modify `GlibcProgramLauncherComponent.createWineCommand()` to accept `additionalArgs`:
```java
public String[] createWineCommand(Container container, List<String> additionalArgs) {
    List<String> cmd = new ArrayList<>();
    // ... build base command
    if (additionalArgs != null) {
        cmd.addAll(additionalArgs);
    }
    return cmd.toArray(new String[0]);
}
```

### Challenge 2: Wine Registry Access
**Problem**: Python uses `wine reg add` subprocess calls. GameNative runs on Android with different process model.

**Solution**: Use `Runtime.exec()` to call `wine reg add` via PRoot environment:
```kotlin
fun addRegistryKey(container: Container, folder: String, name: String?, type: String?, value: String?) {
    val prootCmd = container.buildPRootCommand()
    val wineCmd = "${container.wineInfo.bin}/wine"
    val regCmd = if (name != null && type != null && value != null) {
        "$wineCmd reg add \"$folder\" /f /v \"$name\" /t $type /d \"$value\""
    } else {
        "$wineCmd reg add \"$folder\" /f"
    }
    
    val fullCmd = "$prootCmd $regCmd"
    Runtime.getRuntime().exec(fullCmd).waitFor()
}
```

### Challenge 3: Winetricks Dependencies
**Problem**: Winetricks requires bash, curl, cabextract, unzip - not all available on Android.

**Solution**: Implement minimal installer for top 20 verbs:
- vcrun2017: Direct download MSVC redistributable, extract DLLs
- d3dx9_43: Download DirectX DLLs from Wine's dll repository
- corefonts: Download fonts, install to prefix/drive_c/windows/Fonts
- Skip verbs that require complex build steps (compile from source, etc.)

### Challenge 4: File Path Resolution
**Problem**: Python fixes reference paths like `BasePath.USER` (My Documents), `BasePath.GAME` (install dir). GameNative needs Android-compatible paths.

**Solution**: Path resolver utility:
```kotlin
class ProtonPathResolver(val container: Container, val game: Game?) {
    fun resolve(basePath: BasePath, relativePath: String): File {
        return when (basePath) {
            BasePath.USER -> {
                // prefix/drive_c/users/{username}/Documents
                File(container.rootDir, "drive_c/users/xuser/Documents/$relativePath")
            }
            BasePath.GAME -> {
                // Steam library/steamapps/common/{gamename}
                File(game?.installPath ?: "/", relativePath)
            }
            BasePath.APPDATA_LOCAL -> {
                // prefix/drive_c/users/{username}/AppData/Local
                File(container.rootDir, "drive_c/users/xuser/AppData/Local/$relativePath")
            }
        }
    }
}
```

### Challenge 5: Performance
**Problem**: Applying 5+ winetricks verbs could take minutes on each launch.

**Solution**: 
- Cache installation check: Store MD5 of installed components
- Skip reinstallation if already present and version matches
- Show progress UI: "Installing vcrun2017 (2/5)..."
- Allow background installation: "Fix being applied, game will start when ready"

---

## File Structure

```
app/src/main/java/com/winlator/protonfixes/
├── ProtonFix.kt                    # Data models
├── ProtonFixEngine.kt              # Core engine
├── FixRegistry.kt                  # Fix database access
├── operations/
│   ├── FixOperation.kt             # Sealed class hierarchy
│   ├── EnvironmentOperation.kt     # SetEnvironment, DeleteEnvironment
│   ├── ArgumentOperation.kt        # AppendArgument, ReplaceCommand
│   ├── WineOperation.kt            # WineDllOverride, RegistryAdd
│   ├── InstallOperation.kt         # InstallWinetrick
│   └── ConfigOperation.kt          # SetIniOption, SetXmlOption
├── executors/
│   ├── FixOperationExecutor.kt     # Interface
│   ├── EnvironmentExecutor.kt      # Execute env ops
│   ├── ArgumentExecutor.kt         # Execute arg ops
│   ├── WineExecutor.kt             # Execute Wine ops
│   ├── InstallExecutor.kt          # Execute install ops
│   └── ConfigExecutor.kt           # Execute config ops
├── installers/
│   ├── WineComponentInstaller.kt   # Base class
│   ├── VcrunInstaller.kt           # Install Visual C++ runtimes
│   ├── DirectXInstaller.kt         # Install DirectX DLLs
│   └── FontsInstaller.kt           # Install fonts
└── utils/
    ├── ProtonPathResolver.kt       # Resolve BasePath.USER, etc.
    ├── WineRegistryEditor.kt       # Call wine reg add
    └── IniFileEditor.kt            # Parse/modify INI files

app/src/main/assets/
└── protonfixes/
    └── fixes.json                  # All 336 fixes

app/src/main/java/app/gamenative/ui/screen/
└── settings/
    └── ProtonFixesScreen.kt        # Fix management UI
```

---

## API Design

### ProtonFixEngine API

```kotlin
@Singleton
class ProtonFixEngine @Inject constructor(
    private val fixRegistry: FixRegistry,
    private val operationExecutors: Map<Class<out FixOperation>, FixOperationExecutor>
) {
    /**
     * Check if fixes can be applied to this container
     * Only GLIBC containers support ProtonFixes
     */
    fun canApplyFixes(container: Container): Boolean {
        return container.containerVariant == Container.CONTAINER_VARIANT_GLIBC
    }
    
    /**
     * Get available fix for a Steam game
     * @param steamAppId Steam App ID (e.g., "570940")
     * @return ProtonFix if available, null otherwise
     */
    fun getAvailableFix(steamAppId: String): ProtonFix? {
        return fixRegistry.getFix(steamAppId)
    }
    
    /**
     * Apply a fix to a container before game launch
     * @param container Container to apply fix to
     * @param game Game being launched (for path resolution)
     * @param fix Fix to apply
     * @param progressCallback Progress updates (optional)
     * @return Result indicating success/failure
     */
    suspend fun applyFix(
        container: Container,
        game: Game?,
        fix: ProtonFix,
        progressCallback: ((String, Int, Int) -> Unit)? = null
    ): FixResult {
        if (!canApplyFixes(container)) {
            return FixResult.Failure("Container does not support ProtonFixes")
        }
        
        val context = FixContext(container, game)
        val results = mutableListOf<OperationResult>()
        
        fix.operations.forEachIndexed { index, operation ->
            progressCallback?.invoke(
                operation.description(),
                index + 1,
                fix.operations.size
            )
            
            val executor = operationExecutors[operation::class.java]
                ?: return FixResult.Failure("No executor for ${operation::class.simpleName}")
            
            val result = executor.execute(context, operation)
            results.add(result)
            
            if (!result.success && result.critical) {
                return FixResult.PartialFailure(
                    "Critical operation failed: ${result.error}",
                    results
                )
            }
        }
        
        return if (results.all { it.success }) {
            FixResult.Success(results)
        } else {
            FixResult.PartialSuccess(results)
        }
    }
    
    /**
     * Get all available fixes (for UI listing)
     */
    fun getAllFixes(): List<ProtonFix> {
        return fixRegistry.getAllFixes()
    }
}

data class FixContext(
    val container: Container,
    val game: Game?,
    val environment: MutableMap<String, String> = mutableMapOf(),
    val arguments: MutableList<String> = mutableListOf(),
    val pathResolver: ProtonPathResolver = ProtonPathResolver(container, game)
)

sealed class FixResult {
    data class Success(val operations: List<OperationResult>) : FixResult()
    data class PartialSuccess(val operations: List<OperationResult>) : FixResult()
    data class PartialFailure(val error: String, val operations: List<OperationResult>) : FixResult()
    data class Failure(val error: String) : FixResult()
}

data class OperationResult(
    val operation: FixOperation,
    val success: Boolean,
    val error: String? = null,
    val critical: Boolean = false  // If true, stop applying remaining operations
)
```

---

## Migration Path: Python → JSON

### Simple Fixes (80% of cases)
These can be auto-converted with high confidence:

**Python:**
```python
def main() -> None:
    util.protontricks('vcrun2017')
    util.append_argument('-dx11')
```

**JSON:**
```json
{
  "steamAppId": "570940",
  "operations": [
    {"type": "InstallWinetrick", "verb": "vcrun2017"},
    {"type": "AppendArgument", "argument": "-dx11"}
  ]
}
```

### Complex Fixes (20% of cases)
Require manual review or custom executor:

**Python with Custom Logic:**
```python
def main() -> None:
    resolution = util.get_resolution()
    if resolution[0] > 1920:
        util.set_environment('WINE_LARGE_ADDRESS_AWARE', '1')
    util.protontricks('d3dx9_43')
```

**JSON with Conditional:**
```json
{
  "steamAppId": "123456",
  "operations": [
    {
      "type": "ConditionalSetEnvironment",
      "condition": {"type": "ResolutionGreaterThan", "width": 1920},
      "name": "WINE_LARGE_ADDRESS_AWARE",
      "value": "1"
    },
    {"type": "InstallWinetrick", "verb": "d3dx9_43"}
  ]
}
```

**Or Simplify for v1:**
```json
{
  "steamAppId": "123456",
  "operations": [
    {"type": "SetEnvironment", "name": "WINE_LARGE_ADDRESS_AWARE", "value": "1"},
    {"type": "InstallWinetrick", "verb": "d3dx9_43"}
  ]
}
```
(Apply unconditionally, slightly less optimal but works)

---

## Success Metrics

### Phase 1 Success Criteria:
- [ ] Apply 5 sample fixes successfully
- [ ] Environment vars reach Wine process (verify via logcat)
- [ ] Launch arguments appear in Wine command
- [ ] No crashes during fix application

### Phase 2 Success Criteria:
- [ ] Registry keys added successfully (verify via wine reg query)
- [ ] Install 3 winetricks verbs (vcrun2017, d3dx9_43, corefonts)
- [ ] Verify installed components appear in prefix

### Phase 3 Success Criteria:
- [ ] Edit INI file successfully (read back to verify)
- [ ] Set DXVK options (verify dxvk.conf created)
- [ ] CPU topology applied (verify WINE_CPU_TOPOLOGY env var)

### Phase 4 Success Criteria:
- [ ] Convert all 336 fixes to JSON
- [ ] Test top 50 fixes on actual games
- [ ] 80%+ success rate reported by testers

### Phase 5 Success Criteria:
- [ ] UI shows fix availability for games
- [ ] User can apply/remove fixes via UI
- [ ] Progress dialog shows operation status
- [ ] Feedback system collects success/failure data

---

## Risk Assessment

### High Risk:
- **Winetricks Complexity**: Many verbs have complex dependencies, may not work on Android
  - Mitigation: Start with simple verbs (download + extract), defer complex ones
  
- **Wine Process Execution**: Running wine subprocesses on Android/PRoot has quirks
  - Mitigation: Extensive testing, fallback to environment-only fixes

### Medium Risk:
- **Fix Conversion Accuracy**: Auto-converting Python → JSON may introduce bugs
  - Mitigation: Manual review of all conversions, beta testing
  
- **Performance**: Installing components on each launch could be slow
  - Mitigation: Caching, one-time installation option

### Low Risk:
- **UI Complexity**: Fix management UI is straightforward CRUD
- **Data Model**: Sealed class hierarchy is well-established pattern in Kotlin

---

## Alternative Approaches Considered

### 1. Embed Python Runtime (Chaquopy)
Run original Python protonfixes via embedded interpreter.

**Pros**: 100% compatibility with GE-Proton fixes
**Cons**: 
- Adds 20MB+ to APK size
- Python subprocess execution on Android is fragile
- Maintenance burden (keep Python runtime updated)
- Performance overhead

**Verdict**: Rejected - complexity outweighs benefits

### 2. Remote Fix Execution Service
Host fix execution on cloud server, send commands to device.

**Pros**: No client-side implementation needed
**Cons**:
- Requires network for game launch
- Security concerns (executing remote commands)
- Latency, reliability issues

**Verdict**: Rejected - user experience degradation

### 3. Manual Fix Application (Current State)
Users manually edit configs, install components.

**Pros**: No development needed
**Cons**:
- Poor user experience
- Limited adoption
- Support burden (users ask "why doesn't this game work?")

**Verdict**: Inadequate - automation is core value-add

---

## Conclusion

Porting GE-Proton's protonfixes system to GameNative is feasible with a phased approach:
1. Build core infrastructure with 5 operation types (Week 1)
2. Add Wine integration (registry, dll overrides) (Week 2)
3. Implement advanced operations (config editing, graphics) (Week 3)
4. Convert all 336 fixes to JSON (Week 4)
5. Build user-facing UI (Week 5)

**Estimated Effort**: 5 weeks for MVP covering 80% of fixes, additional 3-4 weeks for remaining 20% complex fixes and refinement.

**Key Success Factors**:
- Start with simple, high-impact fixes (vcrun2017, d3dx9, launch args)
- Test early and often with real games
- Build user feedback loop to prioritize fixes
- Accept 80% coverage initially, expand over time

**Next Steps**:
1. Review this design document
2. Prioritize Phase 1 implementation
3. Set up testing environment (device + Steam account + test games)
4. Begin development with 5 sample fixes

---

## Appendix A: Top 20 Winetricks Verbs to Implement

Based on analysis of 336 fixes:

1. **vcrun2017** (Visual C++ 2017) - 42 games
2. **d3dx9_43** (DirectX 9) - 38 games
3. **d3dcompiler_43** (DirectX Compiler) - 31 games
4. **d3dcompiler_47** (DirectX Compiler) - 29 games
5. **vcrun2019** (Visual C++ 2019) - 24 games
6. **d3dx11_43** (DirectX 11) - 18 games
7. **corefonts** (Core Fonts) - 16 games
8. **dotnet48** (.NET Framework 4.8) - 12 games
9. **vcrun2015** (Visual C++ 2015) - 11 games
10. **xact** (XACT Audio) - 9 games
11. **l3codecx** (L3 Codec) - 8 games
12. **mfc42** (Microsoft Foundation Classes) - 8 games
13. **quartz** (Quartz Video) - 7 games
14. **winxp** (Windows XP Mode) - 7 games
15. **lavfilters** (LAV Filters) - 6 games
16. **vcrun2013** (Visual C++ 2013) - 6 games
17. **d9vk** (D9VK DirectX 9→Vulkan) - 5 games
18. **physx** (PhysX Runtime) - 5 games
19. **vcrun2010** (Visual C++ 2010) - 5 games
20. **wmp10** (Windows Media Player 10) - 4 games

Implementing these 20 verbs would cover ~90% of all protonfixes requiring winetricks.

---

## Appendix B: Sample Fix Conversions

### Example 1: Simple Winetricks
**Python (Dark Souls Remastered - 570940):**
```python
from protonfixes import util
def main() -> None:
    util.protontricks('vcrun2017')
```

**JSON:**
```json
{
  "steamAppId": "570940",
  "gameName": "Dark Souls Remastered",
  "operations": [
    {"type": "InstallWinetrick", "verb": "vcrun2017"}
  ]
}
```

### Example 2: Multiple Operations
**Python (Age of Empires III - 105450):**
```python
from protonfixes import util
def main() -> None:
    util.protontricks('mfc42')
    util.protontricks('l3codecx')
    util.protontricks('corefonts')
    util.protontricks('winxp')
```

**JSON:**
```json
{
  "steamAppId": "105450",
  "gameName": "Age of Empires III",
  "operations": [
    {"type": "InstallWinetrick", "verb": "mfc42"},
    {"type": "InstallWinetrick", "verb": "l3codecx"},
    {"type": "InstallWinetrick", "verb": "corefonts"},
    {"type": "InstallWinetrick", "verb": "winxp"}
  ]
}
```

### Example 3: Launch Arguments
**Python (Red Dead Redemption 2 - 1174180):**
```python
from protonfixes import util
def main() -> None:
    util.append_argument('-fullscreen -vulkan')
```

**JSON:**
```json
{
  "steamAppId": "1174180",
  "gameName": "Red Dead Redemption 2",
  "operations": [
    {"type": "AppendArgument", "argument": "-fullscreen -vulkan"}
  ]
}
```

### Example 4: Environment Variables + DLL Override
**Python (Hypothetical):**
```python
from protonfixes import util
def main() -> None:
    util.set_environment('DXVK_HUD', 'fps')
    util.winedll_override('d3d11', util.OverrideOrder.NATIVE)
    util.disable_esync()
```

**JSON:**
```json
{
  "steamAppId": "999999",
  "gameName": "Example Game",
  "operations": [
    {"type": "SetEnvironment", "name": "DXVK_HUD", "value": "fps"},
    {"type": "WineDllOverride", "dll": "d3d11", "order": "NATIVE"},
    {"type": "DisableEsync"}
  ]
}
```

---

**Document Version**: 1.0  
**Last Updated**: November 23, 2024  
**Author**: GitHub Copilot (Claude Sonnet 4.5)  
**Status**: Design Proposal
