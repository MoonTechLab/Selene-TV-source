package org.moontechlab.selenetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.moontechlab.selenetv.model.GithubAsset
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

    private val assets = listOf(
        GithubAsset("SeleneTV-v1.2.2-arm64-v8a.apk", "https://example.com/a.apk", 1, ""),
        GithubAsset("SeleneTV-v1.2.2-armeabi-v7a.apk", "https://example.com/b.apk", 2, ""),
    )

    @Test
    fun selectsAssetByDeviceAbiPriority() {
        val r64 = UpdateLogic.selectAsset(listOf("arm64-v8a", "armeabi-v7a"), assets)
        assertEquals("arm64-v8a", r64?.first)
        assertEquals("https://example.com/a.apk", r64?.second?.browserDownloadUrl)

        val r32 = UpdateLogic.selectAsset(listOf("armeabi-v7a", "armeabi"), assets)
        assertEquals("armeabi-v7a", r32?.first)

        assertNull(UpdateLogic.selectAsset(listOf("x86_64", "x86"), assets))
        assertNull(UpdateLogic.selectAsset(listOf("arm64-v8a"), emptyList()))
    }

    private fun release(tag: String, assets: List<GithubAsset>) =
        GithubRelease(tagName = tag, name = tag, body = "log", assets = assets)

    @Test
    fun buildsUpdateInfoOnlyWhenNewerAndAssetPresent() {
        val abis = listOf("arm64-v8a", "armeabi-v7a")

        val info = UpdateLogic.buildUpdateInfo(release("v1.3.0", assets), "1.2.2", abis)
        assertNotNull(info)
        assertEquals("1.3.0", info?.version)
        assertEquals("arm64-v8a", info?.abi)
        assertEquals("https://example.com/a.apk", info?.apkUrl)

        assertNull(UpdateLogic.buildUpdateInfo(release("v1.2.2", assets), "1.2.2", abis))
        assertNull(UpdateLogic.buildUpdateInfo(release("v1.3.0", emptyList()), "1.2.2", abis))
    }
}
