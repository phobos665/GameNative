package com.winlator.core;

import android.content.Context;
import android.util.Log;
import com.winlator.core.envvars.EnvVars;
import com.winlator.container.Container;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xenvironment.components.GlibcProgramLauncherComponent;

import java.io.File;

/**
 * Helper class for running Proton game fixes (protonfixes) in glibc containers.
 *
 * Protonfixes are Python scripts from GE-Proton that apply game-specific
 * compatibility tweaks (environment variables, DLL overrides, registry changes,
 * etc.) before launching games.
 *
 * Unlike the bionic container which uses Android Java/Kotlin code, glibc
 * containers can run Python directly via Box64, allowing us to execute the
 * original protonfixes scripts without needing to extract them to JSON or
 * reimplement them in Java.
 */
public class ProtonFixesRunner {

    private static final String TAG = "ProtonFixesRunner";

    /**
     * Apply protonfixes for a game before launching Wine.
     *
     * This method checks if: 1. The container is using a glibc Proton build 2.
     * Python3 is available in the container 3. A protonfix exists for the given
     * Steam App ID
     *
     * If all conditions are met, it executes the protonfix script which will: -
     * Set environment variables for the game - Apply DLL overrides - Modify
     * registry settings - Perform any other compatibility tweaks
     *
     * @param context Android context
     * @param container The container being used
     * @param steamAppId The Steam App ID (e.g., 1245620 for Elden Ring), can be
     * null
     * @param protonPath Absolute path to the Proton installation (e.g.,
     * /opt/proton-ge-10-25-x86_64)
     * @param winePrefix Absolute path to the Wine prefix directory
     * @return true if protonfixes were successfully applied, false otherwise
     */
    public static boolean applyProtonfixes(Context context, Container container,
            Integer steamAppId, String protonPath,
            String winePrefix) {
        // Only apply for glibc containers
        if (!Container.GLIBC.equals(container.getContainerVariant())) {
            Log.d(TAG, "Container is not glibc, skipping protonfixes");
            return false;
        }

        // Need a Steam App ID to know which fix to apply
        if (steamAppId == null) {
            Log.d(TAG, "No Steam App ID provided, skipping protonfixes");
            return false;
        }

        // Check if protonfix exists for this game
        File protonfixFile = new File(protonPath, "protonfixes/gamefixes-steam/" + steamAppId + ".py");
        if (!protonfixFile.exists()) {
            Log.d(TAG, "No protonfix found for Steam App ID: " + steamAppId);
            return false;
        }

        Log.i(TAG, "Found protonfix for Steam App ID: " + steamAppId + " at " + protonfixFile.getAbsolutePath());

        // Check if Python3 is available in container
        ImageFs imageFs = ImageFs.find(context);
        File python3 = new File(imageFs.getRootDir(), "usr/bin/python3");
        if (!python3.exists()) {
            Log.w(TAG, "Python3 not found in container at " + python3.getAbsolutePath() + ", cannot run protonfixes");
            return false;
        }

        Log.d(TAG, "Executing protonfix command for Steam App ID: " + steamAppId);

        try {
            // Execute the Python script via Box64 in the container
            File rootDir = imageFs.getRootDir();
            String box64Path = rootDir.getPath() + "/usr/local/bin/box64";
            String python3Path = rootDir.getPath() + "/usr/bin/python3";

            // Build the actual command to execute via Box64
            // Format: box64 python3 -c "python code"
            String fullCommand = box64Path + " " + python3Path + " -c \""
                    + "import sys; "
                    + "sys.path.insert(0, '" + protonPath + "'); "
                    + "from protonfixes.gamefixes_steam._%d import main; "
                    + "main()\"";
            fullCommand = String.format(fullCommand, steamAppId);

            // Set up environment for protonfix execution
            EnvVars protonfixEnv = new EnvVars();
            protonfixEnv.put("STEAM_COMPAT_DATA_PATH", winePrefix);
            protonfixEnv.put("PYTHONPATH", protonPath);
            protonfixEnv.put("PROTON_DLL_COPY", "*");
            protonfixEnv.put("HOME", imageFs.home_path);
            protonfixEnv.put("USER", ImageFs.USER);
            protonfixEnv.put("TMPDIR", rootDir.getPath() + "/tmp");
            protonfixEnv.put("DISPLAY", ":0");
            protonfixEnv.put("LD_LIBRARY_PATH", rootDir.getPath() + "/usr/lib");
            protonfixEnv.put("BOX64_LD_LIBRARY_PATH", rootDir.getPath() + "/usr/lib/x86_64-linux-gnu");
            protonfixEnv.put("BOX64_NOBANNER", "1");

            Log.i(TAG, "Executing protonfix via Box64: " + fullCommand);

            // Execute the protonfix and wait for completion
            int exitCode = ProcessHelper.exec(fullCommand, protonfixEnv.toStringArray(), rootDir);

            if (exitCode == 0) {
                Log.i(TAG, "Successfully applied protonfix for Steam App ID: " + steamAppId);
                return true;
            } else {
                Log.w(TAG, "Protonfix exited with code: " + exitCode + " for Steam App ID: " + steamAppId);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute protonfix: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Check if protonfixes are available for the given Proton installation.
     *
     * @param protonPath Absolute path to the Proton installation
     * @return true if protonfixes directory exists
     */
    public static boolean hasProtonfixes(String protonPath) {
        if (protonPath == null || protonPath.isEmpty()) {
            return false;
        }

        File protonfixesDir = new File(protonPath, "protonfixes");
        return protonfixesDir.exists() && protonfixesDir.isDirectory();
    }

    /**
     * Get the path to a specific protonfix file.
     *
     * @param protonPath Absolute path to the Proton installation
     * @param steamAppId Steam App ID
     * @return File object for the protonfix, or null if not found
     */
    public static File getProtonfixFile(String protonPath, int steamAppId) {
        if (protonPath == null || protonPath.isEmpty()) {
            return null;
        }

        File protonfixFile = new File(protonPath, "protonfixes/gamefixes-steam/" + steamAppId + ".py");
        return protonfixFile.exists() ? protonfixFile : null;
    }

    /**
     * Install Python3 if protonfixes are available in the Proton installation.
     * This checks if: 1. The container is glibc variant 2. The Proton
     * installation has protonfixes directory 3. Python3 is not already
     * installed in the container
     *
     * If all conditions are met, it extracts python3.tzst from assets to the
     * container.
     *
     * @param context Android context
     * @param container The container being used
     * @param protonPath Absolute path to the Proton installation
     */
    public static void installPython3IfNeeded(Context context, Container container, String protonPath) {
        // Only install for glibc containers
        if (container == null || !Container.GLIBC.equals(container.getContainerVariant())) {
            return;
        }

        // Check if protonfixes are available in the Proton installation
        if (!hasProtonfixes(protonPath)) {
            return;
        }

        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();

        // Check if Python3 is already installed
        File python3 = new File(rootDir, "usr/bin/python3");
        if (!python3.exists()) {
            Log.i(TAG, "Protonfixes detected, installing Python3...");
            try {
                // Extract Python3 from assets
                TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD,
                        context.getAssets(),
                        "python3.tzst",
                        rootDir
                );
                Log.i(TAG, "Python3 installed successfully for protonfixes support");
            } catch (Exception e) {
                Log.e(TAG, "Failed to install Python3: " + e.getMessage(), e);
            }
        } else {
            Log.d(TAG, "Python3 already installed at " + python3.getAbsolutePath());
        }
    }
}
