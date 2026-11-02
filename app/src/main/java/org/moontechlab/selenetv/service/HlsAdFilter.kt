package org.moontechlab.selenetv.service

import android.net.Uri
import java.net.URI

/**
 * Conservative HLS media-playlist filter.
 *
 * Only explicit ad cues (CUE-OUT/CUE-IN) and user supplied URL rules can remove
 * media segments. Segment duration alone is never treated as an ad signal.
 */
object HlsAdFilter {
    data class Rules(
        val enabled: Boolean,
        val urlRegexRules: List<String> = emptyList(),
        val adDomains: List<String> = emptyList(),
    )

    fun filterPlaylist(text: String, playlistUrl: String, rules: Rules): String {
        if (!rules.enabled || !text.trimStart().startsWith("#EXTM3U")) return text

        val regexes = rules.urlRegexRules.mapNotNull { raw ->
            raw.trim().takeIf { it.isNotEmpty() }?.let {
                runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
            }
        }
        val domains = rules.adDomains.map { it.trim().lowercase().trimStart('.') }
            .filter { it.isNotEmpty() }

        val lines = text.lines()
        val output = mutableListOf<String>()
        var inCueAd = false
        var segmentStart = -1
        var sawExtInf = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("#EXT-X-CUE-OUT", ignoreCase = true)) {
                inCueAd = true
                segmentStart = -1
                sawExtInf = false
                continue
            }
            if (trimmed.startsWith("#EXT-X-CUE-IN", ignoreCase = true)) {
                inCueAd = false
                segmentStart = -1
                sawExtInf = false
                continue
            }

            if (trimmed.startsWith("#EXTINF", ignoreCase = true)) {
                segmentStart = output.size
                sawExtInf = true
                if (!inCueAd) output.add(line)
                continue
            }

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (!inCueAd) output.add(line)
                continue
            }

            // A URI after EXTINF is a media segment. Master-playlist variant URIs
            // are intentionally left untouched.
            if (sawExtInf) {
                val absolute = runCatching { URI(playlistUrl).resolve(trimmed).toString() }
                    .getOrDefault(trimmed)
                val removeByRule = matchesAdUrl(absolute, regexes, domains)
                if (!inCueAd && !removeByRule) output.add(line)
                if (removeByRule && segmentStart >= 0 && segmentStart < output.size) {
                    // Remove EXTINF and any segment-scoped tags already emitted.
                    output.subList(segmentStart, output.size).clear()
                }
                segmentStart = -1
                sawExtInf = false
            } else if (!inCueAd) {
                output.add(line)
            }
        }

        return output.joinToString("\n")
    }

    private fun matchesAdUrl(
        url: String,
        regexes: List<Regex>,
        domains: List<String>,
    ): Boolean {
        if (regexes.any { it.containsMatchIn(url) }) return true
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }
}
