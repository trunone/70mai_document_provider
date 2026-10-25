package com.mai70.dashcam.net

import android.util.Log
import com.github.trunone.mai70protocol.Mai70Protocol
import com.github.trunone.mai70protocol.models.Authority
import com.github.trunone.mai70protocol.models.Category
import com.github.trunone.mai70protocol.models.FileEntry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Represents a file or directory entry on the 70mai Dashcam HTTP server.
 *
 * @property name File or directory name.
 * @property path Full path relative to root or parent (e.g. "/sd/normal/2023_1001_120000.MP4").
 * @property isDirectory True if this item is a folder.
 * @property size Byte size of the file; 0 for directories or unknown size.
 * @property mimeType Media MIME type derived from extension (e.g., "video/mp4", "image/jpeg").
 * @property lastModified Timestamp of modification in milliseconds if available.
 */
data class DashcamFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val mimeType: String = if (isDirectory) "vnd.android.document/directory" else "application/octet-stream",
    val lastModified: Long = System.currentTimeMillis()
)

/**
 * Client for interacting with 70mai Dashcam using reverse-engineered Wi-Fi/HTTP CGI protocol.
 */
class DashcamApiClient(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    private val okHttpClient: OkHttpClient = defaultClient(),
    var token: String = ""
) {
    private val fileEntriesCache = ConcurrentHashMap<String, FileEntry>()

    fun getAuthority(): Authority = Authority(host = host, port = port, token = token)

    /**
     * Executes client registration (`client.cgi`) with the dashcam.
     */
    fun register(clientIp: String = "192.168.0.2"): Boolean {
        val authority = getAuthority()
        val timestamp = System.currentTimeMillis() / 1000L
        val queryPath = "client.cgi?"
        val queryParams = "&-operation=register&-ip=$clientIp&-timestamp=$timestamp"

        val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)
        val request = Request.Builder().url(url).get().build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string()
                Mai70Protocol.isSuccessResponse(bodyText)
            }
        } catch (e: Exception) {
            Log.w(TAG, "register failed", e)
            false
        }
    }

    /**
     * Sets access album mode (`setaccessalbum.cgi`).
     * Pauses camera recording and enables file listing / access on supported models.
     */
    fun setAlbumMode(enable: Boolean): Boolean {
        val authority = getAuthority()
        val timestamp = System.currentTimeMillis() / 1000L
        val enableValue = if (enable) 0 else 1

        val queryPath = "setaccessalbum.cgi?"
        val queryParamsCandidates = listOf(
            "&-enable=$enableValue&-timestamp=$timestamp",
            "-enable=$enableValue&-timestamp=$timestamp"
        )

        for (queryParams in queryParamsCandidates) {
            val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)
            val request = Request.Builder().url(url).get().build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string()
                    if (Mai70Protocol.isSuccessResponse(bodyText)) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "setAlbumMode request failed for candidate $queryParams", e)
            }
        }
        return false
    }

    /**
     * Prepares camera access before listing or fetching files.
     */
    fun prepareCameraAccess() {
        try {
            register()
        } catch (_: Exception) {}
        try {
            setAlbumMode(enable = true)
        } catch (_: Exception) {}
    }

    /**
     * Lists files for a specific Category using `getfilelist.cgi`.
     */
    @Throws(IOException::class)
    fun listFiles(category: Category): List<FileEntry> {
        prepareCameraAccess()
        val authority = getAuthority()
        val allEntries = mutableListOf<FileEntry>()
        var startIndex = 0
        val pageSize = 100

        while (true) {
            val timestamp = System.currentTimeMillis() / 1000L
            val queryPath = "getfilelist.cgi?"
            val queryParams = "-start=$startIndex&-end=${startIndex + pageSize - 1}&-type=${category.type}&-timestamp=$timestamp"
            val url = Mai70Protocol.buildCgiUrl(authority, queryPath, queryParams)

            val request = Request.Builder().url(url).get().build()
            val responseText = try {
                okHttpClient.newCall(request).execute().use { it.body?.string() }
            } catch (e: Exception) {
                throw IOException("Network error while listing files for category ${category.categoryName}: ${e.message}", e)
            }

            var batch = Mai70Protocol.parseFileListResponse(responseText, category)

            // Fallback strategy if initial batch on page 0 is empty
            if ((batch == null || batch.isEmpty()) && startIndex == 0) {
                val fallbackFormats = listOf(0, 1, 2, 3)
                for (format in fallbackFormats) {
                    val altTimestamp = System.currentTimeMillis() / 1000L
                    val altUrl = when (format) {
                        0 -> {
                            val qp = "&-start=0&-end=${pageSize - 1}&-type=${category.type}&-timestamp=$altTimestamp"
                            Mai70Protocol.buildCgiUrl(authority, queryPath, qp)
                        }
                        1 -> {
                            val qp = "-start=0&-end=${pageSize - 1}&-timestamp=$altTimestamp"
                            Mai70Protocol.buildCgiUrl(authority, queryPath, qp)
                        }
                        2 -> {
                            val qp = "start=0&end=${pageSize - 1}&type=${category.type}&timestamp=$altTimestamp"
                            Mai70Protocol.buildCgiUrl(authority, queryPath, qp)
                        }
                        3 -> {
                            val qp = "-start=1&-end=$pageSize&-type=${category.type}&-timestamp=$altTimestamp"
                            Mai70Protocol.buildCgiUrl(authority, queryPath, qp)
                        }
                        else -> continue
                    }

                    val altRequest = Request.Builder().url(altUrl).get().build()
                    val altText = try {
                        okHttpClient.newCall(altRequest).execute().use { it.body?.string() }
                    } catch (_: Exception) { null }

                    val altBatch = Mai70Protocol.parseFileListResponse(altText, category)
                    if (!altBatch.isNullOrEmpty()) {
                        batch = altBatch
                        break
                    }
                }
            }

            if (batch.isNullOrEmpty()) {
                break
            }

            for (entry in batch) {
                val fullRemotePath = "/${category.categoryName}/${entry.name}"
                val cacheKey = "$host:$port/${fullRemotePath.removePrefix("/")}"
                fileEntriesCache[cacheKey] = entry
            }

            allEntries.addAll(batch)
            if (batch.size < pageSize) {
                break
            }

            startIndex += pageSize
        }

        return allEntries
    }

    /**
     * Lists files and subdirectories under the given directory path for SAF navigation.
     *
     * Directory paths correspond to root `/`, category folders like `/Normal`, `/Event`, etc.,
     * or lower case `/sd/normal`.
     */
    @Throws(IOException::class)
    fun listDirectory(directoryPath: String): List<DashcamFileItem> {
        val cleanPath = sanitizePath(directoryPath)

        // Root path: return Virtual root directories matching Category enum
        if (cleanPath == "/" || cleanPath.isEmpty()) {
            val categories = listOf(Category.NORMAL, Category.EVENT, Category.PARKING, Category.PHOTO, Category.TIME_LAPSE)
            return categories.map { cat ->
                DashcamFileItem(
                    name = cat.categoryName,
                    path = "/${cat.categoryName}",
                    isDirectory = true,
                    mimeType = "vnd.android.document/directory"
                )
            }
        }

        // Handle /sd or /sd/ directory requests if requested
        if (cleanPath == "/sd" || cleanPath == "/sd/") {
            val categories = listOf(Category.NORMAL, Category.EVENT, Category.PARKING, Category.PHOTO, Category.TIME_LAPSE)
            return categories.map { cat ->
                DashcamFileItem(
                    name = cat.categoryName,
                    path = "/sd/${cat.categoryName}",
                    isDirectory = true,
                    mimeType = "vnd.android.document/directory"
                )
            }
        }

        // Determine Category from directory path
        val folderName = cleanPath.substringAfterLast('/').ifEmpty { cleanPath.removePrefix("/") }
        val category = Category.fromName(folderName)

        if (category == Category.UNKNOWN) {
            return emptyList()
        }

        val entries = listFiles(category)
        return entries.map { entry ->
            val itemPath = if (cleanPath.endsWith("/")) "$cleanPath${entry.name}" else "$cleanPath/${entry.name}"
            DashcamFileItem(
                name = entry.name,
                path = itemPath,
                isDirectory = false,
                size = entry.size,
                mimeType = getMimeTypeForPath(entry.name)
            )
        }
    }

    /**
     * Generates candidate HTTP URLs for downloading file entry content.
     */
    fun getFileUrlCandidates(
        categoryName: String,
        fileName: String,
        remotePath: String? = null
    ): List<String> {
        val candidates = mutableListOf<String>()
        val cleanCategory = categoryName.removePrefix("/").removeSuffix("/")
        val cacheKey = "$host:$port/$cleanCategory/$fileName"
        val cachedEntry = fileEntriesCache[cacheKey]

        candidates.add("http://$host:$port/$cleanCategory/$fileName")
        if (cachedEntry != null) {
            val entryPath = cachedEntry.path.removePrefix("/").removeSuffix("/")
            candidates.add("http://$host:$port/$entryPath/${cachedEntry.name}")
            candidates.add("http://$host:$port/sd/$entryPath/${cachedEntry.name}")
        } else if (!remotePath.isNullOrEmpty()) {
            val cleanRemotePath = remotePath.removePrefix("/").removeSuffix("/")
            candidates.add("http://$host:$port/$cleanRemotePath/$fileName")
            candidates.add("http://$host:$port/sd/$cleanRemotePath/$fileName")
        }
        candidates.add("http://$host:$port/sd/$cleanCategory/$fileName")
        candidates.add("http://$host:$port/$fileName")

        return candidates.distinct()
    }

    /**
     * Builds candidate HTTP thumbnail URLs for a file.
     *
     * Generates signed CGI thumbnail endpoint URLs as well as direct static HTTP thumbnail file candidates.
     *
     * @param categoryName Folder/category name (e.g. "Normal").
     * @param fileName File name (e.g. "NO20240101-120000-000100.MP4").
     * @param remotePath Optional remote directory path as reported by camera.
     * @return List of distinct candidate URLs for retrieving the file's thumbnail.
     */
    fun getThumbnailUrlCandidates(
        categoryName: String,
        fileName: String,
        remotePath: String? = null
    ): List<String> {
        val candidates = mutableListOf<String>()
        val cleanCategory = categoryName.removePrefix("/").removeSuffix("/")
        val cacheKey = "$host:$port/$cleanCategory/$fileName"
        val cachedEntry = fileEntriesCache[cacheKey]

        val authority = getAuthority()
        val timestamp = System.currentTimeMillis() / 1000L

        val paths = mutableListOf<String>()
        if (cachedEntry != null) {
            paths.add(cachedEntry.path.removePrefix("/").removeSuffix("/"))
        }
        if (!remotePath.isNullOrEmpty()) {
            paths.add(remotePath.removePrefix("/").removeSuffix("/"))
        }
        paths.add(cleanCategory)
        paths.add("sd/$cleanCategory")
        paths.add("")

        val distinctPaths = paths.distinct()

        // 1. Signed CGI endpoints
        val cgiPaths = listOf("getthumbnail.cgi?", "getthumb.cgi?", "getfilethumb.cgi?")
        for (p in distinctPaths) {
            for (cgi in cgiPaths) {
                if (p.isNotEmpty()) {
                    candidates.add(Mai70Protocol.buildCgiUrl(authority, cgi, "-path=$p&-name=$fileName&-timestamp=$timestamp"))
                } else {
                    candidates.add(Mai70Protocol.buildCgiUrl(authority, cgi, "-name=$fileName&-timestamp=$timestamp"))
                }
            }
        }

        // 2. Direct static HTTP candidates
        val baseName = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
        val thmNameCandidates = listOf(
            "$baseName.THM",
            "$baseName.thm",
            "$baseName.JPG",
            "$baseName.jpg",
            "${baseName}_THUMB.JPG",
            "${baseName}_thm.jpg",
            fileName
        ).distinct()

        for (p in distinctPaths) {
            val prefix = if (p.isEmpty()) "" else "$p/"
            for (thmName in thmNameCandidates) {
                candidates.add("http://$host:$port/${prefix}THUMB/$thmName")
                candidates.add("http://$host:$port/${prefix}.THUMB/$thmName")
                candidates.add("http://$host:$port/$prefix$thmName")
            }
        }

        return candidates.distinct()
    }

    /**
     * Queries metadata for a specific file path on the dashcam.
     */
    @Throws(IOException::class)
    fun getFileMetadata(filePath: String): DashcamFileItem {
        val normalizedPath = sanitizePath(filePath)
        val fileName = normalizedPath.substringAfterLast('/')
        val parentPath = getParentPath(normalizedPath)

        val children = try {
            listDirectory(parentPath)
        } catch (_: Exception) { emptyList() }

        val found = children.firstOrNull { it.path == normalizedPath || it.name == fileName }
        if (found != null) {
            return found
        }

        // Fallback: try probing candidate HTTP HEAD requests
        val categoryName = parentPath.substringAfterLast('/')
        val candidates = getFileUrlCandidates(categoryName, fileName)
        for (url in candidates) {
            val request = Request.Builder().url(url).head().build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        return DashcamFileItem(
                            name = fileName,
                            path = normalizedPath,
                            isDirectory = false,
                            size = contentLength,
                            mimeType = getMimeTypeForPath(normalizedPath)
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return DashcamFileItem(
            name = fileName,
            path = normalizedPath,
            isDirectory = false,
            size = 0L,
            mimeType = getMimeTypeForPath(normalizedPath)
        )
    }

    /**
     * Reads a byte range from a file on the dashcam server for random access / seeking.
     */
    @Throws(IOException::class)
    fun openRangeStream(filePath: String, offset: Long, size: Int): Response {
        val normalizedPath = sanitizePath(filePath)
        val fileName = normalizedPath.substringAfterLast('/')
        val parentPath = getParentPath(normalizedPath)
        val categoryName = parentPath.substringAfterLast('/')

        val candidates = getFileUrlCandidates(categoryName, fileName)

        val rangeHeader = if (size > 0) {
            "bytes=$offset-${offset + size - 1}"
        } else {
            "bytes=$offset-"
        }

        var lastException: IOException? = null
        for (url in candidates) {
            val request = Request.Builder()
                .url(url)
                .header("Range", rangeHeader)
                .get()
                .build()

            try {
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful || response.code == 206) {
                    return response
                }
                response.close()
            } catch (e: IOException) {
                lastException = e
            }
        }

        throw lastException ?: IOException("HTTP range request failed for $filePath ($rangeHeader)")
    }

    /**
     * Direct input stream download for an entire file.
     */
    @Throws(IOException::class)
    fun openInputStream(filePath: String): Response {
        val normalizedPath = sanitizePath(filePath)
        val fileName = normalizedPath.substringAfterLast('/')
        val parentPath = getParentPath(normalizedPath)
        val categoryName = parentPath.substringAfterLast('/')

        val candidates = getFileUrlCandidates(categoryName, fileName)

        var lastException: IOException? = null
        for (url in candidates) {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            try {
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    return response
                }
                response.close()
            } catch (e: IOException) {
                lastException = e
            }
        }

        throw lastException ?: IOException("Failed to open input stream for $filePath")
    }

    /**
     * Direct input stream download for a file thumbnail.
     */
    @Throws(IOException::class)
    fun openThumbnailInputStream(filePath: String): Response {
        prepareCameraAccess()
        val normalizedPath = sanitizePath(filePath)
        val fileName = normalizedPath.substringAfterLast('/')
        val parentPath = getParentPath(normalizedPath)
        val categoryName = parentPath.substringAfterLast('/')

        val candidates = getThumbnailUrlCandidates(categoryName, fileName)

        var lastException: IOException? = null
        for (url in candidates) {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            try {
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body
                    if (body != null) {
                        val source = body.source()
                        source.request(512)
                        val buffer = source.buffer.clone()
                        val headerText = buffer.readUtf8()
                        if (headerText.trimStart().startsWith("{") &&
                            (headerText.contains("ResultCode") || headerText.contains("status") || headerText.contains("Result"))
                        ) {
                            response.close()
                            continue
                        }
                        return response
                    }
                }
                response.close()
            } catch (e: IOException) {
                lastException = e
            }
        }

        throw lastException ?: IOException("Failed to open thumbnail stream for $filePath")
    }

    companion object {
        private const val TAG = "DashcamApiClient"
        const val DEFAULT_HOST = "192.168.0.1"
        const val DEFAULT_PORT = 80
        const val DEFAULT_BASE_URL = "http://$DEFAULT_HOST:$DEFAULT_PORT"

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun getMimeTypeForPath(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "aac", "mp3" -> "audio/mpeg"
                "thm" -> "image/jpeg"
                else -> "application/octet-stream"
            }
        }

        fun sanitizePath(path: String): String {
            var p = path.trim()
            if (!p.startsWith("/")) p = "/$p"
            return p
        }

        fun getParentPath(path: String): String {
            val clean = sanitizePath(path).trimEnd('/')
            val idx = clean.lastIndexOf('/')
            return if (idx <= 0) "/" else clean.substring(0, idx)
        }
    }
}
