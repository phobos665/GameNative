package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.DownloadInfo
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * Epic/Legendary Python Bridge
 *
 * Executes Legendary CLI commands via Chaquopy for Epic Games Store operations.
 * Uses stdout parsing for download progress monitoring (Option A approach).
 *
 * Key differences from GOG:
 * - Uses legendary-gl package instead of gogdl
 * - Parses stdout for progress (no callback support in Legendary)
 * - Legendary output format: "Progress: [20.5%] 1.2GB/6.0GB @ 5.4MB/s ETA: 00:15:32"
 */
object EpicPythonBridge {
    private var python: Python? = null
    private var isInitialized = false

    fun initialize(context: Context): Boolean {
        if (isInitialized) return true

        return try {
            Timber.i("Initializing EpicPythonBridge with Chaquopy...")

            if (!Python.isStarted()) {
                try {
                    Python.start(AndroidPlatform(context))
                    Timber.d("Python started by EpicPythonBridge")
                } catch (e: IllegalStateException) {
                    // Python already started by another component (e.g., GOGPythonBridge)
                    Timber.d("Python already started, using existing instance")
                }
            } else {
                Timber.d("Python already started, using existing instance")
            }

            python = Python.getInstance()

            isInitialized = true
            Timber.i("EpicPythonBridge initialized successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize EpicPythonBridge")
            false
        }
    }

    fun isReady(): Boolean = isInitialized && Python.isStarted()

    /**
     * Execute Python code directly (for complex operations like authentication)
     *
     * @param context Android context
     * @param pythonCode Python code to execute
     * @return Result containing stdout output or error
     */
    suspend fun executePythonCode(context: Context, pythonCode: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                initialize(context)

                if (!Python.isStarted()) {
                    return@withContext Result.failure(Exception("Python environment not initialized"))
                }

                val python = Python.getInstance()
                val sys = python.getModule("sys")
                val io = python.getModule("io")

                // Monkey-patch sys._getframe BEFORE any legendary imports
                // Chaquopy doesn't support frame introspection
                python.builtins.callAttr("exec", """
import sys
def _dummy_getframe(depth=0):
    class DummyFrame:
        f_code = type('obj', (object,), {'co_filename': '<unknown>', 'co_name': '<unknown>'})()
        f_lineno = 0
        f_back = None
    return DummyFrame()
sys._getframe = _dummy_getframe
""")

                // Capture stdout with a wrapper that supports reconfigure()
                // StringIO doesn't have reconfigure(), but legendary CLI tries to call it
                val stdoutCapture = io.callAttr("StringIO")

                // Add a no-op reconfigure method to the StringIO object
                python.builtins.callAttr("setattr", stdoutCapture, "reconfigure",
                    python.builtins.callAttr("eval", "lambda **kwargs: None"))

                val originalStdout = sys.get("stdout")
                sys.put("stdout", stdoutCapture)

                try {
                    // Execute the Python code with proper namespace
                    // Create empty dicts for globals and locals to give exec() proper context
                    val builtins = python.getBuiltins()
                    val dict = builtins.callAttr("dict")

                    // Execute with separate namespace to avoid frame issues
                    builtins.callAttr("exec", pythonCode, dict)

                    // Get captured output
                    val output = stdoutCapture.callAttr("getvalue").toString()

                    // Restore stdout
                    sys.put("stdout", originalStdout)

                    Result.success(output)
                } catch (e: Exception) {
                    sys.put("stdout", originalStdout)
                    throw e
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to execute Python code")
                Result.failure(e)
            }
        }
    }

    /**
     * Execute Legendary CLI command
     *
     * @param args Command line arguments to pass to legendary CLI
     * @return Result containing command output or error
     *
     * Example: executeCommand("list", "--third-party", "--json")
     * Executes: legendary list --third-party --json
     */
    suspend fun executeCommand(vararg args: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("executeCommand called with args: ${args.joinToString(" ")}")

                if (!Python.isStarted()) {
                    Timber.e("Python is not started! Cannot execute Legendary command")
                    return@withContext Result.failure(Exception("Python environment not initialized"))
                }

                val python = Python.getInstance()
                Timber.d("Python instance obtained successfully")

                val sys = python.getModule("sys")
                val io = python.getModule("io")
                val originalArgv = sys.get("argv")

                try {
                    // Monkey-patch sys._getframe BEFORE importing legendary.cli
                    // Chaquopy doesn't support frame introspection, so we provide a dummy implementation
                    // This must be done before importing legendary because some modules check frames on import
                    Timber.d("Monkey-patching sys._getframe...")
                    python.builtins.callAttr("exec", """
import sys
def _dummy_getframe(depth=0):
    class DummyFrame:
        f_code = type('obj', (object,), {'co_filename': '<unknown>', 'co_name': '<unknown>'})()
        f_lineno = 0
        f_back = None
    return DummyFrame()
sys._getframe = _dummy_getframe
""")
                    Timber.d("sys._getframe monkey-patched successfully")

                    Timber.d("Importing legendary.cli module...")
                    val legendaryCli = python.getModule("legendary.cli")
                    Timber.d("legendary.cli module imported successfully")

                    // Set up arguments for argparse
                    val argsList = listOf("legendary") + args.toList()
                    Timber.d("Setting Legendary arguments for argparse: ${args.joinToString(" ")}")
                    val pythonList = python.builtins.callAttr("list", argsList.toTypedArray())
                    sys.put("argv", pythonList)
                    Timber.d("sys.argv set to: $argsList")

                    // Capture stdout and stderr
                    val stdoutCapture = io.callAttr("StringIO")
                    val stderrCapture = io.callAttr("StringIO")

                    // Add a no-op reconfigure method to both StringIO objects
                    // legendary CLI tries to call reconfigure() which StringIO doesn't have
                    python.builtins.callAttr("setattr", stdoutCapture, "reconfigure",
                        python.builtins.callAttr("eval", "lambda **kwargs: None"))
                    python.builtins.callAttr("setattr", stderrCapture, "reconfigure",
                        python.builtins.callAttr("eval", "lambda **kwargs: None"))

                    val originalStdout = sys.get("stdout")
                    val originalStderr = sys.get("stderr")
                    sys.put("stdout", stdoutCapture)
                    sys.put("stderr", stderrCapture)
                    Timber.d("stdout and stderr capture configured")

                    // Execute the main function
                    Timber.d("Calling legendary.cli.main()...")
                    try {
                        legendaryCli.callAttr("main")
                        Timber.d("legendary.cli.main() completed")
                    } catch (e: Exception) {
                        // Legendary CLI calls sys.exit() on completion, which raises SystemExit
                        // SystemExit with code 0 is success, anything else is an error
                        if (e.javaClass.simpleName == "PyException") {
                            val exceptionStr = e.message ?: ""
                            if (exceptionStr.contains("SystemExit") && exceptionStr.contains(": 0")) {
                                Timber.d("Legendary completed successfully (SystemExit: 0)")
                            } else {
                                Timber.e(e, "Legendary failed with exception: $exceptionStr")
                                throw e
                            }
                        } else {
                            throw e
                        }
                    }

                    // Get the captured output
                    val output = stdoutCapture.callAttr("getvalue").toString()
                    val errorOutput = stderrCapture.callAttr("getvalue").toString()
                    Timber.d("Legendary raw output (length: ${output.length}): $output")
                    if (errorOutput.isNotEmpty()) {
                        Timber.w("Legendary stderr output: $errorOutput")
                    }

                    // Restore original stdout and stderr
                    sys.put("stdout", originalStdout)
                    sys.put("stderr", originalStderr)

                    if (output.isNotEmpty()) {
                        Timber.d("Returning success with output")
                        Result.success(output)
                    } else {
                        Timber.w("Legendary execution completed but output is empty")
                        Result.success("Legendary execution completed")
                    }

                } catch (e: Exception) {
                    Timber.e(e, "Legendary execution exception: ${e.javaClass.simpleName} - ${e.message}")
                    Timber.e("Exception stack trace: ${e.stackTraceToString()}")
                    Result.failure(Exception("Legendary execution failed: ${e.message}", e))
                } finally {
                    // Restore original sys.argv
                    sys.put("argv", originalArgv)
                    Timber.d("sys.argv restored")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to execute Legendary command: ${args.joinToString(" ")}")
                Timber.e("Outer exception stack trace: ${e.stackTraceToString()}")
                Result.failure(Exception("Legendary execution failed: ${e.message}", e))
            }
        }
    }

    /**
     * Execute Legendary install/download command with progress monitoring
     *
     * Parses stdout in real-time to extract progress information.
     * Legendary outputs progress like:
     * "Progress: [20.5%] 1.2GB/6.0GB @ 5.4MB/s ETA: 00:15:32"
     *
     * @param downloadInfo DownloadInfo object to track progress
     * @param args Command line arguments (should be install command)
     * @return Result containing command output or error
     */
    suspend fun executeCommandWithProgress(
        downloadInfo: DownloadInfo,
        vararg args: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.i("Starting Legendary command with progress monitoring: ${args.joinToString(" ")}")

                if (!Python.isStarted()) {
                    return@withContext Result.failure(Exception("Python environment not initialized"))
                }

                val python = Python.getInstance()
                val sys = python.getModule("sys")
                val io = python.getModule("io")
                val originalArgv = sys.get("argv")
                val originalStdout = sys.get("stdout")
                val originalStderr = sys.get("stderr")

                try {
                    val legendaryCli = python.getModule("legendary.cli")

                    // Set up arguments
                    val argsList = listOf("legendary") + args.toList()
                    val pythonList = python.builtins.callAttr("list", argsList.toTypedArray())
                    sys.put("argv", pythonList)

                    // Capture stdout and stderr for parsing
                    val stdoutCapture = io.callAttr("StringIO")
                    val stderrCapture = io.callAttr("StringIO")

                    // Add reconfigure support to both StringIO objects
                    python.builtins.callAttr("setattr", stdoutCapture, "reconfigure",
                        python.builtins.callAttr("eval", "lambda **kwargs: None"))
                    python.builtins.callAttr("setattr", stderrCapture, "reconfigure",
                        python.builtins.callAttr("eval", "lambda **kwargs: None"))

                    sys.put("stdout", stdoutCapture)
                    sys.put("stderr", stderrCapture)

                    // Check for cancellation before starting
                    ensureActive()

                    // Start progress monitoring coroutine
                    val monitorJob = launch {
                        monitorProgressFromStdout(stdoutCapture, downloadInfo)
                    }

                    // Start fallback estimator
                    val estimatorJob = launch {
                        estimateProgress(downloadInfo)
                    }

                    try {
                        // Execute the install command
                        legendaryCli.callAttr("main")

                        // Mark as complete
                        downloadInfo.setProgress(1.0f)
                        Timber.i("Legendary download completed successfully")

                        Result.success("Download completed")
                    } finally {
                        monitorJob.cancel()
                        estimatorJob.cancel()
                        sys.put("stdout", originalStdout)
                    }

                } catch (e: Exception) {
                    Timber.e(e, "Legendary download failed: ${e.message}")
                    Result.failure(e)
                } finally {
                    sys.put("argv", originalArgv)
                    sys.put("stdout", originalStdout)
                    sys.put("stderr", originalStderr)
                }
            } catch (e: CancellationException) {
                Timber.i("Legendary download cancelled")
                throw e // Re-throw to propagate cancellation
            } catch (e: Exception) {
                Timber.e(e, "Failed to execute Legendary download: ${args.joinToString(" ")}")
                Result.failure(e)
            }
        }
    }

    /**
     * Monitor stdout for progress updates
     *
     * Parses Legendary's progress output:
     * - "Progress: [20.5%] 1.2GB/6.0GB @ 5.4MB/s ETA: 00:15:32"
     * - "Downloaded: 1.2 GiB / 6.0 GiB @ 5.4 MiB/s, ETA: 00:15:32"
     */
    private suspend fun monitorProgressFromStdout(
        stdoutCapture: com.chaquo.python.PyObject,
        downloadInfo: DownloadInfo
    ) {
        try {
            var lastOutputLength = 0

            while (coroutineContext.isActive && downloadInfo.getProgress() < 1.0f) {
                delay(500) // Poll every 500ms

                try {
                    // Get current output
                    val output = stdoutCapture.callAttr("getvalue").toString()

                    // Only process new content
                    if (output.length > lastOutputLength) {
                        val newContent = output.substring(lastOutputLength)
                        parseProgressFromOutput(newContent, downloadInfo)
                        lastOutputLength = output.length
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Error reading stdout during progress monitoring")
                }
            }
        } catch (e: CancellationException) {
            Timber.d("Progress monitoring cancelled")
        } catch (e: Exception) {
            Timber.w(e, "Error in progress monitoring")
        }
    }

    /**
     * Parse Legendary progress output and update DownloadInfo
     *
     * Legendary formats:
     * - "Progress: [20.5%] 1.2GB/6.0GB @ 5.4MB/s ETA: 00:15:32"
     * - "Downloaded: 1.2 GiB / 6.0 GiB @ 5.4 MiB/s, ETA: 00:15:32"
     * - "+ Downloaded 1.20GB/6.00GB (20.0%) [5.40MB/s] ETA: 00:15:32"
     */
    private fun parseProgressFromOutput(output: String, downloadInfo: DownloadInfo) {
        try {
            // Extract percentage: [20.5%] or (20.0%)
            val percentRegex = Regex("""[\[\(](\d+\.?\d*)%[\]\)]""")
            val percentMatch = percentRegex.find(output)

            if (percentMatch != null) {
                val percent = percentMatch.groupValues[1].toFloat()
                val progress = (percent / 100f).coerceIn(0f, 1f)
                downloadInfo.setProgress(progress)

                Timber.d("Parsed progress: $percent%")
            }

            // Extract size info: 1.2GB/6.0GB or 1.2 GiB / 6.0 GiB
            val sizeRegex = Regex("""(\d+\.?\d*)\s*G[iB]+\s*/\s*(\d+\.?\d*)\s*G[iB]+""", RegexOption.IGNORE_CASE)
            val sizeMatch = sizeRegex.find(output)

            if (sizeMatch != null) {
                val downloaded = sizeMatch.groupValues[1].toFloat()
                val total = sizeMatch.groupValues[2].toFloat()

                // Convert GB to bytes (using binary GB = 1024^3)
                val downloadedBytes = (downloaded * 1024 * 1024 * 1024).toLong()
                val totalBytes = (total * 1024 * 1024 * 1024).toLong()

                if (totalBytes > 0 && downloadInfo.getTotalExpectedBytes() == 0L) {
                    downloadInfo.setTotalExpectedBytes(totalBytes)
                }

                if (downloadedBytes > downloadInfo.getBytesDownloaded()) {
                    val deltaBytes = downloadedBytes - downloadInfo.getBytesDownloaded()
                    downloadInfo.updateBytesDownloaded(deltaBytes)
                }

                Timber.d("Parsed size: ${downloaded}GB / ${total}GB")
            }

            // Extract speed: @ 5.4MB/s or [5.40MB/s]
            val speedRegex = Regex("""[@\[](\d+\.?\d*)\s*M[iB]+/s[\]\s]""", RegexOption.IGNORE_CASE)
            val speedMatch = speedRegex.find(output)

            if (speedMatch != null) {
                val speedMBps = speedMatch.groupValues[1].toFloat()
                Timber.d("Parsed speed: ${speedMBps}MB/s")
            }

            // Extract ETA: ETA: 00:15:32
            val etaRegex = Regex("""ETA:\s*([\d:]+)""")
            val etaMatch = etaRegex.find(output)

            if (etaMatch != null) {
                val eta = etaMatch.groupValues[1]
                if (eta != "00:00:00") {
                    downloadInfo.updateStatusMessage("ETA: $eta")
                    Timber.d("Parsed ETA: $eta")
                }
            }

        } catch (e: Exception) {
            Timber.w(e, "Error parsing progress from output: ${output.take(100)}")
        }
    }

    /**
     * Estimate progress when parsing fails
     * Fallback to time-based estimation (same as GOG)
     */
    private suspend fun estimateProgress(downloadInfo: DownloadInfo) {
        try {
            var lastProgress = 0.0f
            val startTime = System.currentTimeMillis()

            while (downloadInfo.getProgress() < 1.0f && downloadInfo.getProgress() >= 0.0f) {
                delay(3000L) // Update every 3 seconds

                val elapsed = System.currentTimeMillis() - startTime
                val estimatedProgress = when {
                    elapsed < 5000 -> 0.05f
                    elapsed < 15000 -> 0.15f
                    elapsed < 30000 -> 0.30f
                    elapsed < 60000 -> 0.50f
                    elapsed < 120000 -> 0.70f
                    elapsed < 180000 -> 0.85f
                    else -> 0.95f
                }.coerceAtLeast(lastProgress)

                // Only update if progress hasn't been set by parsing
                if (downloadInfo.getProgress() <= lastProgress + 0.01f) {
                    downloadInfo.setProgress(estimatedProgress)
                    lastProgress = estimatedProgress
                    Timber.d("Estimated progress: %.1f%%", estimatedProgress * 100)
                } else {
                    // Parsing is working, update our tracking
                    lastProgress = downloadInfo.getProgress()
                }
            }
        } catch (e: CancellationException) {
            Timber.d("Progress estimation cancelled")
        } catch (e: Exception) {
            Timber.w(e, "Error in progress estimation")
        }
    }

    /**
     * Parse Epic manifest file using Legendary's binary parser
     *
     * Takes a manifest binary file and extracts:
     * - Chunk data (GUID, hash, size, path)
     * - File manifest list (filename, size, chunk parts)
     *
     * @param context Android context
     * @param manifestFilePath Absolute path to the manifest file
     * @return Result containing JSON string with chunks and files data
     */
    suspend fun parseManifestFile(context: Context, manifestFilePath: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                initialize(context)

                if (!Python.isStarted()) {
                    return@withContext Result.failure(Exception("Python environment not initialized"))
                }

                val pythonCode = """
import json
from legendary.core import LegendaryCore

def to_hex(value):
    if value is None:
        return None
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, str):
        return value
    if hasattr(value, 'hex'):
        try:
            return value.hex()
        except:
            pass
    return str(value)

try:
    core = LegendaryCore()

    # Load manifest from file
    with open('$manifestFilePath', 'rb') as f:
        manifest_data = f.read()

    manifest = core.load_manifest(manifest_data)

    result = {
        "chunks": [],
        "files": []
    }

    # Chunk data
    for chunk in manifest.chunk_data_list.elements:
        result["chunks"].append({
            "guid": to_hex(chunk.guid),
            "hash": to_hex(getattr(chunk, 'hash', None)),
            "sha_hash": to_hex(chunk.sha_hash),
            "size": chunk.file_size,
            "window_size": chunk.window_size,
            "path": chunk.path
        })

    # File data
    for fm in manifest.file_manifest_list.elements:
        result["files"].append({
            "filename": fm.filename,
            "file_size": fm.file_size,
            "hash": to_hex(getattr(fm, 'hash', None)),
            "chunk_parts": [
                {"guid": to_hex(cp.guid), "offset": cp.offset, "size": cp.size}
                for cp in fm.chunk_parts
            ]
        })

    print(json.dumps(result))
except Exception as e:
    import traceback
    print(json.dumps({"error": str(e), "traceback": traceback.format_exc()}))
"""

                val result = executePythonCode(context, pythonCode)

                if (result.isFailure) {
                    return@withContext Result.failure(
                        result.exceptionOrNull() ?: Exception("Python execution failed")
                    )
                }

                val output = result.getOrNull() ?: ""
                val lines = output.trim().lines()
                if (lines.isEmpty()) {
                    return@withContext Result.failure(Exception("No output from Python manifest parser"))
                }

                val lastLine = lines.last()
                Result.success(lastLine)

            } catch (e: Exception) {
                Timber.e(e, "Failed to parse manifest file: $manifestFilePath")
                Result.failure(e)
            }
        }
    }

    /**
     * Test legendary import - verifies legendary-android can be imported without multiprocessing errors
     * @return Version string if successful, error message if failed
     */
    fun testLegendaryImport(context: Context): String {
        initialize(context)

        return try {
            val legendary = python?.getModule("legendary") ?: return "ERROR: Python not initialized"
            val version = legendary.get("__version__").toString()
            Timber.i("legendary-android import successful, version: $version")
            "OK: legendary v$version"
        } catch (e: Exception) {
            Timber.e(e, "Failed to import legendary")
            "ERROR: ${e.message}"
        }
    }
}
