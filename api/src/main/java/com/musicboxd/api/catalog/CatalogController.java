package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Public reads: SecurityConfig lets any GET outside /api/v1/admin through without a token (AD-7).
 * Hidden items still resolve by id; only search leaves them out (AD-12, MBD-34).
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

	private final AlbumService albums;

	CatalogController(AlbumService albums) {
		this.albums = albums;
	}

	@GetMapping("/albums/{id}")
	@ApiResponse(responseCode = "200", description = "The album with its visible songs in track order; a hidden album still resolves")
	@ApiResponse(responseCode = "404", description = "No album with this id")
	AlbumView album(@PathVariable UUID id) {
		return albums.publicView(id);
	}

	@GetMapping("/songs/{id}")
	@ApiResponse(responseCode = "200", description = "The song and its album; resolves even when either is hidden")
	@ApiResponse(responseCode = "404", description = "No song with this id")
	SongPageView song(@PathVariable UUID id) {
		return albums.songPage(id);
	}
}
