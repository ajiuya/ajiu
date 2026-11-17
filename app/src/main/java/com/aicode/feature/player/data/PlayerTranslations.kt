package com.aicode.feature.player.data

import android.content.Context
import com.aicode.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object PlayerTranslations {
    fun json(context: Context): String = JsonObject(strings.mapValues { JsonPrimitive(context.getString(it.value)) }).toString()
    private val strings = mapOf(
        "title" to R.string.player_title, "loading" to R.string.player_loading, "play" to R.string.player_play,
        "pause" to R.string.player_pause, "previous" to R.string.player_previous, "next" to R.string.player_next,
        "local" to R.string.player_page_local, "files" to R.string.player_page_files, "url" to R.string.player_page_url,
        "add" to R.string.player_page_add, "search" to R.string.player_page_search, "keyword" to R.string.player_page_keyword,
        "search_action" to R.string.player_page_search_action, "cancel" to R.string.player_page_cancel,
        "empty" to R.string.player_page_empty, "failed" to R.string.player_page_failed,
        "media_failed" to R.string.player_page_media_failed, "workspace_unavailable" to R.string.player_page_workspace_unavailable,
        "search_failed" to R.string.player_page_search_failed, "search_partial" to R.string.player_page_search_partial,
        "previous_page" to R.string.player_page_previous_page, "next_page" to R.string.player_page_next_page,
        "fullscreen" to R.string.player_page_fullscreen, "exit_fullscreen" to R.string.player_page_exit_fullscreen,
        "remove" to R.string.player_page_remove, "clear" to R.string.player_page_clear, "rate" to R.string.player_page_rate,
        "volume" to R.string.player_page_volume, "seek" to R.string.player_page_seek, "order" to R.string.player_page_order,
        "sequential" to R.string.player_page_sequential, "repeat" to R.string.player_page_repeat, "shuffle" to R.string.player_page_shuffle,
        "playlist" to R.string.player_page_playlist, "lines" to R.string.player_page_lines, "select_sources" to R.string.player_page_select_sources,
        "of" to R.string.player_page_of, "result" to R.string.player_page_result, "local_copy" to R.string.player_page_local_copy,
        "no_episodes" to R.string.player_page_no_episodes, "line" to R.string.player_page_line, "ready" to R.string.player_page_ready,
        "resume" to R.string.player_page_resume, "pip" to R.string.player_pip, "full_page" to R.string.player_full_page,
        "locale" to R.string.player_page_locale, "more" to R.string.player_menu, "retry" to R.string.player_retry,
        "playing" to R.string.player_playing, "paused" to R.string.player_paused,
        "api" to R.string.player_page_api, "back_results" to R.string.player_page_back_results,
        "catalog_hint" to R.string.player_page_catalog_hint, "choose_episode" to R.string.player_page_choose_episode,
        "choose_media" to R.string.player_page_choose_media, "close_search" to R.string.player_page_close_search,
        "drop_add" to R.string.player_page_drop_add, "drop_hint" to R.string.player_page_drop_hint,
        "episode_count" to R.string.player_page_episode_count, "episodes" to R.string.player_page_episodes,
        "examples" to R.string.player_page_examples, "example_hls" to R.string.player_page_example_hls,
        "example_hls_hd" to R.string.player_page_example_hls_hd, "example_mp4" to R.string.player_page_example_mp4,
        "example_animation" to R.string.player_page_example_animation, "handoff_failed" to R.string.player_page_handoff_failed,
        "info" to R.string.player_page_info, "library" to R.string.player_page_library,
        "library_loaded" to R.string.player_page_library_loaded, "loaded_count" to R.string.player_page_loaded_count,
        "mute" to R.string.player_page_mute, "page_number" to R.string.player_page_page_number,
        "page_select" to R.string.player_page_page_select, "rescan" to R.string.player_page_rescan,
        "root" to R.string.player_page_root, "search_empty" to R.string.player_page_search_empty,
        "search_ready" to R.string.player_page_search_ready, "sound_on" to R.string.player_page_sound_on,
        "spectrum" to R.string.player_page_spectrum, "synthetic" to R.string.player_page_synthetic,
        "spectrum_unavailable" to R.string.player_page_spectrum_unavailable, "storage_failed" to R.string.player_page_storage_failed,
        "tap_play" to R.string.player_page_tap_play, "url_hint" to R.string.player_page_url_hint,
        "audio" to R.string.player_page_audio, "video" to R.string.player_page_video,
        "reselect" to R.string.player_page_reselect, "source" to R.string.player_page_source,
        "updated" to R.string.player_page_updated, "year" to R.string.player_page_year,
        "area" to R.string.player_page_area, "director" to R.string.player_page_director, "cast" to R.string.player_page_cast
    )
}
