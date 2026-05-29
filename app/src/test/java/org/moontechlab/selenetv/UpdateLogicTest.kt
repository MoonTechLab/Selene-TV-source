package org.moontechlab.selenetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.GithubRelease
import org.moontechlab.selenetv.service.UpdateLogic

class UpdateLogicTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val sampleJson = """
        {
          "tag_name": "v1.2.2",
          "name": "v1.2.2",
          "body": "更新日志内容",
          "published_at": "2026-05-28T17:36:10Z",
          "assets": [
            {"name":"SeleneTV-v1.2.2-arm64-v8a.apk","browser_download_url":"https://example.com/a.apk","size":30870406,"content_type":"application/vnd.android.package-archive"},
            {"name":"SeleneTV-v1.2.2-armeabi-v7a.apk","browser_download_url":"https://example.com/b.apk","size":28551167,"content_type":"application/vnd.android.package-archive"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesReleaseJson() {
        val release = json.decodeFromString<GithubRelease>(sampleJson)
        assertEquals("v1.2.2", release.tagName)
        assertEquals("更新日志内容", release.body)
        assertEquals(2, release.assets.size)
        assertEquals("SeleneTV-v1.2.2-arm64-v8a.apk", release.assets[0].name)
        assertEquals("https://example.com/a.apk", release.assets[0].browserDownloadUrl)
        assertEquals(30870406L, release.assets[0].size)
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdateLogic.compareVersions("1.2.10", "1.2.2") > 0)
        assertTrue(UpdateLogic.compareVersions("v1.3.0", "1.2.9") > 0)
        assertTrue(UpdateLogic.compareVersions("1.2", "1.2.0") == 0)
        assertTrue(UpdateLogic.compareVersions("1.2.2", "1.2.3") < 0)
        assertEquals(0, UpdateLogic.compareVersions("bad", "1.2.2"))
    }
}
