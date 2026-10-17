package com.mai70.dashcam.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

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
 * Client for interacting with the 70mai Dashcam HTTP web server (default IP: 192.168.0.1).
 *
 * Handles directory listing, metadata extraction, and ranged HTTP byte streaming for SAF playback.
 */
class DashcamApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val okHttpClient: OkHttpClient = defaultClient()
) {

    /**
     * Lists files and subdirectories under the given directory path.
     *
     * Supports standard 70mai paths like "/", "/sd/", "/sd/normal/", "/sd/event/", "/sd/photo/".
     *
     * @param directoryPath Path to query, e.g., "/sd/normal/".
     * @return List of [DashcamFileItem] entries found in the directory.
     * @throws IOException If the network request fails or HTTP error response received.
     */
    @Throws(IOException::class)
    fun listDirectory(directoryPath: String): List<DashcamFileItem> {
        val normalizedPath = sanitizePath(directoryPath)
        val url = buildUrl(normalizedPath)

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch directory listing from $url: HTTP ${response.code}")
            }

            val bodyText = response.body?.string() ?: ""
            return parseHtmlDirectoryListing(normalizedPath, bodyText)
        }
    }

    /**
     * Queries metadata for a specific file path on the dashcam.
     *
     * Uses HEAD request when possible, falling back to directory parsing or default estimates.
     */
    @Throws(IOException::class)
    fun getFileMetadata(filePath: String): DashcamFileItem {
        val normalizedPath = sanitizePath(filePath)
        val fileName = normalizedPath.substringAfterLast('/')
        val url = buildUrl(normalizedPath)

        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    val mimeType = getMimeTypeForPath(normalizedPath)
                    return DashcamFileItem(
                        name = fileName,
                        path = normalizedPath,
                        isDirectory = false,
                        size = contentLength,
                        mimeType = mimeType
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request failed for $normalizedPath, falling back to parent list parse", e)
        }

        // Fallback: list parent directory and find exact item
        val parentPath = getParentPath(normalizedPath)
        val children = listDirectory(parentPath)
        return children.firstOrNull { it.path == normalizedPath || it.name == fileName }
            ?: DashcamFileItem(
                name = fileName,
                path = normalizedPath,
                isDirectory = false,
                size = 0L,
                mimeType = getMimeTypeForPath(normalizedPath)
            )
    }

    /**
     * Reads a byte range from a file on the dashcam server for random access / seeking.
     *
     * @param filePath File path relative to root.
     * @param offset Byte offset to start reading from.
     * @param size Number of bytes requested to read.
     * @return [Response] containing the HTTP response input stream. **Caller must close response!**
     */
    @Throws(IOException::class)
    fun openRangeStream(filePath: String, offset: Long, size: Int): Response {
        val normalizedPath = sanitizePath(filePath)
        val url = buildUrl(normalizedPath)

        val rangeHeader = if (size > 0) {
            "bytes=$offset-${offset + size - 1}"
        } else {
            "bytes=$offset-"
        }

        val request = Request.Builder()
            .url(url)
            .header("Range", rangeHeader)
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw IOException("HTTP range request failed for $url ($rangeHeader) with code ${response.code}")
        }
        return response
    }

    /**
     * Direct input stream download for an entire file.
     */
    @Throws(IOException::class)
    fun openInputStream(filePath: String): Response {
        val normalizedPath = sanitizePath(filePath)
        val url = buildUrl(normalizedPath)

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Failed to open input stream for $url: HTTP ${response.code}")
        }
        return response
    }

    /**
     * Parses HTML directory listings commonly generated by 70mai HTTP servers (lighttpd/busybox httpd/nginx format).
     * Also falls back to virtual directory structure for root and known subfolders if listing `/`.
     */
    internal fun parseHtmlDirectoryListing(parentPath: String, htmlContent: String): List<DashcamFileItem> {
        val items = mutableListOf<DashcamFileItem>()

        // 1. Regular expression matcher for standard HTML directory index links:
        // Match e.g. <a href="filename.MP4">filename.MP4</a> or <a href="/sd/normal/2023_0101.MP4">
        val hrefPattern = Pattern.compile(
            """<a\s+[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = hrefPattern.matcher(htmlContent)

        while (matcher.find()) {
            val rawHref = matcher.group(1)?.trim() ?: continue
            val linkText = matcher.group(2)?.replace("<[^>]*>".toRegex(), "")?.trim() ?: rawHref

            // Skip parent directory links and self links
            if (rawHref == "../" || rawHref == "./" || rawHref.startsWith("?") || linkText == "Parent Directory" || rawHref == "/") {
                continue
            }

            val isDir = rawHref.endsWith("/") || !linkText.contains(".")
            val cleanName = rawHref.trimEnd('/').substringAfterLast('/')

            if (cleanName.isEmpty()) continue

            val itemPath = if (parentPath.endsWith("/")) "$parentPath$cleanName" else "$parentPath/$cleanName"
            val mimeType = if (isDir) "vnd.android.document/directory" else getMimeTypeForPath(cleanName)

            // Try to extract file size if present in nearby HTML text (e.g., <td>1234567</td> or 20MB)
            val size = parseSizeFromHtmlRow(htmlContent, rawHref)

            items.add(
                DashcamFileItem(
                    name = cleanName,
                    path = itemPath,
                    isDirectory = isDir,
                    size = size,
                    mimeType = mimeType
                )
            )
        }

        // If root directory requested and HTML list is empty or doesn't expose default subfolders,
        // guarantee standard 70mai root folders exist so users can navigate to /sd/
        if (parentPath == "/" && items.none { it.name == "sd" }) {
            items.add(
                DashcamFileItem(
                    name = "sd",
                    path = "/sd",
                    isDirectory = true,
                    mimeType = "vnd.android.document/directory"
                )
            )
        }

        // Standard 70mai SD subfolders if user is in /sd
        if ((parentPath == "/sd" || parentPath == "/sd/") && items.isEmpty()) {
            listOf("normal", "event", "photo").forEach { folder ->
                items.add(
                    DashcamFileItem(
                        name = folder,
                        path = "/sd/$folder",
                        isDirectory = true,
                        mimeType = "vnd.android.document/directory"
                    )
                )
            }
        }

        return items.distinctBy { it.path }
    }

    private fun parseSizeFromHtmlRow(htmlContent: String, href: String): Long {
        try {
            val hrefIndex = htmlContent.indexOf(href)
            if (hrefIndex != -1) {
                val snippet = htmlContent.substring(hrefIndex, (hrefIndex + 300).coerceAtMost(htmlContent.length))
                // Match numbers after href that could represent byte size
                val sizeMatch = Pattern.compile("""\b(\d{4,12})\b""").matcher(snippet)
                if (sizeMatch.find()) {
                    return sizeMatch.group(1)?.toLongOrNull() ?: 0L
                }
            }
        } catch (e: Exception) {
            // Ignore parse errors, default size to 0
        }
        return 0L
    }

    private fun buildUrl(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$baseUrl$cleanPath"
    }

    companion object {
        private const val TAG = "DashcamApiClient"
        const val DEFAULT_BASE_URL = "http://192.168.0.1"

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
