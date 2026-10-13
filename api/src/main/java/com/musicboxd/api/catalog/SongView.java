package com.musicboxd.api.catalog;

import java.util.UUID;

public record SongView(UUID id, int trackNumber, String title, Integer durationSeconds, boolean hidden) {
}
