package org.moontechlab.selenetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.moontechlab.selenetv.model.GithubRelease

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
}
