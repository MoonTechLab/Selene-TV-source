package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource

object SubscriptionService {
    private const val TAG = "SubscriptionService"

    class SubscriptionException(message: String) : Exception(message)

    data class ParsedSubscription(
        val searchResources: List<SearchResource>,
        val liveSources: List<LiveSource>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 纯解析：Base58 解码后的 JSON 字符串 → ParsedSubscription。失败抛 SubscriptionException。 */
    fun parseSubscriptionJson(text: String): ParsedSubscription {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw SubscriptionException("订阅内容格式无效: ${e.message}")
        }

        val apiSiteMap = runCatching { root["api_site"]?.jsonObject }.getOrNull() ?: emptyMap()
        val livesMap = runCatching { root["lives"]?.jsonObject }.getOrNull() ?: emptyMap()

        val searchResources = apiSiteMap.entries.mapNotNull { (mapKey, value) ->
            try {
                val obj = value.jsonObject
                SearchResource(
                    key = obj["key"]?.jsonPrimitive?.contentOrNull ?: mapKey,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    api = obj["api"]?.jsonPrimitive?.contentOrNull ?: "",
                    detail = obj["detail"]?.jsonPrimitive?.contentOrNull,
                    from = obj["from"]?.jsonPrimitive?.contentOrNull,
                    disabled = false,
                )
            } catch (_: Exception) {
                null
            }
        }

        val liveSources = livesMap.entries.mapNotNull { (mapKey, value) ->
            try {
                val obj = value.jsonObject
                LiveSource(
                    key = obj["key"]?.jsonPrimitive?.contentOrNull ?: mapKey,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    ua = obj["ua"]?.jsonPrimitive?.contentOrNull ?: "",
                    epg = obj["epg"]?.jsonPrimitive?.contentOrNull ?: "",
                    from = obj["from"]?.jsonPrimitive?.contentOrNull ?: "",
                    disabled = false,
                )
            } catch (_: Exception) {
                null
            }
        }

        if (searchResources.isEmpty() && liveSources.isEmpty()) {
            throw SubscriptionException("订阅内容无可用源")
        }

        return ParsedSubscription(searchResources, liveSources)
    }
}
