package app.gamenative.service.epic

import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Native Kotlin parser for Epic Games binary and JSON manifest formats
 *
 * Replaces Python legendary manifest parser with pure Kotlin implementation.
 * Based on: https://github.com/derrod/legendary/blob/main/legendary/models/manifest.py
 *
 * Manifest Structure:
 * 1. Header (41 bytes) - Magic, sizes, hash, version
 * 2. Body (optionally zlib compressed):
 *    - ManifestMeta - Game metadata
 *    - ChunkDataList - Chunk information (GUIDs, hashes, sizes)
 *    - FileManifestList - File list with chunk parts
 *    - CustomFields - Optional key-value pairs
 */

/**
 * Main manifest container
 */
data class EpicManifest(
    val meta: ManifestMeta,
    val chunks: List<ChunkInfo>,
    val files: List<FileManifest>,
    val customFields: Map<String, String>
) {
    val totalSize: Long
        get() = files.sumOf { it.fileSize }

    companion object {
        private const val HEADER_MAGIC = 0x44BEC00C

        /**
         * Parse Epic manifest from binary or JSON data
         */
        fun parse(data: ByteArray): EpicManifest {
            // Check if this is a JSON manifest (starts with '{')
            if (data.isNotEmpty()) {
                val firstByte = data[0]
                val firstChar = firstByte.toInt().toChar()
                Timber.tag("Epic").e("Manifest first byte: 0x${String.format("%02x", firstByte)} = '$firstChar' (byte value: $firstByte)")
                Timber.tag("Epic").e("Manifest size: ${data.size} bytes")
                Timber.tag("Epic").e("First 20 bytes: ${data.take(20).joinToString(" ") { String.format("%02x", it) }}")

                if (firstByte == '{'.code.toByte()) {
                    Timber.tag("Epic").e("Detected JSON manifest format")
                    return parseJsonManifest(data)
                }
            }

            // Otherwise parse as binary manifest
            Timber.tag("Epic").e("Parsing as binary manifest (first byte was not '{')")
            val stream = ByteArrayInputStream(data)

            // Parse header
            val header = parseHeader(stream)
            Timber.d("Manifest header: version=${header.version}, compressed=${header.compressed}, " +
                    "uncompressed=${header.uncompressedSize} bytes")

            // Read body data
            val bodyData = if (header.compressed) {
                // Decompress with zlib
                decompressZlib(stream.readBytes(), header.uncompressedSize)
            } else {
                stream.readBytes()
            }

            // Verify SHA-1 hash
            val actualHash = MessageDigest.getInstance("SHA-1").digest(bodyData)
            if (!actualHash.contentEquals(header.shaHash)) {
                Timber.w("Manifest hash mismatch! This may cause issues.")
            }

            // Parse body
            val bodyStream = ByteArrayInputStream(bodyData)
            val meta = ManifestMeta.parse(bodyStream)
            val chunks = ChunkDataList.parse(bodyStream, meta.featureLevel)
            val files = FileManifestList.parse(bodyStream)
            val customFields = CustomFields.parse(bodyStream)

            Timber.i("Parsed manifest: ${files.size} files, ${chunks.size} chunks, ${meta.appName} v${meta.buildVersion}")

            return EpicManifest(meta, chunks, files, customFields)
        }

        private fun parseHeader(stream: InputStream): ManifestHeader {
            val magic = stream.readInt()
            if (magic != HEADER_MAGIC) {
                throw IllegalArgumentException("Invalid manifest magic: 0x${magic.toString(16)}")
            }

            val headerSize = stream.readInt()
            val uncompressedSize = stream.readInt()
            val compressedSize = stream.readInt()
            val shaHash = stream.readNBytes(20)
            val storedAs = stream.read()
            val version = stream.readInt()

            // Skip any remaining header bytes
            val bytesRead = 41
            if (bytesRead < headerSize) {
                stream.skip((headerSize - bytesRead).toLong())
            }

            return ManifestHeader(
                headerSize, uncompressedSize, compressedSize,
                shaHash, storedAs, version
            )
        }

        private fun decompressZlib(compressed: ByteArray, uncompressedSize: Int): ByteArray {
            val inflater = Inflater()
            inflater.setInput(compressed)
            val result = ByteArray(uncompressedSize)
            val resultLength = inflater.inflate(result)
            inflater.end()

            if (resultLength != uncompressedSize) {
                Timber.w("Decompressed size mismatch: expected=$uncompressedSize, actual=$resultLength")
            }

            return result
        }

        /**
         * Parse JSON manifest format (older Epic manifests)
         * Based on: https://github.com/derrod/legendary/blob/main/legendary/models/json_manifest.py
         */
        private fun parseJsonManifest(data: ByteArray): EpicManifest {
            try {
                Timber.tag("Epic").e("parseJsonManifest: Starting JSON parsing...")
                val json = JSONObject(String(data, Charsets.UTF_8))

                Timber.tag("Epic").e("parseJsonManifest: JSON object created, parsing metadata...")

                // Parse metadata
                val meta = parseJsonMeta(json)
            Timber.tag("Epic").e("parseJsonManifest: Metadata parsed: ${meta.appName}, featureLevel: ${meta.featureLevel}")

            // Parse chunks
            Timber.tag("Epic").e("parseJsonManifest: Parsing chunks...")
            val chunks = parseJsonChunks(json, meta.featureLevel)
                // Parse files
                Timber.tag("Epic").e("parseJsonManifest: Parsing files...")
                val files = parseJsonFiles(json)
                Timber.tag("Epic").e("parseJsonManifest: Files parsed: ${files.size}")

                // Custom fields
                val customFields = json.optJSONObject("CustomFields")?.let { cf ->
                    cf.keys().asSequence().associateWith { cf.getString(it) }
                } ?: emptyMap()

                Timber.tag("Epic").e("parseJsonManifest: About to return EpicManifest object")
                val result = EpicManifest(meta, chunks, files, customFields)
                Timber.tag("Epic").e("parseJsonManifest: EpicManifest created successfully, returning")
                return result
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "parseJsonManifest: Exception during JSON parsing!")
                throw e
            }
        }

        private fun parseJsonMeta(json: JSONObject): ManifestMeta {
            return ManifestMeta(
                dataVersion = 0,
                featureLevel = blobToNum(json.optString("ManifestFileVersion", "013000000000")),
                isFileData = json.optBoolean("bIsFileData", false),
                appId = blobToNum(json.optString("AppID", "000000000000")),
                appName = json.optString("AppNameString", ""),
                buildVersion = json.optString("BuildVersionString", ""),
                launchExe = json.optString("LaunchExeString", ""),
                launchCommand = json.optString("LaunchCommand", ""),
                prereqIds = json.optJSONArray("PrereqIds")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                prereqName = json.optString("PrereqName", ""),
                prereqPath = json.optString("PrereqPath", ""),
                prereqArgs = json.optString("PrereqArgs", "")
            )
        }

        private fun parseJsonChunks(json: JSONObject, manifestVersion: Int): List<ChunkInfo> {
            val chunkFilesizeList = json.getJSONObject("ChunkFilesizeList")
            val chunkHashList = json.getJSONObject("ChunkHashList")
            val chunkShaList = json.getJSONObject("ChunkShaList")
            val dataGroupList = json.getJSONObject("DataGroupList")

            val guids = chunkFilesizeList.keys().asSequence().toList()

            return guids.map { guidHex ->
                val guid = guidFromJson(guidHex)
                val fileSize = blobToNum(chunkFilesizeList.getString(guidHex)).toLong()
                val hash = blobToNum(chunkHashList.getString(guidHex)).toLong()
                val shaHash = chunkShaList.getString(guidHex).chunked(2)
                    .map { it.toInt(16).toByte() }.toByteArray()
                val groupNum = blobToNum(dataGroupList.getString(guidHex))

                ChunkInfo(
                    guid = guid,
                    hash = hash,
                    shaHash = shaHash,
                    groupNum = groupNum,
                    windowSize = 1024 * 1024, // JSON manifests always use 1 MiB chunks
                    fileSize = fileSize,
                    manifestVersion = manifestVersion // Pass manifest version for correct path
                )
            }
        }

        private fun parseJsonFiles(json: JSONObject): List<FileManifest> {
            val fileManifestList = json.getJSONArray("FileManifestList")

            return (0 until fileManifestList.length()).map { i ->
                val fm = fileManifestList.getJSONObject(i)

                val filename = fm.getString("Filename")
                val hashBlob = blobToNum(fm.getString("FileHash"))
                val hash = ByteArray(20) { idx ->
                    ((hashBlob shr (idx * 8)) and 0xFF).toByte()
                }

                var flags = 0
                if (fm.optBoolean("bIsReadOnly", false)) flags = flags or 0x1
                if (fm.optBoolean("bIsCompressed", false)) flags = flags or 0x2
                if (fm.optBoolean("bIsUnixExecutable", false)) flags = flags or 0x4

                val installTags = fm.optJSONArray("InstallTags")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()

                val chunkParts = fm.getJSONArray("FileChunkParts")
                var fileOffset = 0L
                val parts = (0 until chunkParts.length()).map { j ->
                    val cp = chunkParts.getJSONObject(j)
                    val guid = guidFromJson(cp.getString("Guid"))
                    val offset = blobToNum(cp.getString("Offset"))
                    val size = blobToNum(cp.getString("Size"))

                    val part = ChunkPart(guid, offset, size, fileOffset)
                    fileOffset += size
                    part
                }

                FileManifest(
                    filename = filename,
                    symlinkTarget = "",
                    hash = hash,
                    flags = flags,
                    installTags = installTags,
                    chunkParts = parts,
                    fileSize = fileOffset
                )
            }
        }

        /**
         * Convert Epic's blob number format to integer
         * Format: %03d for each byte concatenated to string
         * Little endian encoding
         */
        private fun blobToNum(blob: String): Int {
            var num = 0
            var shift = 0
            for (i in blob.indices step 3) {
                val byte = blob.substring(i, minOf(i + 3, blob.length)).toInt()
                num = num or (byte shl shift)
                shift += 8
            }
            return num
        }

        /**
         * Convert hex GUID string to IntArray
         * Format: 32 hex chars -> 4 x uint32 (big endian)
         */
        private fun guidFromJson(hexStr: String): IntArray {
            val bytes = hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return IntArray(4) { buffer.int }
        }
    }
}

/**
 * Manifest header (41+ bytes)
 */
private data class ManifestHeader(
    val headerSize: Int,
    val uncompressedSize: Int,
    val compressedSize: Int,
    val shaHash: ByteArray,
    val storedAs: Int,
    val version: Int
) {
    val compressed: Boolean
        get() = (storedAs and 0x1) != 0
}

/**
 * Manifest metadata - game information
 */
data class ManifestMeta(
    val dataVersion: Int,
    val featureLevel: Int,
    val isFileData: Boolean,
    val appId: Int,
    val appName: String,
    val buildVersion: String,
    val launchExe: String,
    val launchCommand: String,
    val prereqIds: List<String>,
    val prereqName: String,
    val prereqPath: String,
    val prereqArgs: String,
    val buildId: String = "",
    val uninstallActionPath: String = "",
    val uninstallActionArgs: String = ""
) {
    companion object {
        fun parse(stream: InputStream): ManifestMeta {
            val startPos = stream.available()
            val metaSize = stream.readInt()
            val dataVersion = stream.read()
            val featureLevel = stream.readInt()
            val isFileData = stream.read() == 1
            val appId = stream.readInt()
            val appName = stream.readFString()
            val buildVersion = stream.readFString()
            val launchExe = stream.readFString()
            val launchCommand = stream.readFString()

            // Prerequisite IDs (list)
            val prereqCount = stream.readInt()
            val prereqIds = (0 until prereqCount).map { stream.readFString() }

            val prereqName = stream.readFString()
            val prereqPath = stream.readFString()
            val prereqArgs = stream.readFString()

            // Optional fields based on data version
            val buildId = if (dataVersion >= 1) stream.readFString() else ""
            val uninstallPath = if (dataVersion >= 2) stream.readFString() else ""
            val uninstallArgs = if (dataVersion >= 2) stream.readFString() else ""

            // Skip any unread bytes in metadata
            val bytesRead = startPos - stream.available()
            if (bytesRead < metaSize) {
                stream.skip((metaSize - bytesRead).toLong())
            }

            return ManifestMeta(
                dataVersion, featureLevel, isFileData, appId, appName,
                buildVersion, launchExe, launchCommand, prereqIds,
                prereqName, prereqPath, prereqArgs, buildId,
                uninstallPath, uninstallArgs
            )
        }
    }
}

/**
 * Chunk information - describes a downloadable chunk
 */
data class ChunkInfo(
    val guid: IntArray, // 4 x uint32 (128-bit GUID)
    val hash: Long,     // 64-bit hash
    val shaHash: ByteArray, // 20-byte SHA-1
    val groupNum: Int,  // Group number (0-99)
    val windowSize: Int, // Uncompressed size
    val fileSize: Long,  // Compressed size (download size)
    val manifestVersion: Int = 18 // Manifest feature level for determining chunk directory
) {
    val guidString: String by lazy {
        guid.joinToString("-") { "%08x".format(it) }
    }

    val guidNum: Long by lazy {
        guid[3].toLong() +
        (guid[2].toLong() shl 32) +
        (guid[1].toLong() shl 64) +
        (guid[0].toLong() shl 96)
    }

    val path: String by lazy {
        // Determine chunk directory based on manifest version
        val chunkDir = when {
            manifestVersion >= 15 -> "ChunksV4"
            manifestVersion >= 6 -> "ChunksV3"
            manifestVersion >= 3 -> "ChunksV2"
            else -> "Chunks"
        }
        // ChunksVX/XX/YYYYYYYY_ZZZZZZZZZZZZZZZZ.chunk
        val groupStr = "%02d".format(groupNum)
        val hashStr = "%016X".format(hash)
        val guidStr = guidString.replace("-", "").uppercase()
        "$chunkDir/$groupStr/${guidStr}_$hashStr.chunk"
    }
}

/**
 * Chunk data list parser
 */
private object ChunkDataList {
    fun parse(stream: InputStream, manifestVersion: Int): List<ChunkInfo> {
        val startPos = stream.available()
        val size = stream.readInt()
        val version = stream.read()
        val count = stream.readInt()

        val chunks = (0 until count).map { ChunkInfo(IntArray(4), 0, ByteArray(20), 0, 0, 0) }.toMutableList()

        // Read arrays in order (optimized binary format)
        // GUIDs (16 bytes each)
        for (i in 0 until count) {
            chunks[i] = chunks[i].copy(guid = IntArray(4) { stream.readInt() })
        }

        // Hashes (8 bytes each)
        for (i in 0 until count) {
            chunks[i] = chunks[i].copy(hash = stream.readLong())
        }

        // SHA hashes (20 bytes each)
        for (i in 0 until count) {
            chunks[i] = chunks[i].copy(shaHash = stream.readNBytes(20))
        }

        // Group numbers (1 byte each)
        for (i in 0 until count) {
            chunks[i] = chunks[i].copy(groupNum = stream.read())
        }

        // Window sizes (4 bytes each)
        for (i in 0 until count) {
            chunks[i] = chunks[i].copy(windowSize = stream.readInt())
        }

        // File sizes (8 bytes each, signed)
        for (i in 0 until count) {
            chunks[i] = chunks[i].copy(fileSize = stream.readLong())
        }

        // Skip any unread bytes
        val bytesRead = startPos - stream.available()
        if (bytesRead < size) {
            stream.skip((size - bytesRead).toLong())
        }

        return chunks
    }
}

/**
 * File manifest - describes a file in the installation
 */
data class FileManifest(
    val filename: String,
    val symlinkTarget: String,
    val hash: ByteArray, // 20-byte SHA-1
    val flags: Int,
    val installTags: List<String>,
    val chunkParts: List<ChunkPart>,
    val fileSize: Long,
    val hashMd5: ByteArray? = null,
    val mimeType: String = "",
    val hashSha256: ByteArray? = null
) {
    val readOnly: Boolean get() = (flags and 0x1) != 0
    val compressed: Boolean get() = (flags and 0x2) != 0
    val executable: Boolean get() = (flags and 0x4) != 0
}

/**
 * Chunk part - describes a portion of a file from a chunk
 */
data class ChunkPart(
    val guid: IntArray, // 4 x uint32
    val offset: Int,    // Offset within chunk
    val size: Int,      // Size of this part
    val fileOffset: Long // Offset within file
) {
    val guidString: String by lazy {
        guid.joinToString("-") { "%08x".format(it) }
    }
}

/**
 * File manifest list parser
 */
private object FileManifestList {
    fun parse(stream: InputStream): List<FileManifest> {
        val startPos = stream.available()
        val size = stream.readInt()
        val version = stream.read()
        val count = stream.readInt()

        val files = mutableListOf<FileManifest>()
        val tempFiles = (0 until count).map {
            TempFileManifest(
                "", "", ByteArray(20), 0,
                mutableListOf(), mutableListOf(), 0L
            )
        }

        // Filenames
        for (i in 0 until count) {
            tempFiles[i].filename = stream.readFString()
        }

        // Symlink targets
        for (i in 0 until count) {
            tempFiles[i].symlinkTarget = stream.readFString()
        }

        // SHA-1 hashes
        for (i in 0 until count) {
            tempFiles[i].hash = stream.readNBytes(20)
        }

        // Flags
        for (i in 0 until count) {
            tempFiles[i].flags = stream.read()
        }

        // Install tags
        for (i in 0 until count) {
            val tagCount = stream.readInt()
            tempFiles[i].installTags.addAll((0 until tagCount).map { stream.readFString() })
        }

        // Chunk parts
        for (i in 0 until count) {
            val partCount = stream.readInt()
            var fileOffset = 0L

            for (j in 0 until partCount) {
                val partSize = stream.readInt() // Should be 28
                val guid = IntArray(4) { stream.readInt() }
                val offset = stream.readInt()
                val size = stream.readInt()

                tempFiles[i].chunkParts.add(
                    ChunkPart(guid, offset, size, fileOffset)
                )
                fileOffset += size
            }

            tempFiles[i].fileSize = fileOffset
        }

        // MD5 hashes (version >= 1)
        val md5Hashes = if (version >= 1) {
            (0 until count).map {
                val hasMd5 = stream.readInt()
                if (hasMd5 != 0) stream.readNBytes(16) else null
            }
        } else {
            List(count) { null }
        }

        // MIME types (version >= 1)
        val mimeTypes = if (version >= 1) {
            (0 until count).map { stream.readFString() }
        } else {
            List(count) { "" }
        }

        // SHA256 hashes (version >= 2)
        val sha256Hashes = if (version >= 2) {
            (0 until count).map { stream.readNBytes(32) }
        } else {
            List(count) { null }
        }

        // Build final file manifests
        for (i in 0 until count) {
            files.add(
                FileManifest(
                    tempFiles[i].filename,
                    tempFiles[i].symlinkTarget,
                    tempFiles[i].hash,
                    tempFiles[i].flags,
                    tempFiles[i].installTags,
                    tempFiles[i].chunkParts,
                    tempFiles[i].fileSize,
                    md5Hashes[i],
                    mimeTypes[i],
                    sha256Hashes[i]
                )
            )
        }

        // Skip any unread bytes
        val bytesRead = startPos - stream.available()
        if (bytesRead < size) {
            stream.skip((size - bytesRead).toLong())
        }

        return files
    }

    private data class TempFileManifest(
        var filename: String,
        var symlinkTarget: String,
        var hash: ByteArray,
        var flags: Int,
        val installTags: MutableList<String>,
        val chunkParts: MutableList<ChunkPart>,
        var fileSize: Long
    )
}

/**
 * Custom fields parser (key-value pairs)
 */
private object CustomFields {
    fun parse(stream: InputStream): Map<String, String> {
        if (stream.available() < 4) {
            return emptyMap()
        }

        val size = stream.readInt()
        if (size <= 4) {
            return emptyMap()
        }

        val count = stream.readInt()
        val fields = mutableMapOf<String, String>()

        for (i in 0 until count) {
            val key = stream.readFString()
            val value = stream.readFString()
            fields[key] = value
        }

        return fields
    }
}

// Extension functions for binary reading (little-endian)
private fun InputStream.readInt(): Int {
    val bytes = readNBytes(4)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun InputStream.readLong(): Long {
    val bytes = readNBytes(8)
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
}

/**
 * Read Epic's "FString" format:
 * - If length > 0: UTF-8 string (length includes null terminator)
 * - If length < 0: UTF-16LE string (absolute length includes null terminator)
 * - If length == 0: empty string
 */
private fun InputStream.readFString(): String {
    val length = readInt()

    return when {
        length == 0 -> ""
        length > 0 -> {
            // ASCII/UTF-8 string
            val bytes = readNBytes(length)
            String(bytes, 0, length - 1, Charsets.UTF_8) // Exclude null terminator
        }
        else -> {
            // UTF-16LE string (negative length)
            val absLength = -length
            val bytes = readNBytes(absLength * 2)
            String(bytes, 0, (absLength - 1) * 2, Charsets.UTF_16LE) // Exclude null terminator
        }
    }
}
