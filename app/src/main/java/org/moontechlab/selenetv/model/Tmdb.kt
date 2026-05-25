package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * TMDB 剧照与标题 logo。
 *
 * 仅用于详情页 hero 渲染（backdrop 作背景剧照、logo 作标题艺术字）；
 * 简介/评分/类型等其余信息仍取自豆瓣，不被 TMDB 覆盖。
 *
 * 可序列化：用于写入磁盘缓存。两字段皆 null 时表示「已查询但无图」的命中（负缓存）。
 */
@Serializable
data class TmdbArt(
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
)
