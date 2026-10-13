package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

/**
 * Staff-only by route (MBD-21). Songs are created and edited through their Album's tracklist
 * (PUT /api/v1/admin/catalog/albums/{id}); this is only the visibility toggle. No delete, ever (AD-12).
 */
@RestController
@RequestMapping("/api/v1/admin/catalog/songs")
public class SongAdminController {

	private final AlbumService albums;

	SongAdminController(AlbumService albums) {
		this.albums = albums;
	}

	@PutMapping("/{id}/hidden")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@ApiResponse(responseCode = "204", description = "Visibility set; a hidden song leaves search and its album page but still opens by id")
	@ApiResponse(responseCode = "400", description = "hidden missing")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No song with this id")
	void setHidden(@PathVariable UUID id, @Valid @RequestBody VisibilityRequest request) {
		albums.setSongHidden(id, request.hidden());
	}
}
