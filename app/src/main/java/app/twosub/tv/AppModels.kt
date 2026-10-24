package app.twosub.tv

enum class TvScreen { CATALOG, DETAIL, PLAYER, LOGIN }
enum class CatalogCategory(val label: String) { ALL("Всё"), NEW("Новое"), FILMS("Фильмы"), SERIES("Сериалы"), CARTOONS("Мультфильмы"), CONTINUE("Продолжить") }
enum class SubtitleMode(val label: String) { BOTH("EN + РУ"), EN("Только EN"), RU("Только РУ"), OFF("Выключены") }

data class CardItem(
    val id: String,
    val url: String,
    val title: String,
    val originalTitle: String = "",
    val poster: String = "",
    val year: String = "",
    val kind: String = "",
    val progress: Float = 0f,
    val landscape: String = "",
    val addedLabel: String = "",
    val episodeLabel: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
)

data class RatingItem(val source: String, val label: String, val value: String, val iconUrl: String = "")

data class EpisodeItem(val id: String, val title: String, val url: String = "", val season: Int = 1, val number: Int = 1)

data class FilmDetail(
    val item: CardItem,
    val description: String = "",
    val meta: String = "",
    val episodes: List<EpisodeItem> = emptyList(),
    val resumeSeconds: Double = 0.0,
    val resumeUrl: String = "",
    val resumeSeason: Int = 0,
    val resumeEpisode: Int = 0,
    val resumeTitle: String = "",
    val ratings: List<RatingItem> = emptyList(),
)

data class PlaybackOption(val id: String, val label: String, val enabled: Boolean = true)

data class PlayerState(
    val ready: Boolean = false,
    val paused: Boolean = true,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val subtitleMode: SubtitleMode = SubtitleMode.BOTH,
    val subtitleScale: Float = 1f,
    val subtitleBackground: Boolean = true,
    val speed: Float = 1f,
    val controlsVisible: Boolean = true,
    val title: String = "",
    val error: String? = null,
    val englishAvailable: Boolean = true,
    val russianAvailable: Boolean = true,
    val videoQualityMode: String = "auto",
    val actualQuality: String = "",
    val qualityOptions: List<PlaybackOption> = emptyList(),
    val serverId: String = "auto",
    val serverOptions: List<PlaybackOption> = emptyList(),
    val buffering: Boolean = false,
    val bufferedSeconds: Double = 0.0,
    val playbackNotice: String = "",
)

data class TvAppState(
    val screen: TvScreen = TvScreen.CATALOG,
    val catalog: List<CardItem> = emptyList(),
    val detail: FilmDetail? = null,
    val category: CatalogCategory = CatalogCategory.ALL,
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val accountName: String = "",
    val loggedIn: Boolean = false,
    val player: PlayerState = PlayerState(),
    val selectedUrl: String = "",
    val hasMore: Boolean = false,
    val status: String = "",
    val loginCode: String = "",
    val loginUrl: String = "",
    val loginReady: Boolean = false,
    val authKnown: Boolean = false,
    val genre: String = "",
    val order: String = "site_popularity",
    val genreOptions: List<PlaybackOption> = emptyList(),
    val orderOptions: List<PlaybackOption> = emptyList(),
    val total: Int = 0,
)

sealed interface TvAction {
    data class Category(val value: CatalogCategory) : TvAction
    data class Search(val query: String) : TvAction
    data class Filters(val genre: String, val order: String) : TvAction
    data class Open(val item: CardItem) : TvAction
    data class Play(val fromStart: Boolean = false) : TvAction
    data class Episode(val item: EpisodeItem) : TvAction
    data object Back : TvAction
    data object ExitPlayer : TvAction
    data object Login : TvAction
    data object Logout : TvAction
    data object Refresh : TvAction
    data object LoadMore : TvAction
    data object Toggle : TvAction
    data class Seek(val seconds: Double) : TvAction
    data class Subtitles(val mode: SubtitleMode) : TvAction
    data class SubtitleScale(val scale: Float) : TvAction
    data class SubtitleBackground(val enabled: Boolean) : TvAction
    data class Speed(val value: Float) : TvAction
    data class Quality(val value: String) : TvAction
    data class Server(val value: String) : TvAction
    data object ShowControls : TvAction
    data object CloseControls : TvAction
    data object Interaction : TvAction
    data class PanelVisibility(val visible: Boolean) : TvAction
}
