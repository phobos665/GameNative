# GameNative Architecture

GameNative is an Android app that lets you play Windows games (from Steam, GOG, Epic, or custom) on Android devices by running them through Wine/Proton inside an embedded Linux environment.

## High-Level Stack

```
┌─────────────────────────────────────────────┐
│  Jetpack Compose UI (Material 3)            │
│  NavHost: Login → Home → Library → XServer  │
├─────────────────────────────────────────────┤
│  ViewModels (5) + EventDispatcher bus       │
│  MainVM, HomeVM, LibraryVM, LoginVM, XSrvVM │
├─────────────────────────────────────────────┤
│  3 Foreground Services                      │
│  SteamService │ GOGService │ EpicService    │
├─────────────────────────────────────────────┤
│  Room DB (v12, 10 DAOs, 10 entities)        │
│  DataStore Preferences (PrefManager)        │
├─────────────────────────────────────────────┤
│  Wine/Proton Container System (Winlator)    │
│  XServer ↔ GLRenderer ↔ WinHandler          │
├─────────────────────────────────────────────┤
│  Native libs (.so): virgl, vortekrenderer,  │
│  pulseaudio, winlator, hook_impl, zstd-jni  │
└─────────────────────────────────────────────┘
```

## The Full Game Lifecycle (Steam focus)

### 1. Authentication
- **JavaSteam** library communicates via WebSocket protocol
- Auth methods: OAuth refresh token (persistent), QR code, credentials+2FA
- Tokens obfuscated and stored in Wine config files (config.vdf, local.vdf) using XOR with MTBF key
- Unique login ID derived from `ANDROID_ID` hashCode

### 2. Library Discovery (PICS)
- Steam's **Product Information Code Server** is the primary metadata source
- On login, `onLicenseList` callback fires → each license triggers PICSRequest for package info
- Batch queries up to 256 apps at a time through buffered channels (`appPicsChannel`, `packagePicsChannel`, capacity 1000)
- Continuous polling every 60s via `picsGetChangesSince()` for library changes
- Metadata stored in Room DB: `SteamApp` (name, depots, manifests, DLC, launch configs, cloud save patterns, images)

### 3. Download

#### Download Pipeline (All Sources)

```
┌─────────────────────────────────────────────────────┐
│           Download Initiation                        │
│  WiFi check → Manifest fetch → Depot selection      │
│  → Size calculation → Weight assignment              │
└──────────────┬────────────┬────────────┬────────────┘
               │            │            │
           ┌───▼───┐   ┌───▼───┐   ┌───▼────┐
           │ Steam  │   │  GOG  │   │  Epic  │
           │ Depot  │   │ Gen-2 │   │ Binary │
           │Downldr │   │Chunks │   │Manifest│
           └───┬───┘   └───┬───┘   └───┬────┘
               │            │            │
               ├─ Parallel CDN chunk download ──────┐
               ├─ Decompress (zlib)                 │
               ├─ Verify (MD5 or SHA-1)             │
               └─ Assemble files from chunks        │
                                                    │
           ┌────────────────────────────────────────┘
           ▼
    ┌─────────────────────────────────────────────┐
    │  Completion                                  │
    │  DB update → DOWNLOAD_COMPLETE marker        │
    │  → clear persisted bytes → emit event        │
    └─────────────────────────────────────────────┘
```

#### Parallelism by Source

| Setting | Steam | GOG | Epic |
|---------|-------|-----|------|
| Parallel downloads | 0.6–2.4 × CPU cores (speed 8/16/24/32) | 4 fixed | 6 fixed |
| Parallel decompress | 0.2–0.8 × CPU cores | N/A (inline) | N/A (inline) |
| Retry attempts | via DepotDownloader | 3, exponential 1s→4s | 3, exponential 1s→4s |
| Chunk buffer | 8KB streams | 1MB | 64KB (OOM-safe streaming) |

#### Progress & ETA

- `DownloadInfo` tracks per-depot weighted progress + cumulative bytes
- Speed: 30-second sliding window of `(timestamp, bytes)` samples
- ETA: exponential moving average (α=0.3) smoothing: `etaSeconds = remaining / smoothedSpeed`
- Byte-based progress preferred; falls back to weighted per-depot averages

#### Persistence & Resume

- On pause/cancel: `{appDir}/.DownloadInfo/bytes_downloaded.txt` (plain long integer)
- On resume: load persisted bytes, skip already-downloaded content
- On completion: delete persistence file

#### Storage Paths

```
Steam:  {base}/Steam/steamapps/common/{appName}
GOG:    {base}/GOG Games/{appName}
Epic:   {base}/Epic Games/{appName}

base = external (Android/data/<pkg>) if enabled, else internal (/data/data/<pkg>)
Path selection: check existing installs (internal→external) → default per preference
```

#### Wi-Fi Monitoring

- `ConnectivityManager.NetworkCallback` monitors TRANSPORT_WIFI and TRANSPORT_ETHERNET
- On WiFi loss + `downloadOnWifiOnly=true`: cancel all active download jobs
- Download start also checks connectivity before proceeding

#### Marker System

| Marker | File | Purpose |
|--------|------|---------|
| `DOWNLOAD_IN_PROGRESS` | `.download_in_progress` | Download active |
| `DOWNLOAD_COMPLETE` | `.download_complete` | Download finished |
| `STEAM_DLL_REPLACED` | `.steam_dll_replaced` | Custom steam_api.dll installed |
| `STEAM_DLL_RESTORED` | `.steam_dll_restored` | Original DLLs restored |
| `STEAM_COLDCLIENT_USED` | `.steam_coldclient_used` | ColdClientLoader mode |

### 4. Installation

#### Steam Post-Download Installation

On `LibraryInstallStatusChanged` event:

```
replaceSteamApi()
  ├─ Scan game dir (10 levels deep) for steam_api.dll / steam_api64.dll
  ├─ Backup originals → .orig files
  ├─ Write backup paths to orig_dll_path.txt
  ├─ Replace with steampipe DLL from assets/steampipe/
  ├─ Generate steam_interfaces.txt (extract interface strings from DLL binary)
  └─ Mark STEAM_DLL_REPLACED

ensureSteamSettings()
  ├─ Create steam_settings/ directory next to replaced DLL
  ├─ steam_appid.txt
  ├─ depots.txt (installed depot IDs)
  ├─ configs.user.ini:
  │    [user::general]
  │    account_name, account_steamid, language, ticket (encrypted app ticket)
  ├─ configs.app.ini:
  │    [app::dlcs] unlock_all, per-DLC entries
  │    [app::cloud_save::general/win] (if save patterns exist)
  ├─ configs.main.ini: LAN settings
  └─ supported_languages.txt (30 languages)

createAppManifest()
  ├─ Write appmanifest_{appid}.acf to Steam/steamapps/
  │    AppState: appid, name, buildid, installdir, SizeOnDisk, StateFlags=4
  ├─ Symlink steamapps/common/{gameDir} → actual game directory
  └─ Also create appmanifest_228980.acf (Steamworks Common Redist)

skipFirstTimeSteamSetup()
  └─ Registry: mark DirectX, .NET 3.5-4.8, XNA 3.0-4.0, OpenAL as installed
     in HKLM\Software\Valve\Steam\Apps\CommonRedist\ (+ Wow6432Node)

autoLoginUserChanges()
  ├─ Write loginusers.vdf with OAuth refresh token
  └─ Registry: HKCU\Software\Valve\Steam: AutoLoginUser, SteamExe, SteamPath

ensureSaveLocationsForGames()
  └─ Symlink save folders using SpecialGameSaveMapping registry
     with placeholders: {64BitSteamID}, {Steam3AccountID}
```

#### ColdClientLoader Mode (Alternative DRM Path)

```
replaceSteamclientDll()
  ├─ Extract experimental-drm package to Wine prefix
  ├─ Backup steamclient DLLs to steamclient_backup/
  ├─ Restore original steam_api DLLs
  ├─ Write ColdClientLoader.ini:
  │    [SteamClient] Exe, AppId, SteamClientDll, SteamClient64Dll
  │    [Injection] IgnoreLoaderArchDifference=1, DllsToInjectFolder
  └─ Mark STEAM_COLDCLIENT_USED
```

#### Steampipe DLL

Custom `steam_api.dll` / `steam_api64.dll` bundled in `assets/steampipe/`. Redirects Steam API calls to `SteamPipeServer` (TCP port 34865) running in the Android app. NOT Proton's — a custom implementation.

#### Game Directory After Installation

```
GameDirectory/
  ├─ game files...
  ├─ steam_api.dll / steam_api64.dll       (replaced with steampipe DLL)
  ├─ steam_api.dll.orig / steam_api64.dll.orig  (backups)
  ├─ orig_dll_path.txt                     (backup path references)
  ├─ steam_appid.txt
  ├─ steam_settings/
  │  ├─ steam_appid.txt, depots.txt
  │  ├─ configs.user.ini, configs.app.ini, configs.main.ini
  │  ├─ supported_languages.txt
  │  └─ controller/  (if useSteamInput)
  ├─ .steam_dll_replaced                   (marker)
  └─ .download_complete                    (marker)
```

#### GOG / Epic / Custom Installation

**GOG**: No DLL replacement. Downloads optional redistributables (VC++, DirectX, etc.) as separate chunks from GOG dependency repository. Games run with original DLLs.

**Epic**: No DLL replacement. Ownership token handling occurs during manifest fetch. DLCs downloaded sequentially after base game.

**Custom Games**: No download step. User adds folder via `PrefManager.customGameManualFolders`. `CustomGameScanner` generates game ID (from path hash, stored in `.gamenative` file), discovers icons (steamgriddb logo → extracted ico → .ico/.png files), and auto-detects single unique `.exe`.

### 5. Container Setup

#### Container Data Model

Each game gets a **Container** — a Wine prefix with extensive configuration:

**Graphics & Rendering:**
- `graphicsDriver`: turnip (default), virgl, vortek, adreno, sd-8-elite
- `dxwrapper`: dxvk (default), d8vk, vkd3d, wined3d, cnc-ddraw
- `dxwrapperConfig`: version, framerate, async cache, maxDeviceMemory, etc.

**Wine & Emulation:**
- `wineVersion`: main, custom, arm64ec, glibc-based variants
- `containerVariant`: bionic (Android libc) or glibc (GNU libc)
- `emulator`: FEXCore or Box86/Box64
- `box86Preset`/`box64Preset`: STABILITY, COMPATIBILITY, INTERMEDIATE, PERFORMANCE, DENUVO, UNITY, CUSTOM

**Display & Audio:**
- `screenSize`: default 1280×720
- `audioDriver`: pulseaudio (default) or alsa

**Game-Specific:**
- `drives`: drive letter → host path mappings
- `executablePath`, `execArgs`: launch config
- `language` → `LC_ALL` locale
- `forceDlc`, `useLegacyDRM`, `unpackFiles`, `needsUnpacking`: workaround flags
- `steamType`: normal/light/ultralight (Box64 RC config)

**Default Environment Variables:**
```
WRAPPER_MAX_IMAGE_COUNT=0        ZINK_DESCRIPTORS=lazy
ZINK_DEBUG=compact               MESA_SHADER_CACHE_DISABLE=false
MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true
WINEESYNC=1                      MESA_VK_WSI_PRESENT_MODE=mailbox
TU_DEBUG=noconform               DXVK_FRAME_RATE=60
PULSE_LATENCY_MSEC=144
```

#### Container Lifecycle

**Creation:**
```
ContainerManager.createContainer(containerId, data)
  ├─ Create directory: imagefs/home/xuser-{id}/
  ├─ Extract Wine prefix template:
  │    container_pattern_gamenative.tzst          (main wine)
  │    OR {wineVersion}_container_pattern.tzst    (custom wine)
  │    OR prefixPack.tzst/.txz                    (custom wine install)
  ├─ Extract common DLLs (filtered via common_dlls.json):
  │    /opt/wine/lib/wine/x86_64-windows/ → system32/
  │    /opt/wine/lib/wine/aarch64-windows/ → system32/  (arm64ec only)
  │    /opt/wine/lib/wine/i386-windows/ → syswow64/
  ├─ Save .container JSON
  └─ Add to container list
```

**Per-Source Drive Mapping:**
```
STEAM:  D:{downloads} E:{storage} {letter}:{steamapps/common/GameName}
GOG:    D:{downloads} E:{storage} A:{gog_game_dir}
EPIC:   D:{downloads} E:{storage} A:{epic_game_dir}
CUSTOM: D:{downloads} E:{storage} A:{custom_game_folder}
```

**Best Config API** (Steam only): Fetch optimal settings for GPU+game from remote service, merge with defaults.

#### Activation (The Symlink Dance)

```
activateContainer(container)
  ├─ Delete existing imagefs/home/xuser symlink
  └─ Create new symlink: xuser → xuser-{containerId}
```

All Wine processes read from the symlinked `.wine/` dir. Switching containers = switching symlink target.

#### Wine Prefix Structure

```
.wine/
├─ drive_c/
│  ├─ windows/
│  │  ├─ system32/         (64-bit DLLs: d3d8-12, dxgi, ddraw, dinput, xinput, DXVK/VKD3D)
│  │  ├─ syswow64/         (32-bit DLLs, same set)
│  │  └─ ProgramData/
│  ├─ Program Files (x86)/Steam/steamapps/common/{Game}/ (symlink)
│  └─ users/xuser/Desktop, Documents
├─ dosdevices/
│  ├─ c: → ../drive_c
│  ├─ z: → /data/data/app.gamenative  (Android root)
│  ├─ d: → /sdcard/Downloads          (or configured)
│  ├─ a: → /game/install/path         (game-specific)
│  └─ e: → app storage
├─ user.reg    (HKEY_CURRENT_USER)
├─ system.reg  (HKEY_LOCAL_MACHINE)
└─ userdef.reg
```

#### Graphics Driver Extraction

Cached via `.winlator/.current_graphics_driver` sentinel — only re-extracted on change.

**Turnip (default, Adreno GPUs):**
```
Extract: turnip-{ver}.tzst + zink-{ver}.tzst
Env: GALLIUM_DRIVER=zink, TU_OVERRIDE_HEAP_SIZE=4096, TU_DEBUG=gmem/sysmem
```

**VirGL (software rendering):**
```
Extract: virgl-{ver}.tzst
Env: GALLIUM_DRIVER=virpipe, VIRGL_NO_READBACK=true, MESA_GL_VERSION_OVERRIDE=3.1
```

**Vortek (Adreno 8 Elite, hardware Vulkan):**
```
Extract: vortek-2.1.tzst + zink-22.2.5.tzst
Env: GALLIUM_DRIVER=zink, ZINK_CONTEXT_THREADED=1, WINEVKUSEPLACEDADDR=1
```

#### DX Wrapper Setup

Clone-and-restore pattern: original DLLs cached on first boot, restored when switching wrappers.

**DXVK (D3D 9-11 → Vulkan):**
```
Extract: dxvk-{ver}.tzst + d8vk-{ver}.tzst → system32/syswow64
Env: DXVK_STATE_CACHE_PATH, DXVK_LOG_LEVEL=none, DXVK_ASYNC=1, DXVK_FRAME_RATE
```

**VKD3D (D3D 12 → Vulkan):**
```
Extract: dxvk-{ver}.tzst (9-11 fallback) + vkd3d-{ver}.tzst → d3d12.dll, d3d12core.dll
Env: VKD3D_FEATURE_LEVEL=12_1
```

**WineD3D (no translation):** Restore original DLLs from cache.

**CNC-DDRAW:** Extract + copy config/shaders to `C:\ProgramData\cnc-ddraw\`.

#### Windows Components

Extracted per container from `wincomponents/{component}.tzst`:
- direct3d, directsound, directmusic, directshow, directplay
- vcrun2010, wmdecoder, opengl (arm64ec only)

Each toggles between native (extracted) and builtin (Wine default) via registry:
```
Software\Wine\DllOverrides\{dll} = "native,builtin" or "builtin"
```

#### Registry Configuration

**user.reg (HKCU):**
```
Software\Wine\Drivers\Audio = "alsa" or "pulse"
Software\Wine\Direct3D: renderer, csmt, VideoPciDeviceID/VendorID,
    OffScreenRenderingMode, strict_shader_math, VideoMemorySize, shader_backend
Software\Wine\DirectInput\MouseWarpOverride
Software\Wine\DllOverrides: d3d8-12, dxgi, dinput, xinput, opengl32 (arm64ec)
Software\Valve\Steam: AutoLoginUser, SteamExe, SteamPath (Steam games)
```

**system.reg (HKLM):**
```
Software\Valve\Steam\Apps\CommonRedist: DirectX, .NET, XNA, OpenAL = installed
Software\Wow6432Node\...: same for 32-bit
```

#### Pre-Launch Setup Sequence

Before game launch (in `setupWineSystemFiles`):

```
1. activateContainer()              symlink xuser → xuser-{id}
2. applyGeneralPatches()            extract imagefs + container patches, font/file assoc setup
3. extractGraphicsDriverFiles()     GPU driver libraries + env vars
4. extractDXWrapperFiles()          DXVK/VKD3D/WineD3D DLLs
5. changeWineAudioDriver()          set audio registry key
6. extractWinComponentFiles()       Windows library overrides
7. createDosdevicesSymlinks()       drive letter → path symlinks
8. WineThemeManager.apply()         desktop theme
9. WineStartMenuCreator.create()    start menu items
10. changeServicesStatus()           NORMAL/ESSENTIAL/AGGRESSIVE service startup
```

### Known Issues & Failure Modes (Download/Install/Container)

#### Critical

| Issue | Location | Description |
|-------|----------|-------------|
| **Container symlink activation race** | ContainerManager:85-90 | Non-atomic delete+create of `xuser` symlink. Two concurrent activations can leave broken/wrong symlink. No locking |
| **DLL replacement no rollback** | SteamUtils:145-223 | If replacing one of two DLLs fails partway, the other is already replaced. No transaction semantics — game left in broken half-replaced state |

#### High

| Issue | Location | Description |
|-------|----------|-------------|
| **DownloadInfo data races** | DownloadInfo:17-35 | `progresses[]`, `bytesDownloaded`, `emaSpeedBytesPerSec`, `isActive` all mutable, accessed from download threads + UI thread without synchronization. Torn reads on progress/ETA |
| **Symlink creation failure ignored** | ContainerManager:88-89 | `FileUtils.symlink()` return value unchecked. Old symlink already deleted — if new one fails, container left unactivated |
| **DLL replacement non-atomic** | SteamUtils:181-186 | `Files.delete()` then `Files.createFile()` then write. If crash between delete and write, `steam_api.dll` left empty → game crash |
| **Force-unwrap on userSteamId** | SteamUtils:698,710,836 | `SteamService.userSteamId!!` — NPE crash if called before Steam login completes |
| **Force-unwrap on Epic manifest** | EpicDownloadManager:120 | `manifestResult.getOrNull()!!` — crash instead of graceful error for invalid manifests |
| **GOG chunk assembly partial files** | GOGDownloadManager:920-945 | If assembly fails mid-way, already-assembled files not cleaned up. Resume may use stale/incomplete files |
| **Speed sample concurrent modification** | DownloadInfo:135-145 | `addSpeedSample()` from download thread and `getEstimatedTimeRemaining()` from UI thread both call `trimOldSamples()` on shared list without sync → IndexOutOfBoundsException |

#### Medium

| Issue | Location | Description |
|-------|----------|-------------|
| **ACF manifest silent failure** | SteamUtils:651-653 | Entire `createAppManifest()` wrapped in catch-log-continue. DLL replacement succeeds but manifest fails → game not visible to Steam internals |
| **GOG DB update swallowed** | GOGDownloadManager:376-394 | Download succeeds but DB update failure caught and logged only. Game appears not installed to user |
| **Container extraction partial state** | ContainerManager:165-194 | If `extractContainerPatternFile()` fails, dir deleted but container object with modified state persists in memory |
| **Epic DLC failure not rolled back** | EpicDownloadManager:290-325 | Base game marked installed before DLC download. If DLC fails, game appears complete but DLC missing |
| **Progress listener CME** | DownloadInfo:239-243 | `downloadProgressListeners` is mutable list iterated during `emitProgressChange()`. Listener removing itself during callback → ConcurrentModificationException |
| **Chunk cache not cleaned on cancel** | EpicDownloadManager:217-221, GOGDownloadManager:458-461 | User cancels download → early return skips chunk cache cleanup → wasted disk space |
| **ACF string escaping incomplete** | SteamUtils:656-659 | `escapeString()` only handles `"`, `\n`, `\r`. Tabs, backslashes, or Unicode in game names → malformed ACF |
| **GOG placeholder productId** | GOGDownloadManager:273 | Hardcoded `"2147483047"` placeholder. If GOG changes this value, files silently skip download |

#### Low

| Issue | Location | Description |
|-------|----------|-------------|
| **DLL scan depth limit** | SteamUtils:166 | `.maxDepth(10)` hardcoded. Games with DLLs nested deeper than 10 levels silently miss replacement |
| **OkHttpClient never closed** | SteamUtils:41-45 | Static `http` client connection pool not cleaned up on app shutdown |

### 6. Game Launch

#### Full Execution Chain

```
User taps Play
  → MainViewModel.LaunchApp event
  → navigate(XServer) with popUpTo(Home)
  → XServerScreen composable mounts
  → AndroidView factory (line 686) spawns background thread:

    1. ContainerManager.activateContainer(container)     [line 829]
       └─ symlinks imagefs/home/xuser → xuser-{containerId}

    2. setupWineSystemFiles()                            [line 886]
       ├─ Extract DX wrapper DLLs (DXVK/VKD3D) to system32/syswow64
       ├─ Write Wine registry (Direct3D, DirectInput settings)
       ├─ Create dosdevices symlinks (drive letter → path)
       ├─ Set Windows component DLL overrides
       └─ Configure service startup (essential/aggressive)

    3. Extract graphics driver, audio driver, input DLLs  [lines 897-910]

    4. setupXEnvironment()                                [line 911]
       ├─ Build env vars (WINEPREFIX, MESA_*, DISPLAY=:0, Box64/FEX, SDL, WINEDEBUG)
       ├─ getWineStartCommand() → resolve executable path
       ├─ Construct: "wine explorer /desktop=shell,{WxH} {game_cmd} {args}"
       ├─ Add components to XEnvironment:
       │   SysVSharedMemory, XServer, Network, SteamClient,
       │   PulseAudio/ALSA, VirGL/Vortek, GuestProgramLauncher
       ├─ Set pre-unpack callback (Mono install, Steamless, DRM)
       ├─ environment.startEnvironmentComponents()
       └─ WinHandler.start() [async, UDP ports 7946/7947]
```

#### Executable Resolution

`getWineStartCommand()` (XServerScreen.kt line 2000) varies by game source:

| Source | Resolution | Launch Command |
|--------|-----------|----------------|
| **Steam** | `SteamService.getInstalledExe(gameId)` auto-detects from manifest. Writes `ColdClientLoader.ini` for modern DRM path | `steamclient_loader_x64.exe` (modern) or `winhandler.exe "D:\game.exe"` (legacy) |
| **GOG** | `GOGService.getGogWineStartCommand()` | `winhandler.exe {gogCommand}` |
| **Epic** | `EpicService.getInstalledExe()` + `buildLaunchParameters()` (auth tokens, EOS params) | `winhandler.exe "A:\game.exe" -AUTH_PASSWORD=... -epicapp=...` |
| **Custom** | `container.executablePath` or auto-scan via `CustomGameScanner.findUniqueExeRelativeToFolder()` | `"A:\path\to\game.exe"` |
| **Boot-to-container** | Hardcoded | `wfm.exe` (Wine file manager) |

#### Wine Execution Paths: x86→ARM Translation

The fundamental problem: Windows games are x86_64 binaries running on ARM64 Android.
The two Proton variants solve this differently — the key distinction is *where*
x86→ARM translation happens.

**Proton x86_64 (Box64-wrapped):**
```
Windows game (.exe, x86_64)
    ↓
Box64 (userspace x86_64→ARM64 translator)
    ↓ translates EVERY x86 instruction
Wine/Proton (x86_64 build, also running under Box64)
    ↓
Linux syscalls (ARM64 native)
    ↓
Android kernel
```

Everything runs through Box64 — both Wine itself and the game. Wine is an x86_64
binary, so its own code (memory management, syscall wrappers, window handling) is
all translated too. This is significant overhead since Wine is ~50%+ of total
instruction count in many games.

**Proton ARM64EC (native Wine + in-process translation):**
```
Windows game (.exe, x86_64)
    ↓
Wine/Proton (ARM64 native build, runs directly)
    ↓ uses Windows' WoW64 layer for x86_64 guest code
FEXCore or WoWBox64 (translates ONLY game code, loaded as Wine DLL)
    ↓
Linux syscalls (ARM64 native)
    ↓
Android kernel
```

ARM64EC ("Emulation Compatible") is Microsoft's hybrid architecture: an ARM64
process hosts both ARM64 and x86_64 code. Wine is compiled as a native ARM64
binary — zero translation overhead for Wine itself. Only the game's x86_64 code
gets translated, via a WoW64 DLL:
- **FEXCore** (`libwow64fex.dll`) — FEX's translation engine as a Wine DLL
- **WoWBox64** (`wowbox64.dll`) — Box64 wrapped as a Wine WoW64 DLL

#### Key Differences

| | Bionic x86_64 | Bionic ARM64EC |
|---|---|---|
| Wine binary | x86_64 (translated) | ARM64 (native) |
| Translation scope | Everything (Wine + game) | Game code only |
| Translator | Box64 (standalone process wrapper) | FEXCore or WoWBox64 (in-process DLL) |
| Launch command | `box64 wine explorer ...` | `wine-preloader {game_cmd}` |
| HODLL env var | not set | `libwow64fex.dll` or `wowbox64.dll` |
| Preset system | Box64 presets (BOX64_RCFILE) | FEXCore presets (FEX_* env vars) |

#### Why ARM64EC Is Better

1. **Less translation overhead**: Wine is a large runtime (~50%+ of instruction count).
   In x86_64 mode, Box64 translates every Wine instruction — API calls, memory
   allocations, syscall wrappers, window management — all pure overhead. ARM64EC
   runs Wine natively, translating only the game's hot loops (rendering, physics, AI).

2. **No double-translation penalty**: In x86_64 mode, Box64 must translate Wine's
   thunks that themselves translate Windows→Linux calls. ARM64EC eliminates this
   layer — Wine's ARM64 code calls Linux syscalls directly.

3. **Better Android integration**: Native ARM64 Wine can directly call into Bionic
   libc and Android system libraries without thunking. x86_64 Wine needs Box64 to
   marshal every call across the architecture boundary.

4. **In-process translation**: FEXCore/WoWBox64 run as DLLs inside Wine's process,
   sharing address space. This avoids the overhead of Box64's process-level wrapping
   and enables tighter integration with Wine's memory management.

The tradeoff: ARM64EC Wine builds are newer and less mature. Some games may have
better compatibility with the traditional Box64 path due to its longer history
of bug fixes.

#### Execution Path Details

**Bionic + ARM64EC** (best perf, `BionicProgramLauncherComponent`):
```
Command: {winePath}/wine-preloader {game_cmd}     ← direct exec, NO box64
HODLL:   libwow64fex.dll (FEXCore) or wowbox64.dll (Box64)
```

**Bionic + x86_64** (`BionicProgramLauncherComponent`):
```
Command: {binDir}/box64 wine explorer /desktop=shell,... {game_cmd}
HODLL:   (not set)
```

**Glibc** (always x86_64, `GlibcProgramLauncherComponent`):
```
Command: /usr/local/bin/box64 wine explorer /desktop=shell,... {game_cmd}
HODLL:   (not set)
```

#### Environment Variables by Path

All three paths share:
```
HOME, USER, DISPLAY=:0, WINEPREFIX, LC_ALL={language}
```

| Variable | Bionic ARM64EC | Bionic x86_64 | Glibc |
|----------|---------------|---------------|-------|
| LD_PRELOAD | sysvshm, evshim, redirect-bionic | same | redirect, sysvshm |
| LD_LIBRARY_PATH | rootDir/usr/lib:/system/lib64 | same | rootDir/usr/lib |
| BOX64_LD_LIBRARY_PATH | (not set) | (not set) | rootDir/usr/lib/x86_64-linux-gnu |
| HODLL | libwow64fex.dll or wowbox64.dll | (not set) | (not set) |
| BOX64_DYNAREC | 1 (set but unused) | 1 | 1 |
| BOX64_RCFILE | set | set | set |
| FEX_* preset vars | from FEXCorePresetManager | (not set) | (not set) |

#### FEXCore Presets (ARM64EC only)

FEXCore presets control the x86→ARM translation fidelity/performance tradeoff.
Higher fidelity (TSO enabled) = more correct memory ordering but slower.

| Preset | FEX_TSO | VectorTSO | MemcpyTSO | HalfBarrier | X87Reduced | Multiblock | Extra |
|--------|---------|-----------|-----------|-------------|------------|------------|-------|
| STABILITY | 1 | 1 | 1 | 1 | 0 | 0 | |
| COMPATIBILITY | 1 | 1 | 1 | 1 | 0 | 1 | |
| INTERMEDIATE | 1 | 0 | 0 | 1 | 1 | 1 | |
| PERFORMANCE | 0 | 0 | 0 | 0 | 1 | 1 | |
| EXTREME | 0 | 0 | 0 | 0 | 1 | 1 | +SMALLTSCSCALE, VOLATILEMETADATA |
| DENUVO | 0 | 0 | 0 | 0 | 1 | 1 | +SMCCHECKS=full, HIDEHYPERVISORBIT |

TSO = Total Store Ordering (x86 memory model emulation on ARM's weaker model).
Disabling TSO is faster but can cause rare crashes in games with lock-free code.

Verification from inside a container: boot to desktop, open `cmd.exe`, run `set FEX`
to dump all FEX env vars and confirm the active preset.

#### Box86/Box64 Presets (x86_64 path only)

Separate preset system for Box64's dynarec tuning: STABILITY, COMPATIBILITY,
INTERMEDIATE, PERFORMANCE, DENUVO, UNITY, CUSTOM. Configured via `BOX64_RCFILE`
pointing to generated config.

#### Config Bridging (XServerScreen → Launcher)

Container config is bridged to the launcher component before launch:
```
guestProgramLauncherComponent.box64Version = container.box64Version
guestProgramLauncherComponent.box86Version = container.box86Version
guestProgramLauncherComponent.box86Preset  = container.box86Preset
guestProgramLauncherComponent.box64Preset  = container.box64Preset
if (bionic) guestProgramLauncherComponent.setFEXCorePreset(container.fexCorePreset)
```

The emulator type (FEXCore vs Box64) is read directly from `container.getEmulator()`
inside the launcher, not bridged separately.

#### DLL Replacement & DRM Handling

During the pre-unpack callback (`unpackExecutableFile()`):
- **Mono install**: Wine Mono extracted if needed
- **DRM handling**: Reads `orig_dll_path.txt` listing DLLs requiring interface generation. Runs `generate_interfaces_file.exe` per DLL to create `steam_interfaces.txt`
- **Steamless**: If `unpackFiles` enabled, runs `Steamless.CLI.exe` via Wine batch file on each exe. Backs up original as `.original.exe`, replaces with `.unpacked.exe`
- **Redistributables**: Installs VC++, OpenAL, PhysX, XNA as needed

### 7. Runtime

#### IPC Channels

Once the game is running, 6 parallel IPC channels operate simultaneously:

**X11 Display** (XServerComponent → XConnectorEpoll):
- Unix socket at `imagefs/tmp/xserver`
- Epoll-based single-threaded event loop
- Wine creates/maps windows → `WindowManager` tracks tree
- Each frame: `GLRenderer.drawFrame()` locks drawables, uploads textures via `glTexSubImage2D`, renders as quads

**Input** (WinHandler → UDP):
- Port 7947 (receive from Wine), port 7946 (send to Wine)
- Request codes: MOUSE_EVENT(7), KEYBOARD_EVENT(11), EXEC(2), LIST_PROCESSES(4)
- Single-threaded send queue (`ArrayDeque<Runnable>`)
- Gamepad state: memory-mapped file at `imagefs/tmp/gamepad.mem` (64 bytes/player)
- Rumble feedback polled every 20ms from shared mem

**Graphics Backends:**
- **VirGL**: Software Vulkan, shares EGL context with GL render thread via `queueEvent()`. `flushFrontbuffer()` copies Wine's framebuffer → X11 drawable texture
- **Vortek**: Hardware Vulkan, uses `AHardwareBuffer` for zero-copy GPU memory. ~3-5x faster than VirGL

**Audio:**
- **PulseAudio**: Subprocess (`libpulseaudio.so`), Unix socket at `$WINEPREFIX/pulse_server`, AAudio sink
- **ALSA**: In-process Java server via XConnectorEpoll, multithreaded client mode

**Steam API** (SteamPipeServer):
- TCP port 34865, intercepts Steam API calls from replaced `steam_api.dll`
- Messages: INIT, IS_RUNNING, REGISTER_CALLBACK, RUN_CALLBACKS
- Currently a stub — enough to satisfy basic Steam checks

**Shared Memory** (SysVSharedMemoryComponent):
- Unix socket for Android SysV SHM emulation
- Used by X11 MIT-SHM extension for fast image transfer

#### Display Pipeline

```
Wine creates X11 windows → XServer.WindowManager tracks tree
  → GLRenderer.drawFrame() called each vsync:
    1. Lock drawables (XLock)
    2. For each renderable window:
       a. Lock drawable.renderLock
       b. texture.updateFromDrawable() → glTexSubImage2D (pixel upload to GPU)
       c. Render as textured quad
    3. Render cursor overlay
```

Graphics translation layers: DXVK (D3D 9/10/11→Vulkan), VKD3D (D3D12→Vulkan), ZINK (OpenGL→Vulkan)

#### Input Pipeline

```
Android touch/key/gamepad events
    → XServerScreen handlers
    → PhysicalControllerHandler / InputControlsView / TouchpadView
    → WinHandler.mouseEvent() / keyboardEvent()
    → UDP packet to Wine (port 7946 ← 7947)
    → Wine processes input, updates window
```

#### Process Exit Detection

```
Game window unmaps
  → onUnmapWindow callback
  → startExitWatchForUnmappedGameWindow()
    → Match window WM_NAME against container.executablePath
    → If match: poll WinHandler.listProcesses() every 1s
    → Build allowlist: {wineserver, services, explorer, winedevice, svchost, ...}
    → When only allowlisted processes remain → exit()
    → Timeout after EXIT_PROCESS_TIMEOUT_MS → exit()
```

#### Exit Cleanup Sequence

```
exit() {
    isExiting.compareAndSet(false, true)       // guard duplicates
    PostHog.capture("game_exited", ...)
    winHandler.stop()                           // close UDP sockets
    environment.stopEnvironmentComponents()     // in order:
      SysVSharedMemory → XServer → Network → SteamClient →
      ALSA/PulseAudio → VirGL/Vortek → GuestProgramLauncher
    // GuestProgramLauncher.stop() sends SIGKILL to Wine + wineserver -k
    SteamService.keepAlive = false
    PluviaApp.xEnvironment = null
    frameRating.writeSessionSummary()
    onExit()
    navigateBack()
}
```

### Known Issues & Failure Modes

#### Critical

| Issue | Location | Description |
|-------|----------|-------------|
| **EGL context deadlock (VirGL)** | VirGLRendererComponent | `getSharedEGLContext()` waits infinitely for GL thread. If GL thread is blocked on a lock the Wine thread also needs → deadlock, app freeze |
| **Socket creation failure** | XConnectorEpoll | If Unix socket already bound or permissions wrong → RuntimeException, app crash. No recovery path |
| **Texture allocation stall** | GLRenderer | First render of large window → `glTexImage2D` on render thread. 4K drawable can cause 10+ ms frame stutter |

#### High

| Issue | Location | Description |
|-------|----------|-------------|
| **False exit trigger** | XServerScreen exit watch | `onUnmapWindow` fires before `WM_NAME` is set → window doesn't match exe name → exit watch never starts. OR: window unmapped while game still alive → premature exit |
| **Gamepad state corruption** | WinHandler shared mem | Wine reads `gamepad.mem` while app writes — no synchronization. Single-word ARM64 reads are atomic but multi-byte state (sticks + buttons) can be torn |
| **Symlink activation not error-checked** | ContainerManager:89 | `FileUtils.symlink()` can fail silently → wrong container activated → game runs with wrong prefix |
| **Process listener overwrite** | WinHandler | `setOnGetProcessInfoListener()` called without sync. Multiple windows unmapping → only last listener receives process list updates |

#### Medium

| Issue | Location | Description |
|-------|----------|-------------|
| **No shutdown timeout** | XEnvironment | `stopEnvironmentComponents()` calls `stop()` per component — if any hangs (e.g. process.waitFor()), app freezes |
| **SteamPipeServer busy-wait** | SteamPipeServer | `while (input.available() > 0)` is a CPU-burning busy loop. Drains battery on idle |
| **Memory leak on exception** | exit() | No try-catch around `stopEnvironmentComponents()`. If component throws → globals not cleared → Wine process stays alive → next launch conflicts |
| **Drawable lock contention** | GLRenderer | `renderLock` held during GPU upload. Wine's draw thread also acquires it. Both threads contend → frame drops |
| **PulseAudio socket race** | PulseAudioComponent | Daemon takes ~100ms to create socket. Wine might connect before it exists → audio silently fails |

#### Low

| Issue | Location | Description |
|-------|----------|-------------|
| **Epoll thread starvation** | XServerComponent | Single epoll thread for all X11 clients. Slow/blocked client starves others |
| **No dirty-rect optimization** | GLRenderer | `updateScene()` traverses entire window tree every frame even if nothing changed |
| **AHardwareBuffer OOM** | VortekRenderer | GPU memory allocation failure not propagated back to Wine |
| **renderableWindows CME** | GLRenderer | `updateScene()` clears and rebuilds list while `drawFrame()` may be iterating it |

#### DLL/DRM Specific

| Issue | Location | Description |
|-------|----------|-------------|
| **Steamless failure** | unpackExecutableFile() | If Steamless fails, `.unpacked.exe` not created → original exe used, but `needsUnpacking` cleared → won't retry |
| **generate_interfaces_file failure** | unpackExecutableFile() | Per-DLL interface generation logs warning but continues. Missing `steam_interfaces.txt` → Steam API calls may fail at runtime |
| **Container creation retry** | ContainerUtils:664-675 | On first failure, deletes orphaned dir and retries once. Persistent extraction failure → returns null, user gets no game |

## Communication Architecture

**Event Bus** (`PluviaApp.events: EventDispatcher`):
- `AndroidEvent.*` — UI/system (BackPressed, ExternalGameLaunch, DownloadStatusChanged, etc.)
- `SteamEvent.*` — Steam protocol (Connected, LogonEnded, PersonaStateReceived, etc.)
- ViewModels register in `init{}`, unregister in `onCleared()`

**IPC between Android ↔ Linux:**

| Channel | Mechanism |
|---------|-----------|
| Display | Unix socket (`imagefs/tmp/xserver`) |
| Input | UDP (ports 7946/7947) |
| Audio | PulseAudio socket |
| Gamepad | Memory-mapped file |
| Steam API | TCP port 34865 (SteamPipeServer) |
| Shared memory | SysV SHM |

## GOG & Epic (same pattern, different APIs)

Both follow the same architecture as Steam:
- OAuth via Chrome Custom Tabs → callback activity
- Foreground service (GOGService/EpicService) with 15-min sync throttle
- Chunk-based CDN downloads (GOG: 4 parallel, Epic: 6 parallel)
- Room DB entities (`GOGGame`, `EpicGame`)
- Cloud save sync
- Same container/Wine launch pipeline

Key differences: Epic has binary manifest format parsing, ownership token DRM, and third-party launcher detection (EA/Ubisoft). GOG has generation-2 depot manifests and cloud save templates.

## Infrastructure

- **DI**: Hilt (DatabaseModule, AppThemeModule)
- **Analytics**: PostHog (game events, UI interactions), Supabase (game feedback)
- **Crash handling**: Custom `CrashHandler` → `crash_logs/pluvia_crash_{timestamp}.txt` with device info + logcat
- **Logging**: Timber (DebugTree in debug, ReleaseTree filters to INFO+ in release)
- **Crypto**: AES-256-CBC via Android KeyStore for token storage
- **Dynamic feature**: `ubuntufs` module for Linux filesystem layer (on-demand delivery)
- **Build variants**: debug, release, release-signed, release-gold (`.gold` suffix + custom icons)

## Key Files

| File | Purpose |
|------|---------|
| `PluviaApp.kt` | Application class, event bus, analytics init |
| `PluviaMain.kt` | NavHost, screen routing, event handling |
| `SteamService.kt` | Core Steam integration (3800+ lines) |
| `XServerScreen.kt` | Game launch orchestrator |
| `Container.java` | Wine prefix config (~1000 lines) |
| `ContainerManager.java` | Container lifecycle |
| `ContainerUtils.kt` | High-level container helpers |
| `XServer.java` | X11 server implementation |
| `GLRenderer.java` | OpenGL ES rendering |
| `WinHandler.java` | Input routing via UDP |
| `ImageFs.java` | Linux filesystem management |
| `PrefManager.kt` | 100+ preference keys |
| `EventDispatcher.kt` | Global event bus |
| `SteamUtils.kt` | DLL replacement, config files |
| `MainViewModel.kt` | Root navigation + event routing |

## Source Layout

```
app/src/main/java/app/gamenative/
├── PluviaApp.kt, MainActivity.kt, PrefManager.kt, CrashHandler.kt
├── di/                    # Hilt modules (Database, AppTheme)
├── db/                    # Room DB, DAOs (10), converters, migrations
├── data/                  # Data models (30+ classes)
├── enums/                 # Enumerations (14)
├── events/                # EventDispatcher, AndroidEvent, SteamEvent
├── service/               # SteamService, DownloadService, NotificationHelper
│   ├── gog/               # GOGService, GOGManager, GOGDownloadManager, GOGAuth
│   └── epic/              # EpicService, EpicManager, EpicDownloadManager, EpicAuth
├── ui/
│   ├── PluviaMain.kt      # NavHost
│   ├── model/             # 5 ViewModels
│   ├── screen/            # Login, Home, Library, XServer, Settings
│   ├── component/         # Reusable UI components
│   └── theme/             # Compose theming
└── utils/                 # ContainerUtils, SteamUtils, IntentLaunchManager, etc.

app/src/main/java/com/winlator/
├── container/             # Container.java, ContainerManager.java
├── xenvironment/          # XEnvironment, ImageFs, components
├── xserver/               # XServer, WindowManager, Keyboard, Pointer
├── renderer/              # GLRenderer
├── winhandler/            # WinHandler (UDP input)
├── steampipeserver/       # SteamPipeServer (port 34865)
└── widget/                # XServerView (GLSurfaceView)
```
