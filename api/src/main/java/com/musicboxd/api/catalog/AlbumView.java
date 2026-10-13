package com.musicboxd.api.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Songs in track order; genres in the order Staff picked them. */
public record AlbumView(UUID id, String title, String artist, Integer releaseYear, boolean hidden,
		Instant createdAt, List<SongView> songs, List<GenreView> genres) {
}
