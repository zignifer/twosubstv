package app.twosub.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Official Lucide stroke geometry, pinned and attributed in research/UX-ICONS.md. */
enum class TvIconName(@DrawableRes val resourceId: Int) {
    Search(R.drawable.ic_search_lucide),
    Refresh(R.drawable.ic_refresh_lucide),
    FilmSlate(R.drawable.ic_film_slate_lucide),
    CatalogGrid(R.drawable.ic_catalog_grid_lucide),
    AnimationSmile(R.drawable.ic_animation_smile_lucide),
    Television(R.drawable.ic_television_lucide),
    Sparkle(R.drawable.ic_sparkle_lucide),
    History(R.drawable.ic_history_lucide),
    Play(R.drawable.ic_play_lucide),
    Pause(R.drawable.ic_pause_lucide),
    Subtitles(R.drawable.ic_subtitles_lucide),
    Sliders(R.drawable.ic_sliders_lucide),
    Episodes(R.drawable.ic_episodes_lucide),
    ArrowLeft(R.drawable.ic_arrow_left_lucide),
    CaretDown(R.drawable.ic_caret_down_lucide),
    SignIn(R.drawable.ic_sign_in_lucide),
    SignOut(R.drawable.ic_sign_out_lucide),
    Filter(R.drawable.ic_filter_lucide),
    Sort(R.drawable.ic_sort_lucide),
    Check(R.drawable.ic_check_lucide),
    Close(R.drawable.ic_close_lucide),
}

/** Original rating marks use their own aspect ratios and colors, never the UI icon tint. */
@Composable
fun TvRatingBrandIcon(
    source: String,
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
) {
    val (resource, label, ratio) = when (source.lowercase()) {
        "kp", "kinopoisk" -> Triple(R.drawable.brand_kp, "Кинопоиск", 1f)
        "imdb" -> Triple(R.drawable.brand_imdb, "IMDb", 2f)
        "tmdb" -> Triple(R.drawable.brand_tmdb, "TMDB", 190.24f / 81.52f)
        else -> return
    }
    Image(
        painter = painterResource(resource),
        contentDescription = label,
        modifier = modifier.size(width = height * ratio, height = height),
    )
}

/** Pass null for a decorative icon beside a label; label standalone icon buttons. */
@Composable
fun TvIcon(
    icon: TvIconName,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Image(
        painter = painterResource(icon.resourceId),
        contentDescription = contentDescription,
        modifier = modifier.size(24.dp),
        colorFilter = ColorFilter.tint(tint),
    )
}
