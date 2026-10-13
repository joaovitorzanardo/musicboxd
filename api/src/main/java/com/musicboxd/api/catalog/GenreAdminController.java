package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

	public record GenreNameRequest(@NotBlank @Size(max = 50) String name) {

		/** Validation sees the stripped name, matching what is stored. */
		public GenreNameRequest {
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
	GenreView create(@Valid @RequestBody GenreNameRequest request) {
		return genres.create(request.name());
	}

	@PutMapping("/{id}")
	@ApiResponse(responseCode = "200", description = "Genre renamed; its visibility is unchanged")
	@ApiResponse(responseCode = "400", description = "Blank name or longer than 50 characters")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No genre with this id")
	@ApiResponse(responseCode = "409", description = "Another genre has this name, ignoring case (type urn:musicboxd:problem:genre-taken)")
	GenreView rename(@PathVariable UUID id, @Valid @RequestBody GenreNameRequest request) {
		return genres.rename(id, request.name());
	}

	@PutMapping("/{id}/hidden")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@ApiResponse(responseCode = "204", description = "Visibility set; a hidden genre leaves the options for new picks but stays on the albums and profiles that use it")
	@ApiResponse(responseCode = "400", description = "hidden missing")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No genre with this id")
	void setHidden(@PathVariable UUID id, @Valid @RequestBody VisibilityRequest request) {
		genres.setHidden(id, request.hidden());
	}
}
