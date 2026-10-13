package com.musicboxd.api.catalog;

import java.util.UUID;

/** A Song opened by its own link; resolves even when the Song or its Album is hidden (AD-12). */
public record SongPageView(UUID id, int trackNumber, String title, Integer durationSeconds, boolean hidden,
		AlbumSummary album) {
}
