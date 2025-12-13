package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * 搜索资源模型
 */
@Serializable
data class SearchResource(
    val key: String,
    val name: String,
    val api: String,
    val detail: String? = null,
    val from: String? = null,
    val disabled: Boolean = false
)
