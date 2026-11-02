package org.moontechlab.selenetv.service

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Intercepts HLS playlist responses for ExoPlayer while leaving media segments
 * and all non-HLS requests on the original DataSource unchanged.
 */
class HlsAdFilteringDataSource(
    private val upstream: DataSource,
    private val rules: HlsAdFilter.Rules,
) : DataSource {
    private var playlistBytes: ByteArrayInputStream? = null
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        val path = dataSpec.uri.path.orEmpty().lowercase()
        if (!rules.enabled || !path.substringBefore('?').endsWith(".m3u8")) {
            opened = false
            return length
        }

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = upstream.read(buffer, 0, buffer.size)
            if (count == androidx.media3.common.C.LENGTH_UNSET || count == -1) break
            if (count > 0) output.write(buffer, 0, count)
        }
        val original = output.toByteArray()
        val source = original.toString(Charsets.UTF_8)
        val filtered = HlsAdFilter.filterPlaylist(source, dataSpec.uri.toString(), rules)
        playlistBytes = ByteArrayInputStream(filtered.toByteArray(Charsets.UTF_8))
        opened = true
        return filtered.toByteArray(Charsets.UTF_8).size.toLong()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val manifest = playlistBytes
        return if (opened && manifest != null) {
            if (length == 0) 0 else manifest.read(buffer, offset, length).let {
                if (it == -1) androidx.media3.common.C.RESULT_END_OF_INPUT else it
            }
        } else {
            upstream.read(buffer, offset, length)
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        playlistBytes?.close()
        playlistBytes = null
        opened = false
        upstream.close()
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val rules = HlsAdFilter.Rules(
                enabled = AppDataService.isAdFilterEnabledSync(),
                urlRegexRules = AppDataService.getAdFilterRegexRulesSync(),
                adDomains = AppDataService.getAdFilterDomainsSync(),
            )
            return HlsAdFilteringDataSource(upstreamFactory.createDataSource(), rules)
        }
    }
}
