package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Staff-only by route: SecurityConfig requires STAFF on /api/v1/admin/** (MBD-21). No delete, ever (AD-12). */
@RestController
@RequestMapping("/api/v1/admin/catalog/genres")
public class GenreAdminController {

	public record CreateGenreRequest(@NotBlank @Size(max = 50) String name) {

		/** Validation sees the stripped name, matching what is stored. */
		public CreateGenreRequest {
			name = name == null ? null : name.strip();
		}
	}

	/** Each field is optional; an absent one is left as it is. Validation sees the stripped name. */
	public record EditGenreRequest(@Size(min = 1, max = 50) String name, Boolean hidden) {

		public EditGenreRequest {
			name = name == null ? null : name.strip();
		}
	}

	private final GenreService genres;

	GenreAdminController(GenreService genres) {
		this.genres = genres;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@ApiResponse(responseCode = "201", description = "Genre created, visible")
	@ApiResponse(responseCode = "400", description = "Blank name or longer than 50 characters")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "409", description = "A genre with this name exists, ignoring case (type urn:musicboxd:problem:genre-taken)")
	GenreView create(@Valid @RequestBody CreateGenreRequest request) {
		return genres.create(request.name());
	}

	@PatchMapping("/{id}")
	@ApiResponse(responseCode = "200", description = "Genre renamed, hidden or shown again; absent fields are unchanged")
	@ApiResponse(responseCode = "400", description = "Blank name, longer than 50 characters, malformed id, or neither name nor hidden sent")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No genre with this id")
	@ApiResponse(responseCode = "409", description = "Another genre has this name, ignoring case (type urn:musicboxd:problem:genre-taken)")
	GenreView edit(@PathVariable UUID id, @Valid @RequestBody EditGenreRequest request) {
		return genres.edit(id, request.name(), request.hidden());
	}
}
