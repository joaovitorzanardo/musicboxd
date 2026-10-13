package com.musicboxd.api.catalog;

import java.util.UUID;

/** The Album a Song page links back to. */
public record AlbumSummary(UUID id, String title, String artist, Integer releaseYear, boolean hidden) {
}
