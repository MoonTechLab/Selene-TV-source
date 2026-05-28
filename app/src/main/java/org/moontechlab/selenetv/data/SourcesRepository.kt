package org.moontechlab.selenetv.data

import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource

/**
 * 源仓库：搜索源 / 直播源。
 */
interface SourcesRepository {
    fun getSearchResources(): List<SearchResource>
    fun getLiveSources(): List<LiveSource>
}
