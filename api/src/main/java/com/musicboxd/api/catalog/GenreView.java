package com.musicboxd.api.catalog;

import java.util.UUID;

public record GenreView(UUID id, String name, boolean hidden) {
}
