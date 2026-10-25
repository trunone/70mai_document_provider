package com.mai70.dashcam.net

import com.github.trunone.mai70protocol.Mai70Protocol
import com.github.trunone.mai70protocol.models.Category
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
        val host = mockWebServer.hostName
        val port = mockWebServer.port
        apiClient = DashcamApiClient(host = host, port = port)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testProtocolParseFileListResponse() {
        val jsonResponse = """
            {
                "ResultCode": 0,
                "Result": [
                    {
                        "name": "NO20231001-120000.MP4",
                        "path": "Normal",
                        "size": "10485760"
                    },
                    {
                        "name": "NO20231001-120500.JPG",
                        "path": "Normal",
                        "size": "2097152"
                    }
                ]
            }
        """.trimIndent()

        val entries = Mai70Protocol.parseFileListResponse(jsonResponse, Category.NORMAL)

        assertNotNull(entries)
        assertEquals(2, entries!!.size)

        val video = entries.find { it.name == "NO20231001-120000.MP4" }
        assertNotNull(video)
        assertEquals("Normal", video!!.path)
        assertEquals(10485760L, video.size)
        assertEquals(Category.NORMAL, video.category)

        val photo = entries.find { it.name == "NO20231001-120500.JPG" }
        assertNotNull(photo)
        assertEquals(2097152L, photo!!.size)
    }

    @Test
    fun testListDirectoryProtocolCall() {
        val jsonResponse = """
            {
                "ResultCode": 0,
                "Result": [
                    {
                        "name": "NO20231001-120000.MP4",
                        "path": "Normal",
                        "size": "10485760"
                    }
                ]
            }
        """.trimIndent()

        // 1. client.cgi register call
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ResultCode":0}"""))
        // 2. setaccessalbum.cgi call
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ResultCode":0}"""))
        // 3. getfilelist.cgi call
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val items = apiClient.listDirectory("/Normal")
        assertEquals(1, items.size)
        assertEquals("NO20231001-120000.MP4", items[0].name)
        assertEquals("/Normal/NO20231001-120000.MP4", items[0].path)
        assertEquals("video/mp4", items[0].mimeType)
        assertFalse(items[0].isDirectory)

        val req1 = mockWebServer.takeRequest()
        assertTrue(req1.path!!.contains("client.cgi"))

        val req2 = mockWebServer.takeRequest()
        assertTrue(req2.path!!.contains("setaccessalbum.cgi"))

        val req3 = mockWebServer.takeRequest()
        assertTrue(req3.path!!.contains("getfilelist.cgi"))
        assertTrue(req3.path!!.contains("-type=0"))
    }

    @Test
    fun testOpenRangeStream() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/1000")
                .setBody("0123456789")
        )

        val response = apiClient.openRangeStream("/Normal/NO20231001-120000.MP4", 0, 10)
        assertEquals(206, response.code)

        val bodyString = response.body?.string()
        assertEquals("0123456789", bodyString)

        val request = mockWebServer.takeRequest()
        assertEquals("bytes=0-9", request.getHeader("Range"))
    }

    @Test
    fun testGetMimeTypeForPath() {
        assertEquals("video/mp4", DashcamApiClient.getMimeTypeForPath("/Normal/file.MP4"))
        assertEquals("image/jpeg", DashcamApiClient.getMimeTypeForPath("photo.JPG"))
        assertEquals("image/png", DashcamApiClient.getMimeTypeForPath("snapshot.png"))
        assertEquals("application/octet-stream", DashcamApiClient.getMimeTypeForPath("unknown.dat"))
    }

    @Test
    fun testGetThumbnailUrlCandidates() {
        val candidates = apiClient.getThumbnailUrlCandidates("Normal", "NO20231001-120000.MP4")
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { it.contains("getthumbnail.cgi") || it.contains("NO20231001-120000.THM") })
    }

    @Test
    fun testOpenThumbnailInputStream() {
        // 1. client.cgi register call
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ResultCode":0}"""))
        // 2. setaccessalbum.cgi call
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ResultCode":0}"""))
        // 3. thumbnail request call (first candidate returning 200 JPEG binary)
        val mockImageData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/jpeg")
                .setBody(okio.Buffer().write(mockImageData))
        )

        val response = apiClient.openThumbnailInputStream("/Normal/NO20231001-120000.MP4")
        assertNotNull(response)
        assertEquals(200, response.code)
        val readBytes = response.body?.byteStream()?.readBytes()
        assertNotNull(readBytes)
        assertTrue(readBytes!!.size >= 4)
        assertEquals(0xFF.toByte(), readBytes[0])
        assertEquals(0xD8.toByte(), readBytes[1])
    }
}
