package com.mai70.dashcam.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import androidx.annotation.RequiresApi
import com.mai70.dashcam.net.DashcamApiClient
import com.mai70.dashcam.net.DashcamFileItem
import com.mai70.dashcam.net.DashcamNetworkManager
import okhttp3.Response
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Custom Android [DocumentsProvider] acting as a Storage Access Framework (SAF) proxy
 * to stream media files directly from a 70mai Dashcam local Wi-Fi hotspot.
 *
 * Supports seeking for high-bitrate video streaming (`rs` mode) using Android's
 * [StorageManager.openProxyFileDescriptor] when running on API 26 (Android 8.0) and above,
 * with a local chunk caching pipe fallback for older/alternative access modes.
 */
class Mai70Provider : DocumentsProvider() {

    private lateinit var networkManager: DashcamNetworkManager
    private lateinit var apiClient: DashcamApiClient
    private lateinit var handlerThread: HandlerThread
    private lateinit var proxyHandler: Handler

    override fun onCreate(): Boolean {
        Log.d(TAG, "Initializing Mai70Provider...")
        val ctx = context ?: return false
        networkManager = DashcamNetworkManager.getInstance(ctx)
        apiClient = DashcamApiClient()

        handlerThread = HandlerThread("70maiProxyHandler")
        handlerThread.start()
        proxyHandler = Handler(handlerThread.looper)

        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DOC_ID_ROOT)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "70mai Dashcam")
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, "70mai Dashcam Wi-Fi Storage")
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                    DocumentsContract.Root.FLAG_LOCAL_ONLY
        )
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_camera)

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        ensureNetworkBound()
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        if (documentId == DOC_ID_ROOT) {
            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DOC_ID_ROOT)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "70mai Dashcam")
            row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            return result
        }

        val relativePath = documentIdToPath(documentId)
        try {
            val item = apiClient.getFileMetadata(relativePath)
            appendDocumentRow(result, item)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query document: $documentId (path: $relativePath)", e)
            val fnf = FileNotFoundException("Failed to find 70mai document: $documentId")
            fnf.initCause(e)
            throw fnf
        }

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        ensureNetworkBound()
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val directoryPath = documentIdToPath(parentDocumentId)

        try {
            val items = apiClient.listDirectory(directoryPath)
            for (item in items) {
                appendDocumentRow(result, item)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query child documents for parent: $parentDocumentId (path: $directoryPath)", e)
            val fnf = FileNotFoundException("Unable to list directory $directoryPath from 70mai Dashcam")
            fnf.initCause(e)
            throw fnf
        }

        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        ensureNetworkBound()
        Log.d(TAG, "openDocument requested for docId=$documentId with mode=$mode")

        if (mode.contains("w") || mode.contains("rw")) {
            throw FileNotFoundException("70mai Dashcam storage is read-only.")
        }

        val relativePath = documentIdToPath(documentId)

        // Query file metadata to obtain file size
        val metadata = try {
            apiClient.getFileMetadata(relativePath)
        } catch (e: Exception) {
            throw FileNotFoundException("Could not retrieve file metadata for $relativePath: ${e.message}")
        }

        if (metadata.size <= 0) {
            Log.w(TAG, "File size for $relativePath reported as ${metadata.size}. Proxy streaming requires known file size.")
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            openSeekableProxyDescriptor(relativePath, metadata.size)
        } else {
            openLocalCacheDescriptor(relativePath)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openSeekableProxyDescriptor(filePath: String, fileSize: Long): ParcelFileDescriptor {
        val storageManager = context?.getSystemService(StorageManager::class.java)
            ?: throw FileNotFoundException("StorageManager service unavailable.")

        val callback = DashcamProxyCallback(filePath, fileSize, apiClient)

        return try {
            storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                callback,
                proxyHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to openProxyFileDescriptor for $filePath", e)
            throw FileNotFoundException("Failed to open proxy descriptor for $filePath: ${e.message}")
        }
    }

    /**
     * Fallback strategy for older Android versions using a temporary seekable cache file or pipe stream.
     */
    private fun openLocalCacheDescriptor(filePath: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Context missing")
        val cacheFile = File(ctx.cacheDir, "dashcam_stream_${System.currentTimeMillis()}.tmp")
        cacheFile.deleteOnExit()

        val pfd = ParcelFileDescriptor.open(
            cacheFile,
            ParcelFileDescriptor.MODE_READ_ONLY or ParcelFileDescriptor.MODE_CREATE
        )

        // Asynchronously pre-stream initial bytes into cacheFile
        proxyHandler.post {
            try {
                apiClient.openInputStream(filePath).use { response ->
                    response.body?.byteStream()?.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error caching file $filePath", e)
            }
        }

        return pfd
    }

    private fun ensureNetworkBound() {
        if (!networkManager.isBound()) {
            Log.d(TAG, "Network not currently bound to 70mai Wi-Fi. Attempting binding...")
            val bound = networkManager.bindToDashcamNetwork(timeoutMs = 5000)
            if (!bound) {
                Log.w(TAG, "Could not bind process to 70mai Wi-Fi hotspot. Continuing with default network.")
            }
        }
    }

    private fun appendDocumentRow(cursor: MatrixCursor, item: DashcamFileItem) {
        val row = cursor.newRow()
        val docId = pathToDocumentId(item.path)

        var flags = 0
        if (!item.isDirectory) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        }

        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, item.name)
        row.add(DocumentsContract.Document.COLUMN_SIZE, item.size)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, item.mimeType)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, item.lastModified)
    }

    companion object {
        private const val TAG = "Mai70Provider"

        const val AUTHORITY = "com.mai70.dashcam.documents"
        private const val ROOT_ID = "mai70_root"
        private const val DOC_ID_ROOT = "root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        fun pathToDocumentId(path: String): String {
            val clean = DashcamApiClient.sanitizePath(path)
            return if (clean == "/" || clean.isEmpty()) DOC_ID_ROOT else "doc:$clean"
        }

        fun documentIdToPath(docId: String): String {
            if (docId == DOC_ID_ROOT) return "/"
            return if (docId.startsWith("doc:")) docId.substring(4) else docId
        }
    }
}

/**
 * Thread-safe [ProxyFileDescriptorCallback] that intercepts read and seek calls from Android's SAF
 * and translates them directly into OkHttp Range HTTP requests (`Range: bytes=offset-end`) to the 70mai server.
 */
@RequiresApi(Build.VERSION_CODES.O)
private class DashcamProxyCallback(
    private val filePath: String,
    private val totalSize: Long,
    private val apiClient: DashcamApiClient
) : ProxyFileDescriptorCallback() {

    override fun onGetSize(): Long {
        Log.d(TAG, "ProxyCallback.onGetSize() returning $totalSize for $filePath")
        return totalSize
    }

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        if (offset >= totalSize && totalSize > 0) {
            return 0
        }

        val requestedSize = if (totalSize > 0) {
            ((totalSize - offset).coerceAtMost(size.toLong())).toInt()
        } else {
            size
        }

        if (requestedSize <= 0) {
            return 0
        }

        var response: Response? = null
        try {
            response = apiClient.openRangeStream(filePath, offset, requestedSize)
            val body = response.body
                ?: throw IOException("Empty response body from dashcam server for $filePath")

            val inputStream = body.byteStream()
            var bytesReadTotal = 0

            while (bytesReadTotal < requestedSize) {
                val read = inputStream.read(data, bytesReadTotal, requestedSize - bytesReadTotal)
                if (read == -1) break
                bytesReadTotal += read
            }

            return bytesReadTotal
        } catch (e: Exception) {
            Log.e(TAG, "Error in ProxyCallback.onRead(offset=$offset, size=$size) for $filePath", e)
            throw android.system.ErrnoException("onRead", android.system.OsConstants.EIO)
        } finally {
            try {
                response?.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    override fun onRelease() {
        Log.d(TAG, "ProxyCallback.onRelease() called for $filePath")
    }

    companion object {
        private const val TAG = "Mai70ProxyCallback"
    }
}
