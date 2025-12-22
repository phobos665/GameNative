# Achievements (Steam / Goldberg) – How It Works

GameNative supports **local Steam achievements** for Steam games by using a **Goldberg-style** `steam_api.dll` shim (bundled in `app/src/main/assets/steampipe/`). This enables games to call `SetAchievement()` / `IndicateAchievementProgress()` and have the results **persist locally**.

This document explains what files are involved, how schemas are generated, how popups/UI work, and what to do when something doesn’t show up.

---

## Quick Summary

- Achievements only work if the game is using a **Goldberg-like `steam_api.dll`** (not the real Steam client APIs).
- Goldberg needs a schema file: `steam_settings/achievements.json`.
- GameNative can generate that schema:
  - **Without a key:** uses a public Steam endpoint to get **achievement API names** (often no pretty names/icons).
  - **With `STEAM_WEB_API_KEY`:** uses Steam Web API schema to get **display names/descriptions/icons** and downloads icons.
- Earned state is stored in a per-user `achievements.json` file inside the container’s Wine prefix (see paths below).
- GameNative shows lightweight “achievement unlocked” popups by polling that per-user file while the game runs.

---

## Files and Folders

### 1) `steam_settings/` (per game)
GameNative creates/maintains `steam_settings/` next to the game’s Steam DLLs when preparing a Steam game.

Important achievement files:

- `steam_settings/achievements.json`
  - **Schema/definitions** used by Goldberg to know what achievements exist.
- `steam_settings/achievement_images/`
  - Optional **downloaded icons** when a Web API key is available.

### 2) User achievements state file (per user + per app)
Goldberg persists unlock/progress state in a JSON file named `achievements.json` under the configured save directory.

In GameNative, `configs.user.ini` typically sets `user::saves::local_save_path` to:
- `C:\Program Files (x86)\Steam\userdata\<accountId>`

Given that, the most common per-user state path is:
- `C:\Program Files (x86)\Steam\userdata\<accountId>\<appId>\achievements.json`

GameNative searches additional common fallback locations, including:
- `C:\users\<user>\AppData\Roaming\GSE Saves\<appId>\achievements.json`
- `C:\users\<user>\AppData\Roaming\Goldberg SteamEmu Saves\<appId>\achievements.json`

---

## Schema Generation (the important part)

Goldberg does **not** reliably create unknown achievements by itself; it expects `steam_settings/achievements.json` to exist. Without that file, in-game achievement calls may fail to persist.

GameNative will generate the schema when needed via `app/src/main/java/app/gamenative/utils/SteamAchievements.kt`.

### Sources (in order)

1) **Steam Web API (best quality, requires key)**
   - Endpoint: `ISteamUserStats/GetSchemaForGame`
   - Output includes:
     - API name
     - localized display name + description
     - hidden flag
     - icon + gray icon URLs (downloaded into `steam_settings/achievement_images/`)

2) **Public Steam endpoint (no key)**
   - Endpoint: `GetGlobalAchievementPercentagesForApp`
   - Output includes:
     - achievement **API names only**
   - Limitations:
     - no descriptions
     - no icons
     - display name will be the API name

### When generation happens

- **Automatic:** during Steam DLL setup (only when dealing with `steam_api*.dll`).
  - Entry point: `app/src/main/java/app/gamenative/utils/SteamUtils.kt`
- **Manual (UI):** Steam game screen → menu → **Achievements** → Generate/Regenerate.
  - UI: `app/src/main/java/app/gamenative/ui/component/dialog/AchievementsDialog.kt`

---

## In-Game “Achievement Unlocked” Popups

While a Steam game is running, GameNative polls the per-user achievements state file and shows a toast when new entries flip to `earned=true`.

- Code: `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
- Behavior:
  - polls about every ~2 seconds
  - detects newly-earned achievements by comparing state snapshots
  - shows an Android Toast (`Achievement unlocked: ...`)

Notes:
- If the game/emulator only writes the file on exit or infrequently, notifications can be delayed.
- If no schema exists, popups will still trigger (based on API name), but display names may be “raw” without `STEAM_WEB_API_KEY`.

---

## Achievements Viewer UI

For installed Steam games:
- Steam game screen → options menu → **Achievements**

The dialog:
- loads `steam_settings/achievements.json` (if present) for definitions
- loads the per-user `achievements.json` for earned/progress state
- shows earned/unearned and progress (if present in the user state file)
- offers:
  - **Refresh**: reloads definitions and user state
  - **Generate/Regenerate**: creates schema if missing (or overwrites to refresh)

---

## Configuring `STEAM_WEB_API_KEY` (Optional)

Add this to `local.properties` (or set as an environment variable during build):

```
STEAM_WEB_API_KEY=your_api_key_here
```

This is compiled into `BuildConfig.STEAM_WEB_API_KEY` (see `app/build.gradle.kts`) and is used only to improve schema generation.

---

## Troubleshooting

### “No achievements found”
- Open Achievements UI and press **Generate**.
- If generation fails, check:
  - network connectivity
  - Steam endpoints reachable
  - the game actually has achievements
  - optionally set `STEAM_WEB_API_KEY` for the richer schema endpoint

### Achievements unlock in-game but don’t show in UI / no popups
- The game may not be writing the user state file where we expect.
- The game may not be using the Goldberg `steam_api.dll` path (e.g., different Steam integration).
- Try:
  - open Achievements dialog → Refresh while the game has been running
  - verify `steam_settings/` exists under the game directory and contains `steam_appid.txt`
  - verify a per-user `achievements.json` appears under:
    - `C:\Program Files (x86)\Steam\userdata\<accountId>\<appId>\achievements.json`

### “Pretty names/icons are missing”
- That’s expected without `STEAM_WEB_API_KEY`.
- With a key, regenerate the schema to download metadata + icons.

---

## Manual Schema (Advanced / Optional)

If you already have a Goldberg-format schema, place it at:
- `steam_settings/achievements.json`

Format is a JSON array of objects, typically like:

```json
[
  {
    "name": "ACH_WIN_ONE_GAME",
    "displayName": "Winner",
    "description": "Win one game",
    "hidden": "0",
    "icon": "achievement_images/winner_icon.jpg",
    "icon_gray": "achievement_images/winner_icongray.jpg"
  }
]
```

Only `name` is strictly required for Goldberg achievement persistence.

---

## What “Sync to Steam” Means Here

Today, achievements are **local to the emulator**. GameNative includes a stub hook for future Steam sync work:
- `SteamAchievements.syncUnlockedToSteamStub(...)`

Actual syncing to Steam is non-trivial and may be restricted or undesirable; it is intentionally not implemented.

