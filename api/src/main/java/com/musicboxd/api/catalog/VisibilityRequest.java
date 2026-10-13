package com.musicboxd.api.catalog;

import jakarta.validation.constraints.NotNull;

/** Body of the hide/unhide toggles. Hiding never deletes (AD-12). */
public record VisibilityRequest(@NotNull Boolean hidden) {
}
