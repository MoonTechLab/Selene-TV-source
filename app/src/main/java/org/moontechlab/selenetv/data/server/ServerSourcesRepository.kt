package org.moontechlab.selenetv.data.server

import org.moontechlab.selenetv.data.SourcesRepository
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource
import org.moontechlab.selenetv.service.ApiService

/**
 * 服务端模式的源仓库实现：直接委托 ApiService（保持 1:1 行为）。
 */
object ServerSourcesRepository : SourcesRepository {
    override fun getSearchResources(): List<SearchResource> = ApiService.getSearchResources()
    override fun getLiveSources(): List<LiveSource> = ApiService.getLiveSources()
}
