package app.twosub.tv

/** History stays per episode; Continue presents only the most recently watched entry per title. */
internal object HistoryPolicy {
    fun titleKey(url: String): String = url.substringBefore('#').substringBefore('?').trimEnd('/')

    fun latestPerTitle(items: List<CardItem>): List<CardItem> = items.distinctBy { titleKey(it.url) }

    fun resumeUrl(requested: String, recentUrls: List<String>): String {
        if (episode(requested) != null) return requested
        return recentUrls.firstOrNull { titleKey(it) == titleKey(requested) } ?: requested
    }

    fun episodePlaybackUrl(requested: String, episodes: List<EpisodeItem>): String {
        if (episode(requested) != null) return requested
        return episodes.firstOrNull {
            it.season > 0 && it.number > 0 && titleKey(it.url) == titleKey(requested) &&
                episode(it.url) == (it.season to it.number)
        }?.url ?: requested
    }

    fun episode(url: String): Pair<Int, Int>? = Regex("#watch-s(\\d+)e(\\d+)$").find(url)?.let {
        it.groupValues[1].toIntOrNull()?.let { season ->
            it.groupValues[2].toIntOrNull()?.let { episode -> season to episode }
        }
    }
}
