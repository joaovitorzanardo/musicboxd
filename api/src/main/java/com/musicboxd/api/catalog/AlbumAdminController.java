package com.musicboxd.api.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Staff-only by route: SecurityConfig requires STAFF on /api/v1/admin/** (MBD-21). No delete, ever (AD-12). */
@RestController
@RequestMapping("/api/v1/admin/catalog/albums")
public class AlbumAdminController {

	/** Track numbers are the list order; the client never sends them. */
	public record CreateAlbumRequest(
			@NotBlank @Size(max = 200) String title,
			@NotBlank @Size(max = 200) String artist,
			// The upper bound (next year) needs the clock, so AlbumService checks it.
			@Min(1000) Integer releaseYear,
			@Size(max = 10) List<@NotNull UUID> genreIds,
			@NotNull @Size(min = 1, max = 200) List<@NotNull @Valid SongRequest> songs) {

		/** Validation sees stripped text and each genre once, in the order picked. */
		public CreateAlbumRequest {
			title = title == null ? null : title.strip();
			artist = artist == null ? null : artist.strip();
			genreIds = genreIds == null ? List.of() : genreIds.stream().distinct().toList();
		}
	}

	public record SongRequest(
			@NotBlank @Size(max = 200) String title,
			@Min(1) @Max(86400) Integer durationSeconds) {

		public SongRequest {
			title = title == null ? null : title.strip();
		}
	}

	private final AlbumService albums;

	AlbumAdminController(AlbumService albums) {
		this.albums = albums;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@ApiResponse(responseCode = "201", description = "Album created, visible, with its songs and genres")
	@ApiResponse(responseCode = "400", description = "Invalid title, artist, year, songs or genre list")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "422", description = "A genre does not exist or is hidden (type urn:musicboxd:problem:unknown-genre, property genreIds)")
	AlbumView create(@Valid @RequestBody CreateAlbumRequest request) {
		return albums.create(request);
	}

	@GetMapping("/{id}")
	@ApiResponse(responseCode = "200", description = "The album with every song, hidden ones included, for the editor")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No album with this id")
	AlbumView get(@PathVariable UUID id) {
		return albums.adminView(id);
	}
}
