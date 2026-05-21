package org.moontechlab.selenetv.screen

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object LoginRoute

@Serializable
data class DetailRoute(
    val doubanId: String,
    val title: String,
    val poster: String,
    val year: String,
)
