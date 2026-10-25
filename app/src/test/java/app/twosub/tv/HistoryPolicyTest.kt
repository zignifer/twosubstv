package app.twosub.tv

import org.junit.Assert.*
import org.junit.Test

class HistoryPolicyTest {
    private val series = "https://2sub.movie/series/example/"
    private fun card(url: String) = CardItem(url, url, "Example")

    @Test fun accountPositionReplacesLegacyTvPositionAndHonorsBrowserRewinds() {
        val url = "${series}#watch-s1e2"
        val merged = HistoryPolicy.mergeResume(
            listOf(ResumePoint(url, 900.0)),
            listOf(ResumePoint(url, 120.0, 1000))
        )
        assertEquals(120.0, merged.single().position, 0.0)
    }

    @Test fun newerTvSaveWinsAndLatestEpisodeIsChosenByTimestamp() {
        val first = "${series}#watch-s1e2"
        val second = "${series}#watch-s1e5"
        val merged = HistoryPolicy.mergeResume(
            listOf(ResumePoint(first, 15.0, 3000)),
            listOf(ResumePoint(first, 400.0, 1000), ResumePoint(second, 800.0, 2000))
        )
        assertEquals(15.0, merged.first().position, 0.0)
        assertEquals(first, HistoryPolicy.resumeUrl(series, merged.map { it.url }))
        assertEquals(second, HistoryPolicy.resumeUrl(second, merged.map { it.url }))
    }

    @Test fun continueShowsLatestWatchedEpisodeEvenWhenItIsAnEarlierEpisode() {
        val items = listOf(card("${series}#watch-s1e2"), card("https://2sub.movie/film/another/"), card("${series}#watch-s1e5"))
        val result = HistoryPolicy.latestPerTitle(items)
        assertEquals(2, result.size)
        assertEquals("${series}#watch-s1e2", result.first().url)
    }

    @Test fun openingSeriesResolvesLatestButExplicitEpisodeIsPreserved() {
        val recent = listOf("${series}#watch-s2e3", "${series}#watch-s1e2")
        assertEquals(recent.first(), HistoryPolicy.resumeUrl(series, recent))
        assertEquals(recent.last(), HistoryPolicy.resumeUrl(recent.last(), recent))
        assertEquals("https://2sub.movie/film/new/", HistoryPolicy.resumeUrl("https://2sub.movie/film/new/", recent))
    }

    @Test fun episodeContextSupportsMultipleDigitNumbers() {
        assertEquals(12 to 24, HistoryPolicy.episode("${series}#watch-s12e24"))
        assertNull(HistoryPolicy.episode(series))
    }

    @Test fun loadedSeriesGetsAnExplicitDefaultWithoutReplacingResumeOrFilmUrls() {
        val episodes = listOf(
            EpisodeItem("1", "First", "${series}#watch-s1e1", 1, 1),
            EpisodeItem("2", "Second", "${series}#watch-s1e2", 1, 2),
        )
        assertEquals(episodes.first().url, HistoryPolicy.episodePlaybackUrl(series, episodes))
        assertEquals(episodes.first().url, HistoryPolicy.episodePlaybackUrl("${series}#watch", episodes))
        val localResume = HistoryPolicy.resumeUrl(series, listOf(episodes.last().url))
        assertEquals(episodes.last().url, HistoryPolicy.episodePlaybackUrl(localResume, episodes))
        assertEquals(episodes.last().url, HistoryPolicy.episodePlaybackUrl(episodes.last().url, episodes))
        val film = "https://2sub.movie/film/example/"
        assertEquals(film, HistoryPolicy.episodePlaybackUrl(film, emptyList()))
        assertEquals(film, HistoryPolicy.episodePlaybackUrl(film, episodes))
    }
}
