package com.mai70.dashcam.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DashcamApiClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: DashcamApiClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        val baseUrl = mockWebServer.url("/").toString().trimEnd('/')
        apiClient = DashcamApiClient(baseUrl = baseUrl)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testParseHtmlDirectoryListing() {
        val htmlContent = """
            <html>
            <head><title>Index of /sd/normal/</title></head>
            <body>
            <h1>Index of /sd/normal/</h1>
            <a href="../">Parent Directory</a>
            <a href="2023_1001_120000.MP4">2023_1001_120000.MP4</a> 10485760
            <a href="2023_1001_120500.JPG">2023_1001_120500.JPG</a> 2097152
            <a href="subfolder/">subfolder/</a>
            </body>
            </html>
        """.trimIndent()

        val items = apiClient.parseHtmlDirectoryListing("/sd/normal", htmlContent)

        assertEquals(3, items.size)

        val videoItem = items.find { it.name == "2023_1001_120000.MP4" }
        assertNotNull(videoItem)
        assertEquals("/sd/normal/2023_1001_120000.MP4", videoItem!!.path)
        assertEquals("video/mp4", videoItem.mimeType)
        assertFalse(videoItem.isDirectory)

        val imageItem = items.find { it.name == "2023_1001_120500.JPG" }
        assertNotNull(imageItem)
        assertEquals("image/jpeg", imageItem!!.mimeType)

        val dirItem = items.find { it.name == "subfolder" }
        assertNotNull(dirItem)
        assertTrue(dirItem!!.isDirectory)
        assertEquals("vnd.android.document/directory", dirItem.mimeType)
    }

    @Test
    fun testListDirectoryNetworkCall() {
        val htmlContent = """
            <a href="2023_1001_120000.MP4">2023_1001_120000.MP4</a>
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(htmlContent)
        )

        val items = apiClient.listDirectory("/sd/normal")
        assertEquals(1, items.size)
        assertEquals("2023_1001_120000.MP4", items[0].name)

        val request = mockWebServer.takeRequest()
        assertEquals("/sd/normal", request.path)
    }

    @Test
    fun testOpenRangeStream() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-99/1000")
                .setBody("0123456789")
        )

        val response = apiClient.openRangeStream("/sd/normal/test.mp4", 0, 10)
        assertEquals(206, response.code)

        val bodyString = response.body?.string()
        assertEquals("0123456789", bodyString)

        val request = mockWebServer.takeRequest()
        assertEquals("bytes=0-9", request.getHeader("Range"))
    }

    @Test
    fun testGetMimeTypeForPath() {
        assertEquals("video/mp4", DashcamApiClient.getMimeTypeForPath("/sd/normal/file.MP4"))
        assertEquals("image/jpeg", DashcamApiClient.getMimeTypeForPath("photo.JPG"))
        assertEquals("image/png", DashcamApiClient.getMimeTypeForPath("snapshot.png"))
        assertEquals("application/octet-stream", DashcamApiClient.getMimeTypeForPath("unknown.dat"))
    }
}
