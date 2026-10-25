package com.mai70.dashcam.provider

import android.Manifest
import android.content.Context
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class Mai70ProviderTest {

    private lateinit var context: Context
    private lateinit var provider: Mai70Provider

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        context = app
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.MANAGE_DOCUMENTS)

        val info = ProviderInfo().apply {
            authority = Mai70Provider.AUTHORITY
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
            exported = true
            grantUriPermissions = true
        }

        provider = Robolectric.buildContentProvider(Mai70Provider::class.java).create(info).get()
    }

    @Test
    fun testPathToDocumentIdAndBack() {
        val rootDocId = Mai70Provider.pathToDocumentId("/")
        assertEquals("root", rootDocId)
        assertEquals("/", Mai70Provider.documentIdToPath(rootDocId))

        val docId = Mai70Provider.pathToDocumentId("/sd/normal/video.mp4")
        assertEquals("doc:/sd/normal/video.mp4", docId)
        assertEquals("/sd/normal/video.mp4", Mai70Provider.documentIdToPath(docId))
    }

    @Test
    fun testQueryRoots() {
        val cursor = provider.queryRoots(null)

        assertNotNull(cursor)
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())

        val rootId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID))
        val title = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_TITLE))

        assertEquals("mai70_root", rootId)
        assertEquals("70mai Dashcam", title)
    }

    @Test
    fun testIsChildDocument() {
        assertTrue(provider.isChildDocument("root", "doc:/Normal"))
        assertTrue(provider.isChildDocument("root", "doc:/Normal/video.mp4"))
        assertTrue(provider.isChildDocument("doc:/Normal", "doc:/Normal/video.mp4"))
        assertFalse(provider.isChildDocument("doc:/Normal", "doc:/Event/video.mp4"))
        assertFalse(provider.isChildDocument("doc:/Normal", "doc:/Normal"))
    }

    @Test
    fun testFindDocumentPath() {
        val path = provider.findDocumentPath("root", "doc:/Normal/2023_0101.MP4")
        assertNotNull(path)
        assertEquals("mai70_root", path.rootId)

        val pathList = path.path
        assertEquals(3, pathList.size)
        assertEquals("root", pathList[0])
        assertEquals("doc:/Normal", pathList[1])
        assertEquals("doc:/Normal/2023_0101.MP4", pathList[2])
    }

    @Test
    fun testQueryDocumentRoot() {
        val cursor = provider.queryDocument("root", null)

        assertNotNull(cursor)
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())

        val docId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        val displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))

        assertEquals("root", docId)
        assertEquals("70mai Dashcam", displayName)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, mimeType)
    }

    @Test
    fun testOpenDocumentThumbnailWhenCached() {
        val safeFileName = "_Normal_NO20231001-120000.MP4"
        val cacheDir = java.io.File(context.cacheDir, "dashcam_thumbnails")
        cacheDir.mkdirs()
        val thumbFile = java.io.File(cacheDir, "thumb_$safeFileName.jpg")
        thumbFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))

        val afd = provider.openDocumentThumbnail("doc:/Normal/NO20231001-120000.MP4", null, null)
        assertNotNull(afd)
        assertNotNull(afd.parcelFileDescriptor)
        assertTrue(afd.length == -1L || afd.length > 0)
    }
}
