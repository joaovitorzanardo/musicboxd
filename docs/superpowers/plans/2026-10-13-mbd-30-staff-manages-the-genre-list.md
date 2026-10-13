# MBD-30 Staff Manages the Genre List — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff can manage the controlled genre list. `PATCH /api/v1/admin/catalog/genres/{id}` renames, hides or shows a genre again. `GET /api/v1/admin/catalog/genres` lists every genre with its album count. MBD-28 already added `POST` for creating genres. A USER token gets 403 on all of them.

**Architecture:** This extends the existing `com.musicboxd.api.catalog` module (MBD-28) and does not add a new one. `GenreAdminController` gets two more handlers. `GenreService` gets `edit` and `list`. `GenreRepository` gets an `UPDATE … RETURNING` and a `LEFT JOIN … GROUP BY` count. MBD-21's `SecurityConfig` already requires `STAFF` on `/api/v1/admin/**` for every method, so security does not change. The V1 schema already has `genres.hidden`, the case-insensitive `genres_name_key` index and `album_genres_genre_idx` "for MBD-30", so there is no migration.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC 7, Spring Security 7), Spring JDBC `JdbcClient`, Jakarta Validation, PostgreSQL 18, JUnit 5 / MockMvc / AssertJ / JsonPath, Testcontainers.

**Spec:** Jira MBD-30 / `_bmad-output/initiative-musicboxd/epic-catalogo-busca/story-staff-manages-the-genre-list.md`. Its acceptance criterion: "Staff adds a genre and edits its name; a USER token gets 403 on both endpoints." Epic: `epic-catalogo-busca.md` (Done-when 5: "Staff can add and edit the genre list that epic-perfil-favoritos's favorite-genres feature reads from"). Architecture: `ARCHITECTURE-SPINE.md` AD-12 (never delete, only hide) and AD-13 (genre list in `catalog`, Staff endpoints at `/api/v1/admin/<module>/**`). UX: `EXPERIENCE.md` "Lista de generos (admin)": "Adicionar, renomear (edicao inline), ocultar; ocultar retira das opcoes mas mantem albuns e perfis que ja usam". Microcopy is "{n} álbuns" and "Ativo". See also `mockups/admin.html` section 3 "Gêneros".

**Branch:** work on the current branch `joaovitorzanardo/mbd-30-staff-manages-the-genre-list`. Commit messages end with `(MBD-30)` and the `Co-Authored-By` trailer. Run Gradle from `api/`. Docker must be running for Testcontainers. Never `git add -A`; add files by path.

## Decisions (proposed, confirm on review)

| Question | Decision |
|---|---|
| What "edit" covers | Rename **and** hide/show again, in one `PATCH`. The UX genre list has "Renomear" and "Ocultar", and MBD-28 left `GenreService` saying "Rename and hide arrive with MBD-30". Two endpoints (`POST` and `PATCH`) match the ticket's "403 on both endpoints". |
| Edit request | `PATCH /api/v1/admin/catalog/genres/{id}` with body `{name?, hidden?}`. A field that is absent or `null` is left as it is. Sending neither gives `400`. `name` is stripped and must be 1–50 characters, as on create. |
| Edit response | `200` with `GenreView {id, name, hidden}`. |
| Rename clash | Another genre already has the name, ignoring case → `409`, type `urn:musicboxd:problem:genre-taken` (same as create). Changing only the case of a genre's own name (`rock` → `Rock`) is allowed. |
| Unknown id | `404` Problem Details. A malformed UUID in the path → `400`. |
| Hide semantics | `hidden: true` takes the genre out of the selectable options. `AlbumService` already refuses hidden genres with `422`. Existing album links stay. `hidden: false` brings it back. Nothing is deleted (AD-12). |
| Admin list | `GET /api/v1/admin/catalog/genres` → `200 [{id, name, hidden, albumCount}]`. It includes hidden genres and is sorted by `lower(name)`, then `id`. There is no pagination because the list is short and stable. `albumCount` counts every linked album, hidden or not. This feeds the mockup's "{n} álbuns" and the V1 index comment ("Admin "{n} álbuns" per genre (MBD-30)"). MBD-53 needs this list to render the screen. |
| Public / User read of the list | **Out of scope.** MBD-48 (favorite genres) adds what `profiles` needs to validate ids and what the picker needs to show visible genres. |
| SPA | Out of scope (MBD-53). |
| Migration | None. V1 already has everything. |

## Global Constraints

- `/api/v1/admin/**` requires `STAFF` for every HTTP method (AD-7 / MBD-21). This story adds no security rule and must not move the admin matcher.
- No DELETE operation on genres, albums or songs anywhere in the API (AD-12). `CatalogContractTest` asserts this.
- `catalog` is the only writer of the `catalog` schema. Never edit an applied `V` migration file.
- Errors are `application/problem+json` (RFC 9457), with `urn:musicboxd:problem:<kebab>` types for the ones a client branches on. They render through the existing `CatalogExceptionHandler`.
- Genre names are stripped, 1–50 characters, and unique ignoring case. The stored value keeps the casing Staff chose.
- Repositories stay package-private. Java sources use tabs. Routes are documented by springdoc with `@ApiResponse`.
- Tests share one Postgres container across contexts. Use unique names (a random UUID suffix) for every genre you assert on, and never assert on the total row count of `catalog.genres`.

## Review Focus

- **The admin GET reached by a USER or anonymous caller.** `SecurityConfig` has a public-GET rule below the admin rule, so a regression there would leak the admin list. A USER must get 403 and an anonymous caller 401. (Task 2: `userTokenCannotListGenres`, `anonymousCallerCannotListGenres`.)
- **Renaming a genre to only a different case of its own name.** The unique index is on `lower(name)`, and a naive "name exists" pre-check would return 409. It must succeed. (Task 1: `renameMayChangeOnlyTheCase`.)
- **A hidden genre picked for a new album, then shown again.** Hidden must be refused (422) and shown again must be accepted. This proves the flag drives selectability end to end. (Task 1: `staffHidesAndShowsAGenreAgain`.)
- **A `PATCH` with an empty body or only nulls.** It must be a 400 Problem Details, not a silent 200 no-op and not a 500 from a null bind. (Task 1: `invalidEditsAre400AndChangeNothing`.)
- **A failed edit (409, 403, 400) leaving the row changed.** The stored name and flag must be untouched. (Task 1: the clash, USER and invalid tests each re-read the row.)

---

## File Structure

All paths are under `api/src/`.

**Edit: rename / hide (Task 1)**
- Modify `main/java/com/musicboxd/api/catalog/GenreAdminController.java`: add `EditGenreRequest` and a `@PatchMapping("/{id}")` handler.
- Modify `main/java/com/musicboxd/api/catalog/GenreService.java`: add `edit`, factor the duplicate-key translation into a helper, and update the class Javadoc.
- Modify `main/java/com/musicboxd/api/catalog/GenreRepository.java`: add `update`.
- Create `main/java/com/musicboxd/api/catalog/GenreNotFoundException.java`: 404 Problem Details.
- Modify `test/java/com/musicboxd/api/catalog/GenreAdminTest.java`: add the edit tests and helpers.
- Modify `test/java/com/musicboxd/api/catalog/CatalogContractTest.java`: assert that the `patch` route is in the contract.

**Admin list (Task 2)**
- Create `main/java/com/musicboxd/api/catalog/GenreAdminView.java`: a list row with its album count.
- Modify `GenreAdminController.java`, `GenreService.java` and `GenreRepository.java`: add `list` / `findAllWithAlbumCount`.
- Modify `GenreAdminTest.java`: add the list tests.
- Modify `CatalogContractTest.java`: assert that the `get` route is in the contract.

---

### Task 1: Staff renames, hides and shows a genre again (`PATCH`)

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/catalog/GenreNotFoundException.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreService.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java`
- Test: `api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java`, `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`

**Interfaces:**
- Consumes: `GenreView(UUID id, String name, boolean hidden)`, `GenreTakenException()`, `GenreRepository.NAME_UNIQUE`, `Constraints.violated(DataAccessException, String)`, `StaffTokens.bearer(TokenService, Role)`. All exist from MBD-28.
- Produces:
  - `GenreView GenreService.edit(UUID id, String name, Boolean hidden)`: `null` arguments leave a field as it is.
  - `Optional<GenreView> GenreRepository.update(UUID id, String name, Boolean hidden)`
  - `public class GenreNotFoundException extends ErrorResponseException` (no-arg constructor)
  - `public record GenreAdminController.EditGenreRequest(String name, Boolean hidden)`
  - Test helpers in `GenreAdminTest`: `String createdId(String name)`, `ResultActions edit(Role role, String id, String body)`, `ResultActions createAlbumWith(String genreId)`, `GenreView stored(String id)`. Task 2 reuses `createdId`, `edit` and `createAlbumWith`.

- [ ] **Step 1: Write the failing tests**

Add these imports to `GenreAdminTest.java` (keep the existing ones):

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.jayway.jsonpath.JsonPath;
```

Add these tests after `userTokenCannotCreateAGenre`:

```java
	@Test
	void staffRenamesAGenre() throws Exception {
		String id = createdId("Rock " + UUID.randomUUID());
		String renamed = "Hard Rock " + UUID.randomUUID();
		edit(Role.STAFF, id, "{\"name\":\"  " + renamed + "  \"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.name").value(renamed))
			.andExpect(jsonPath("$.hidden").value(false));
		assertThat(stored(id).name()).isEqualTo(renamed);
	}

	@Test
	void renameMayChangeOnlyTheCase() throws Exception {
		String name = "rock " + UUID.randomUUID();
		String id = createdId(name);
		edit(Role.STAFF, id, "{\"name\":\"" + name.toUpperCase() + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value(name.toUpperCase()));
	}

	@Test
	void renameToAnotherGenresNameIs409AndChangesNothing() throws Exception {
		String taken = "Jazz " + UUID.randomUUID();
		createdId(taken);
		String name = "Blues " + UUID.randomUUID();
		String id = createdId(name);
		edit(Role.STAFF, id, "{\"name\":\" " + taken.toLowerCase() + " \"}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:genre-taken"));
		assertThat(stored(id).name()).isEqualTo(name);
	}

	@Test
	void staffHidesAndShowsAGenreAgain() throws Exception {
		String name = "MPB " + UUID.randomUUID();
		String id = createdId(name);

		edit(Role.STAFF, id, "{\"hidden\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value(name))
			.andExpect(jsonPath("$.hidden").value(true));
		createAlbumWith(id)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.genreIds[0]").value(id));

		edit(Role.STAFF, id, "{\"hidden\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(false));
		createAlbumWith(id).andExpect(status().isCreated());
	}

	@Test
	void invalidEditsAre400AndChangeNothing() throws Exception {
		String name = "Funk " + UUID.randomUUID();
		String id = createdId(name);
		for (String body : new String[] { "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(51) + "\"}", "{}",
				"{\"name\":null,\"hidden\":null}" }) {
			edit(Role.STAFF, id, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(stored(id)).isEqualTo(new GenreView(UUID.fromString(id), name, false));
	}

	@Test
	void unknownGenreIs404AndMalformedIdIs400() throws Exception {
		edit(Role.STAFF, UUID.randomUUID().toString(), "{\"name\":\"Anything\"}")
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		edit(Role.STAFF, "not-a-uuid", "{\"name\":\"Anything\"}")
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void userTokenCannotEditAGenre() throws Exception {
		String name = "Samba " + UUID.randomUUID();
		String id = createdId(name);
		edit(Role.USER, id, "{\"name\":\"Hijacked " + UUID.randomUUID() + "\",\"hidden\":true}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertThat(stored(id)).isEqualTo(new GenreView(UUID.fromString(id), name, false));
	}
```

Add these helpers next to `create(...)` and `countNamed(...)`:

```java
	private String createdId(String name) throws Exception {
		String json = create(Role.STAFF, "{\"name\":\"" + name + "\"}")
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.id");
	}

	private ResultActions edit(Role role, String id, String body) throws Exception {
		return mockMvc.perform(patch(GENRES + "/" + id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions createAlbumWith(String genreId) throws Exception {
		return mockMvc.perform(post("/api/v1/admin/catalog/albums")
			.header("Authorization", StaffTokens.bearer(tokens, Role.STAFF))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Album\",\"artist\":\"Artist\",\"genreIds\":[\"" + genreId
					+ "\"],\"songs\":[{\"title\":\"Song\"}]}"));
	}

	private GenreView stored(String id) {
		return jdbc.sql("SELECT id, name, hidden FROM catalog.genres WHERE id = :id")
			.param("id", UUID.fromString(id))
			.query(GenreView.class).single();
	}
```

In `CatalogContractTest.java`, rename the test to `adminRoutesAreInTheContractAndNothingDeletes` and add this after the genres `post` assertion:

```java
		assertThat(paths.get("/api/v1/admin/catalog/genres/{id}")).containsKey("patch");
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `api/`): `./gradlew test --tests com.musicboxd.api.catalog.GenreAdminTest --tests com.musicboxd.api.catalog.CatalogContractTest`
Expected: FAIL. The new edit tests get `405 Method Not Allowed` (or a `404` for the route), and the contract test fails because `paths.get(".../genres/{id}")` is `null`. The four MBD-28 genre tests still PASS. If `userTokenCannotEditAGenre` passes already, that is expected because the route guard runs first. Its `stored(...)` check still has to pass after implementation.

- [ ] **Step 3: Add `GenreNotFoundException`**

Create `api/src/main/java/com/musicboxd/api/catalog/GenreNotFoundException.java`:

```java
package com.musicboxd.api.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class GenreNotFoundException extends ErrorResponseException {

	public GenreNotFoundException() {
		super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Genre not found"), null);
	}
}
```

- [ ] **Step 4: Add `GenreRepository.update`**

Add `import java.util.Optional;` to `GenreRepository.java`. Then add this method after `insert`:

```java
	/** A null name or hidden leaves that column as it is. Empty when no genre has this id. */
	Optional<GenreView> update(UUID id, String name, Boolean hidden) {
		return jdbc.sql("""
				UPDATE catalog.genres
				SET name = coalesce(CAST(:name AS text), name), hidden = coalesce(CAST(:hidden AS boolean), hidden)
				WHERE id = :id
				RETURNING id, name, hidden""")
			.param("id", id)
			.param("name", name)
			.param("hidden", hidden)
			.query(GenreView.class)
			.optional();
	}
```

The `CAST`s give Postgres a type for a null bind parameter. Without them a null `:name` can fail with "could not determine data type of parameter".

- [ ] **Step 5: Add `GenreService.edit`**

Replace the body of `GenreService.java` with the following. It keeps `create`'s behaviour and moves the duplicate-key translation into `taken(...)` so both writes share it:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import com.musicboxd.api.db.Constraints;

/** The controlled genre list (AD-13). Genres are renamed or hidden, never deleted (AD-12). */
@Service
class GenreService {

	private final GenreRepository genres;

	GenreService(GenreRepository genres) {
		this.genres = genres;
	}

	@Transactional
	GenreView create(String name) {
		UUID id = UUID.randomUUID();
		try {
			genres.insert(id, name);
		}
		catch (DuplicateKeyException e) {
			throw taken(e);
		}
		return new GenreView(id, name, false);
	}

	/** Renames, hides or shows again; a null argument is left as it is. Hidden genres keep their album links. */
	@Transactional
	GenreView edit(UUID id, String name, Boolean hidden) {
		if (name == null && hidden == null) {
			throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
					ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Send name, hidden, or both"), null);
		}
		try {
			return genres.update(id, name, hidden).orElseThrow(GenreNotFoundException::new);
		}
		catch (DuplicateKeyException e) {
			throw taken(e);
		}
	}

	/** The unique index ignores case, so it also catches a rename onto another genre's name in any case. */
	private static RuntimeException taken(DuplicateKeyException e) {
		return Constraints.violated(e, GenreRepository.NAME_UNIQUE) ? new GenreTakenException() : e;
	}
}
```

- [ ] **Step 6: Add the `PATCH` handler**

In `GenreAdminController.java`, add these imports:

```java
import java.util.UUID;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
```

Add this record after `CreateGenreRequest`:

```java
	/** Each field is optional; an absent one is left as it is. Validation sees the stripped name. */
	public record EditGenreRequest(@Size(min = 1, max = 50) String name, Boolean hidden) {

		public EditGenreRequest {
			name = name == null ? null : name.strip();
		}
	}
```

Add this handler after `create`:

```java
	@PatchMapping("/{id}")
	@ApiResponse(responseCode = "200", description = "Genre renamed, hidden or shown again; absent fields are unchanged")
	@ApiResponse(responseCode = "400", description = "Blank name, longer than 50 characters, malformed id, or neither name nor hidden sent")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No genre with this id")
	@ApiResponse(responseCode = "409", description = "Another genre has this name, ignoring case (type urn:musicboxd:problem:genre-taken)")
	GenreView edit(@PathVariable UUID id, @Valid @RequestBody EditGenreRequest request) {
		return genres.edit(id, request.name(), request.hidden());
	}
```

Change the class Javadoc to: `/** Staff-only by route: SecurityConfig requires STAFF on /api/v1/admin/** (MBD-21). No delete, ever (AD-12). */`

- [ ] **Step 7: Run the tests to verify they pass**

Run (from `api/`): `./gradlew test --tests com.musicboxd.api.catalog.GenreAdminTest --tests com.musicboxd.api.catalog.CatalogContractTest --tests com.musicboxd.api.catalog.AlbumAdminTest`
Expected: PASS. `AlbumAdminTest` is included because `staffHidesAndShowsAGenreAgain` relies on its 422 path, which must stay unchanged. If `@PathVariable UUID id` fails with "Name for argument of type UUID not specified", the `-parameters` compiler flag is missing. Use `@PathVariable("id")` instead of adding build config.

- [ ] **Step 8: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/catalog/GenreNotFoundException.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreService.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java \
  api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java \
  api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java
git commit -m "$(cat <<'EOF'
Let Staff rename, hide and show a genre again; 409 on a name taken in any case (MBD-30)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Staff lists the genres with their album counts (`GET`)

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/catalog/GenreAdminView.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreService.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java`
- Test: `api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java`, `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`

**Interfaces:**
- Consumes: from Task 1, the `GenreAdminTest` helpers `createdId(String)`, `edit(Role, String, String)` and `createAlbumWith(String)`.
- Produces:
  - `public record GenreAdminView(UUID id, String name, boolean hidden, long albumCount)`
  - `List<GenreAdminView> GenreService.list()`
  - `List<GenreAdminView> GenreRepository.findAllWithAlbumCount()`
  - MBD-53 consumes the route `GET /api/v1/admin/catalog/genres` → `200 [GenreAdminView]`.

- [ ] **Step 1: Write the failing tests**

Add these imports to `GenreAdminTest.java`:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
```

Add these tests:

```java
	@Test
	void staffListsEveryGenreWithItsAlbumCountSortedByName() throws Exception {
		String suffix = " " + UUID.randomUUID();
		String beta = createdId("beta" + suffix);
		createdId("Alpha" + suffix);
		String gamma = createdId("gamma" + suffix);
		createAlbumWith(beta).andExpect(status().isCreated());
		createAlbumWith(gamma).andExpect(status().isCreated());
		edit(Role.STAFF, gamma, "{\"hidden\":true}").andExpect(status().isOk());

		String json = list(Role.STAFF)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		List<Map<String, Object>> rows = JsonPath.read(json, "$");
		assertThat(rows.stream()
			.filter(row -> ((String) row.get("name")).endsWith(suffix))
			.map(row -> row.get("name") + "|" + row.get("albumCount") + "|" + row.get("hidden")))
			.as("hidden genres are listed and keep their albums; sorted ignoring case")
			.containsExactly("Alpha" + suffix + "|0|false", "beta" + suffix + "|1|false",
					"gamma" + suffix + "|1|true");
	}

	@Test
	void userTokenCannotListGenres() throws Exception {
		list(Role.USER)
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void anonymousCallerCannotListGenres() throws Exception {
		mockMvc.perform(get(GENRES))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}
```

Add this helper:

```java
	private ResultActions list(Role role) throws Exception {
		return mockMvc.perform(get(GENRES).header("Authorization", StaffTokens.bearer(tokens, role)));
	}
```

In `CatalogContractTest.java`, replace the genres `post` assertion with:

```java
		assertThat(paths.get("/api/v1/admin/catalog/genres")).containsKeys("post", "get");
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `api/`): `./gradlew test --tests com.musicboxd.api.catalog.GenreAdminTest --tests com.musicboxd.api.catalog.CatalogContractTest`
Expected: `staffListsEveryGenreWithItsAlbumCountSortedByName` FAILS with `405 Method Not Allowed`. The contract test FAILS because the genres path has no `get` key. `userTokenCannotListGenres` and `anonymousCallerCannotListGenres` may already pass because the route guard answers first. That is fine; they pin the guard.

- [ ] **Step 3: Add `GenreAdminView`**

Create `api/src/main/java/com/musicboxd/api/catalog/GenreAdminView.java`:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

/** A row of the admin genre list; albumCount backs the "{n} álbuns" label and counts hidden albums too. */
public record GenreAdminView(UUID id, String name, boolean hidden, long albumCount) {
}
```

- [ ] **Step 4: Add `GenreRepository.findAllWithAlbumCount`**

Add `import java.util.List;` to `GenreRepository.java`. Then add this method:

```java
	/** Every genre, hidden ones too, in name order ignoring case. */
	List<GenreAdminView> findAllWithAlbumCount() {
		return jdbc.sql("""
				SELECT g.id, g.name, g.hidden, count(ag.album_id) AS album_count
				FROM catalog.genres g
				LEFT JOIN catalog.album_genres ag ON ag.genre_id = g.id
				GROUP BY g.id
				ORDER BY lower(g.name), g.id""")
			.query(GenreAdminView.class)
			.list();
	}
```

`JdbcClient`'s record mapper (`DataClassRowMapper`) maps `album_count` to `albumCount`. `GROUP BY g.id` is enough because `id` is the primary key.

- [ ] **Step 5: Add `GenreService.list`**

Add `import java.util.List;` to `GenreService.java`. Then add this method after `edit`:

```java
	@Transactional(readOnly = true)
	List<GenreAdminView> list() {
		return genres.findAllWithAlbumCount();
	}
```

- [ ] **Step 6: Add the `GET` handler**

In `GenreAdminController.java`, add these imports:

```java
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
```

Add this handler before `create`:

```java
	@GetMapping
	@ApiResponse(responseCode = "200", description = "Every genre, hidden ones included, sorted by name ignoring case, with its album count")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	List<GenreAdminView> list() {
		return genres.list();
	}
```

- [ ] **Step 7: Run the whole suite**

Run (from `api/`): `./gradlew test`
Expected: PASS, everything. This covers `AdminRouteProtectionTest`, `SecurityRulesTest` and `OpenApiExportBootTest`. The last one must still boot without a database, and the new handlers add no startup DB access.

- [ ] **Step 8: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/catalog/GenreAdminView.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreService.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java \
  api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java \
  api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java
git commit -m "$(cat <<'EOF'
Let Staff list every genre, hidden too, with its album count (MBD-30)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

## Acceptance check (after both tasks)

These map to the ticket's "Verify: Staff adds a genre and edits its name; a USER token gets 403 on both endpoints":

- Adds a genre: `staffCreatesAGenre` (MBD-28, still green).
- Edits its name: `staffRenamesAGenre`.
- USER gets 403 on create: `userTokenCannotCreateAGenre`.
- USER gets 403 on edit: `userTokenCannotEditAGenre`.
- Beyond the literal AC, the plan adds hide/show again (UX "ocultar") and the admin list (UX "{n} álbuns", needed by MBD-53). The USER-403 coverage extends to the list.
