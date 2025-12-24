package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.EpicGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EpicDownloadManager handles downloading Epic games using Kotlin/OkHttp
 * instead of Legendary's Python downloader.
 *
 * Epic's CDN structure:
 * 1. Fetch manifest from CDN (contains list of chunks and files)
 * 2. Download chunks from CDN (compressed data)
 * 3. Decompress and assemble chunks into files
 * 4. Verify file hashes
 *
 * Manifest structure (from legendary.models.manifest):
 * - meta: App metadata (app_name, build_version, etc.)
 * - chunk_data_list: List of chunks to download
 * - file_manifest_list: List of files and their chunk composition
 *
 * NOTE: This still uses EpicPythonBridge for manifest parsing
 * TODO: Implement native manifest parsing to fully remove Python dependency
 */
@Singleton
class EpicDownloadManager @Inject constructor() {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 4
        private const val CHUNK_BUFFER_SIZE = 1024 * 1024 // 1MB buffer for decompression
    }

    // TODO: Update this so that it can give a proper download tracker for the front-end. Currently it's not tracking correctly.
    /**
     * Download and install an Epic game
     *
     * @param context Android context
     * @param game Epic game to download
     * @param installPath Directory where game will be installed
     * @param downloadInfo Progress tracker
     * @return Result indicating success or failure
     */
    suspend fun downloadGame(
        context: Context,
        game: EpicGame,
        installPath: String,
        downloadInfo: DownloadInfo
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("Starting download for ${game.title} to $installPath")

            // Step 1: Authenticate and get manifest
            val manifestResult = fetchManifestData(context, game.appName)
            if (manifestResult.isFailure) {
                return@withContext Result.failure(
                    manifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest")
                )
            }

            val manifestData = manifestResult.getOrNull()!!
            Timber.tag("Epic").d("Manifest fetched, parsing...")

            // Step 2: Parse manifest to get chunks and files
            val manifest = parseManifest(manifestData)

            val totalSize = manifest.totalSize
            val chunkCount = manifest.chunks.size
            val fileCount = manifest.files.size

            Timber.tag("Epic").i("""
                |Download prepared:
                |  Total size: ${totalSize / 1_000_000_000.0} GB
                |  Chunks: $chunkCount
                |  Files: $fileCount
            """.trimMargin())

            downloadInfo.setTotalExpectedBytes(totalSize)
            downloadInfo.updateStatusMessage("Downloading chunks...")

            // Step 3: Download chunks in parallel
            val chunkDir = File(installPath, ".chunks")
            chunkDir.mkdirs()

            // Download chunks in batches to avoid overwhelming the system
            manifest.chunks.chunked(MAX_PARALLEL_DOWNLOADS).forEach { chunkBatch ->
                if (!downloadInfo.isActive()) {
                    Timber.tag("Epic").w("Download cancelled by user")
                    return@withContext Result.failure(Exception("Download cancelled"))
                }

                // Download batch in parallel
                val results = chunkBatch.map { chunk ->
                    async {
                        downloadChunk(chunk, chunkDir, manifestData.baseUrls, downloadInfo)
                    }
                }.awaitAll()

                // Check if any download failed
                results.firstOrNull { it.isFailure }?.let { failedResult ->
                    return@withContext Result.failure(
                        failedResult.exceptionOrNull() ?: Exception("Failed to download chunk")
                    )
                }
            }

            downloadInfo.updateStatusMessage("Decompressing and assembling files...")

            // Step 4: Assemble files from chunks
            val installDir = File(installPath)
            installDir.mkdirs()

            for ((index, fileManifest) in manifest.files.withIndex()) {
                downloadInfo.updateStatusMessage("Assembling file ${index + 1}/$fileCount")

                val assembleResult = assembleFile(fileManifest, chunkDir, installDir)
                if (assembleResult.isFailure) {
                    return@withContext Result.failure(
                        assembleResult.exceptionOrNull() ?: Exception("Failed to assemble file")
                    )
                }
            }

            // Step 5: Cleanup chunk directory
            chunkDir.deleteRecursively()

            // Log final directory structure
            Timber.tag("Epic").i("Download completed successfully for ${game.title}")
            logDirectoryStructure(installDir)

            downloadInfo.updateStatusMessage("Complete")
            downloadInfo.setProgress(1.0f)
            downloadInfo.emitProgressChange() // Force final progress update

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Download failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetch manifest data from Epic CDN using native Kotlin/HTTP
     *
     * This replaces the Python/Legendary implementation with direct API calls:
     * 1. Get manifest URLs from Epic's launcher API
     * 2. Download manifest bytes from CDN
     * 3. Parse manifest using Python (still needed for complex binary format)
     */
    private suspend fun fetchManifestData(context: Context, appName: String): Result<ManifestData> {
        return try {
            // Step 1: Get credentials
            val credentialsResult = EpicAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                return Result.failure(Exception("Not authenticated with Epic Games"))
            }
            val credentials = credentialsResult.getOrNull()!!
            val accessToken = credentials.accessToken

            // Step 2: Get game metadata to find namespace and catalog ID
            val gameMetadata = getGameMetadata(context, appName, accessToken)
                ?: return Result.failure(Exception("Game not found: $appName"))

            Timber.tag("Epic").d("Found game: ${gameMetadata.title} (${gameMetadata.namespace}/${gameMetadata.catalogItemId})")

            // Step 3: Get manifest URLs from Epic launcher API
            val manifestApiUrl = "https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/public/assets/v2/platform/Windows/namespace/${gameMetadata.namespace}/catalogItem/${gameMetadata.catalogItemId}/app/$appName/label/Live"

            val manifestRequest = Request.Builder()
                .url(manifestApiUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit")
                .build()

            Timber.tag("Epic").d("Fetching manifest metadata from: $manifestApiUrl")
            val manifestResponse = okHttpClient.newCall(manifestRequest).execute()

            if (!manifestResponse.isSuccessful) {
                val error = manifestResponse.body?.string() ?: "Unknown error"
                return Result.failure(Exception("Failed to get manifest URLs: HTTP ${manifestResponse.code} - $error"))
            }

            val manifestApiJson = JSONObject(manifestResponse.body!!.string())
            val elements = manifestApiJson.getJSONArray("elements")

            if (elements.length() == 0) {
                return Result.failure(Exception("No manifest elements found for game"))
            }

            val element = elements.getJSONObject(0)
            val manifestHash = element.getString("hash")
            val manifests = element.getJSONArray("manifests")

            // Extract base URLs and manifest URLs
            val baseUrls = mutableListOf<String>()
            val manifestUrls = mutableListOf<String>()

            for (i in 0 until manifests.length()) {
                val manifest = manifests.getJSONObject(i)
                val uri = manifest.getString("uri")
                val baseUrl = uri.substringBeforeLast('/')

                if (baseUrl !in baseUrls) {
                    baseUrls.add(baseUrl)
                }

                // Add query params if present
                val queryParams = manifest.optJSONArray("queryParams")
                val fullUri = if (queryParams != null && queryParams.length() > 0) {
                    val params = (0 until queryParams.length()).joinToString("&") { j ->
                        val param = queryParams.getJSONObject(j)
                        "${param.getString("name")}=${param.getString("value")}"
                    }
                    "$uri?$params"
                } else {
                    uri
                }
                manifestUrls.add(fullUri)
            }

            Timber.tag("Epic").d("Found ${manifestUrls.size} manifest URLs, ${baseUrls.size} base URLs")

            // Step 4: Download manifest bytes from CDN
            var manifestBytes: ByteArray? = null
            for (url in manifestUrls) {
                try {
                    Timber.tag("Epic").d("Downloading manifest from: $url")
                    val downloadRequest = Request.Builder()
                        .url(url)
                        .build()

                    val downloadResponse = okHttpClient.newCall(downloadRequest).execute()
                    if (downloadResponse.isSuccessful) {
                        manifestBytes = downloadResponse.body?.bytes()
                        if (manifestBytes != null) {
                            // Verify SHA-1 hash
                            val digest = MessageDigest.getInstance("SHA-1")
                            val actualHash = digest.digest(manifestBytes).joinToString("") { "%02x".format(it) }

                            if (actualHash.equals(manifestHash, ignoreCase = true)) {
                                Timber.tag("Epic").d("Manifest downloaded and verified successfully")
                                break
                            } else {
                                Timber.tag("Epic").w("Manifest hash mismatch, trying next URL...")
                                manifestBytes = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("Epic").w(e, "Failed to download from $url, trying next...")
                }
            }

            if (manifestBytes == null) {
                return Result.failure(Exception("Failed to download manifest from any CDN URL"))
            }

            // Step 5: Parse manifest using Python (binary format is complex)
            // We still need Python for this part as the manifest format is proprietary
            val manifestParsed = parseManifestBytes(context, manifestBytes, baseUrls)
            if (manifestParsed.isFailure) {
                return Result.failure(manifestParsed.exceptionOrNull() ?: Exception("Failed to parse manifest"))
            }

            manifestParsed

        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to fetch manifest data")
            Result.failure(e)
        }
    }

    /**
     * Get game metadata (namespace and catalog ID) from library or API
     */
    private suspend fun getGameMetadata(context: Context, appName: String, accessToken: String): GameMetadata? {
        return try {
            // Try to fetch from library API and find the matching game
            val libraryUrl = "https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true"

            val request = Request.Builder()
                .url(libraryUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.tag("Epic").w("Failed to fetch library: ${response.code}")
                return null
            }

            val json = JSONObject(response.body!!.string())
            val records = json.getJSONArray("records")

            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                if (record.optString("appName") == appName) {
                    return GameMetadata(
                        appName = appName,
                        namespace = record.getString("namespace"),
                        catalogItemId = record.getString("catalogItemId"),
                        title = record.optString("productName", appName)
                    )
                }
            }

            Timber.tag("Epic").w("Game not found in library: $appName")
            null
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to get game metadata")
            null
        }
    }

    /**
     * Parse manifest bytes using Python (still required for complex binary format)
     */
    private suspend fun parseManifestBytes(context: Context, manifestBytes: ByteArray, baseUrls: List<String>): Result<ManifestData> {
        return try {
            // Save manifest to temporary file
            val manifestFile = File(context.cacheDir, "temp_manifest_${System.currentTimeMillis()}.manifest")
            manifestFile.outputStream().use { it.write(manifestBytes) }

            // Use EpicPythonBridge to parse the manifest
            val parseResult = EpicPythonBridge.parseManifestFile(context, manifestFile.absolutePath)

            // Clean up temp file
            manifestFile.delete()

            if (parseResult.isFailure) {
                return Result.failure(
                    parseResult.exceptionOrNull() ?: Exception("Failed to parse manifest")
                )
            }

            val jsonOutput = parseResult.getOrNull() ?: ""
            val json = JSONObject(jsonOutput)

            if (json.has("error")) {
                return Result.failure(Exception(json.getString("error")))
            }

            // Parse manifest data
            val chunks = json.getJSONArray("chunks").let { arr ->
                (0 until arr.length()).map { i ->
                    val chunk = arr.getJSONObject(i)
                    ChunkData(
                        guid = chunk.getString("guid"),
                        hash = chunk.optString("hash", ""),
                        shaHash = chunk.getString("sha_hash"),
                        size = chunk.getLong("size"),
                        windowSize = chunk.getLong("window_size"),
                        path = chunk.getString("path")
                    )
                }
            }

            val files = json.getJSONArray("files").let { arr ->
                (0 until arr.length()).map { i ->
                    val file = arr.getJSONObject(i)
                    val chunkParts = file.getJSONArray("chunk_parts").let { partsArr ->
                        (0 until partsArr.length()).map { j ->
                            val part = partsArr.getJSONObject(j)
                            ChunkPart(
                                guid = part.getString("guid"),
                                offset = part.getLong("offset"),
                                size = part.getLong("size")
                            )
                        }
                    }
                    FileManifest(
                        filename = file.getString("filename"),
                        fileSize = file.getLong("file_size"),
                        hash = file.optString("hash", ""),
                        chunkParts = chunkParts
                    )
                }
            }

            val totalSize = files.sumOf { it.fileSize }

            Result.success(ManifestData(baseUrls, chunks, files, totalSize))
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to parse manifest")
            Result.failure(e)
        }
    }

    private fun parseManifest(manifestData: ManifestData): ManifestData {
        // Already parsed in fetchManifestData
        return manifestData
    }

    /**
     * Download a single chunk from Epic CDN with decompression
     */
    private suspend fun downloadChunk(
        chunk: ChunkData,
        chunkDir: File,
        baseUrls: List<String>,
        downloadInfo: DownloadInfo
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val chunkFile = File(chunkDir, "${chunk.guid}.chunk")
            val decompressedFile = File(chunkDir, chunk.guid)

            // Skip if already downloaded and decompressed
            if (decompressedFile.exists() && decompressedFile.length() == chunk.windowSize) {
                Timber.tag("Epic").d("Chunk ${chunk.guid} already exists, skipping")
                downloadInfo.updateBytesDownloaded(chunk.size)
                return@withContext Result.success(decompressedFile)
            }

            // Try each base URL until one succeeds
            var lastException: Exception? = null
            for (baseUrl in baseUrls) {
                try {
                    val url = "$baseUrl/${chunk.path}"
                    Timber.tag("Epic").d("Downloading chunk from: $url")

                    val request = Request.Builder()
                        .url(url)
                        .build()

                    val response = okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        lastException = Exception("HTTP ${response.code} downloading chunk from $baseUrl")
                        continue
                    }

                    // Download Epic chunk file (contains header + potentially compressed data)
                    val chunkBytes = response.body?.bytes() ?: throw Exception("Empty response body")
                    downloadInfo.updateBytesDownloaded(chunkBytes.size.toLong())

                    // Parse Epic Chunk format and decompress if needed
                    val decompressedData = readEpicChunk(chunkBytes)

                    // Verify size matches expected
                    if (decompressedData.size.toLong() != chunk.windowSize) {
                        throw Exception("Decompressed size mismatch: expected ${chunk.windowSize}, got ${decompressedData.size}")
                    }

                    // Verify SHA hash
                    if (!verifyChunkHashBytes(decompressedData, chunk.shaHash)) {
                        throw Exception("Chunk hash verification failed for ${chunk.guid}")
                    }

                    // Write decompressed data
                    decompressedFile.outputStream().use { it.write(decompressedData) }

                    return@withContext Result.success(decompressedFile)
                } catch (e: Exception) {
                    Timber.tag("Epic").w(e, "Failed to download from $baseUrl, trying next...")
                    lastException = e
                }
            }

            // All URLs failed
            return@withContext Result.failure(lastException ?: Exception("All CDN URLs failed for chunk ${chunk.guid}"))
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to download chunk ${chunk.guid}")
            Result.failure(e)
        }
    }

    /**
     * Read and decompress an Epic Chunk file
     * Epic chunks have their own format with header + optional compression
     *
     * Format (from legendary/models/chunk.py):
     * - Magic: 0xB1FE3AA2 (4 bytes)
     * - Header version: 3 (4 bytes)
     * - Header size: 66 (4 bytes)
     * - Compressed size (4 bytes)
     * - GUID (16 bytes)
     * - Hash (8 bytes)
     * - Stored as flags (1 byte) - bit 0 = compressed
     * - SHA hash (20 bytes)
     * - Hash type (1 byte)
     * - Uncompressed size (4 bytes)
     * - Data (compressed_size bytes)
     */
    private fun readEpicChunk(chunkBytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Read header
        val magic = buffer.int
        if (magic != 0xB1FE3AA2.toInt()) {
            throw Exception("Invalid chunk magic: 0x${magic.toString(16)}")
        }

        val headerVersion = buffer.int
        val headerSize = buffer.int
        val compressedSize = buffer.int

        // Skip GUID (16 bytes), hash (8 bytes)
        buffer.position(buffer.position() + 24)

        // Read stored_as flag
        val storedAs = buffer.get().toInt() and 0xFF
        val isCompressed = (storedAs and 0x1) == 0x1

        // Skip SHA hash (20 bytes), hash type (1 byte), uncompressed size (4 bytes)
        buffer.position(buffer.position() + 25)

        // Read chunk data starting from header end
        val dataStart = headerSize
        val dataBytes = chunkBytes.copyOfRange(dataStart, dataStart + compressedSize)

        return if (isCompressed) {
            // Decompress using zlib
            val inflater = Inflater()
            try {
                inflater.setInput(dataBytes)
                val result = ByteArray(1024 * 1024) // Epic chunks are always 1 MiB uncompressed
                val resultLength = inflater.inflate(result)
                result.copyOf(resultLength)
            } finally {
                inflater.end()
            }
        } else {
            // Already uncompressed
            dataBytes
        }
    }

    /**
     * Verify chunk SHA-1 hash from byte array
     */
    private fun verifyChunkHashBytes(data: ByteArray, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(data)
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            val matches = actualHash.equals(expectedHash, ignoreCase = true)

            if (!matches) {
                Timber.tag("Epic").e("Hash mismatch: expected $expectedHash, got $actualHash")
            }

            matches
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Hash verification failed")
            false
        }
    }

    /**
     * Verify chunk SHA-1 hash from file
     */
    private fun verifyChunkHash(file: File, expectedHash: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            val matches = actualHash.equals(expectedHash, ignoreCase = true)

            if (!matches) {
                Timber.tag("Epic").e("Hash mismatch: expected $expectedHash, got $actualHash")
            }

            matches
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Hash verification failed")
            false
        }
    }

    /**
     * Assemble a file from its chunks
     */
    private suspend fun assembleFile(
        fileManifest: FileManifest,
        chunkDir: File,
        installDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(installDir, fileManifest.filename)
            outputFile.parentFile?.mkdirs()

            outputFile.outputStream().use { output ->
                for (chunkPart in fileManifest.chunkParts) {
                    val chunkFile = File(chunkDir, chunkPart.guid)

                    if (!chunkFile.exists()) {
                        return@withContext Result.failure(Exception("Chunk file missing: ${chunkPart.guid}"))
                    }

                    // Read chunk data at specified offset
                    chunkFile.inputStream().use { input ->
                        input.skip(chunkPart.offset)

                        val buffer = ByteArray(8192)
                        var remaining = chunkPart.size

                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val bytesRead = input.read(buffer, 0, toRead)

                            if (bytesRead == -1) break

                            output.write(buffer, 0, bytesRead)
                            remaining -= bytesRead
                        }
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to assemble file ${fileManifest.filename}")
            Result.failure(e)
        }
    }

    /**
     * Log the directory structure of the installed game
     */
    private fun logDirectoryStructure(dir: File, prefix: String = "", isRoot: Boolean = true) {
        if (!dir.exists()) {
            Timber.tag("Epic").w("Directory does not exist: ${dir.absolutePath}")
            return
        }

        if (isRoot) {
            Timber.tag("Epic").i("=== Installation Directory Structure ===")
            Timber.tag("Epic").i("Root: ${dir.absolutePath}")
        }

        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()

        files.forEachIndexed { index, file ->
            val isLast = index == files.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val fileInfo = if (file.isDirectory) {
                "${file.name}/"
            } else {
                val size = formatFileSize(file.length())
                "${file.name} ($size)"
            }

            Timber.tag("Epic").i("$prefix$connector$fileInfo")

            // Recursively log subdirectories
            if (file.isDirectory) {
                val newPrefix = prefix + if (isLast) "    " else "│   "
                logDirectoryStructure(file, newPrefix, isRoot = false)
            }
        }

        if (isRoot) {
            val totalSize = calculateTotalSize(dir)
            val fileCount = countFiles(dir)
            Timber.tag("Epic").i("=== Summary ===")
            Timber.tag("Epic").i("Total files: $fileCount")
            Timber.tag("Epic").i("Total size: ${formatFileSize(totalSize)}")
            Timber.tag("Epic").i("==================")
        }
    }

    /**
     * Format file size in human-readable format
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Calculate total size of a directory recursively
     */
    private fun calculateTotalSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { calculateTotalSize(it) } ?: 0
    }

    /**
     * Count total number of files in a directory recursively
     */
    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        if (dir.isFile) return 1
        return dir.listFiles()?.sumOf { countFiles(it) } ?: 0
    }

    // Data classes for manifest representation
    data class ManifestData(
        val baseUrls: List<String>,
        val chunks: List<ChunkData>,
        val files: List<FileManifest>,
        val totalSize: Long
    )

    data class ChunkData(
        val guid: String,
        val hash: String,
        val shaHash: String,
        val size: Long,
        val windowSize: Long,
        val path: String
    )

    data class FileManifest(
        val filename: String,
        val fileSize: Long,
        val hash: String,
        val chunkParts: List<ChunkPart>
    )

    data class ChunkPart(
        val guid: String,
        val offset: Long,
        val size: Long
    )

    /**
     * Game metadata for manifest fetching
     */
    private data class GameMetadata(
        val appName: String,
        val namespace: String,
        val catalogItemId: String,
        val title: String
    )
}
