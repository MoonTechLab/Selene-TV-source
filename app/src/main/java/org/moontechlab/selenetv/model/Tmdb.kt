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

/**
 * 解析后的 TMDB 媒体标识，供片头片尾取数用。
 * @param mediaType "movie" 或 "tv"
 * @param season 季号（从豆瓣原名后缀解析，无后缀=1）
 * @param imdbId 仅 TV 解析；供 introdb.app（仅吃 imdb）补充用
 */
@Serializable
data class TmdbMediaRef(
    val mediaType: String,
    val tmdbId: Long,
    val season: Int,
    val imdbId: String?,
)
