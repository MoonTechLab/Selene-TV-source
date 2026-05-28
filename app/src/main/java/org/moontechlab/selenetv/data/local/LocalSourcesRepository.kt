package org.moontechlab.selenetv.data.local

import org.moontechlab.selenetv.data.SourcesRepository
import org.moontechlab.selenetv.model.LiveSource
import org.moontechlab.selenetv.model.SearchResource

object LocalSourcesRepository : SourcesRepository {
    override fun getSearchResources(): List<SearchResource> = LocalStore.getSearchSources()
    override fun getLiveSources(): List<LiveSource> = LocalStore.getLiveSources()
}
