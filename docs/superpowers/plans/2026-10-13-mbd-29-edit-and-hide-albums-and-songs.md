# MBD-29 Edit and Hide Albums and Songs; Assign Songs to an Album — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff can open an Album in the editor, save its whole state in one atomic call (title, artist, year, genres, visibility, and a tracklist that edits, reorders, hides and adds Songs), and hide/unhide an Album or Song with a one-field toggle. Anyone can open an Album or Song by id, hidden or not. No delete exists anywhere.

**Architecture:** Everything stays in `com.musicboxd.api.catalog`, built on MBD-28. `AlbumRepository` gains reads and update writes (still package-private `JdbcClient`). `AlbumService` gains `adminView`, `publicView`, `songPage`, `update`, `setAlbumHidden` and `setSongHidden`. Routes: Staff routes on `AlbumAdminController` plus a new `SongAdminController` under `/api/v1/admin/catalog/**`, and a new public `CatalogController` under `/api/v1/catalog/**`. `SecurityConfig` already makes `/api/v1/admin/**` STAFF-only for every method and every other GET public, so security needs no change. Reordering relies on MBD-28's `DEFERRABLE` `(album_id, track_number)` constraint, which is deferred inside the edit transaction.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC 7, Spring Security 7), Spring JDBC `JdbcClient`, Jakarta Validation, PostgreSQL 18, JUnit 5 / MockMvc / AssertJ / JsonPath, Testcontainers.

**Spec:** Jira MBD-29 / `_bmad-output/initiative-musicboxd/epic-catalogo-busca/story-edit-and-hide-albums-and-songs-assign-songs-to-an-album.md` ("Staff edits an Album, adds a Song to it, and hides the Album; the hidden Album disappears from search but still opens at its direct link; no delete endpoint exists."). Epic: `epic-catalogo-busca.md` (CAP-3, Done-when 1–3). Architecture: `ARCHITECTURE-SPINE.md` AD-1, AD-7, AD-12 ("hidden entities disappear from search but stay resolvable by id … Moving a song between albums is not supported"), AD-13. UX: `EXPERIENCE.md` "Editor de album (admin)", "Tabela admin", "Oculto (catalogo)" and `mockups/admin.html` ("Visibilidade Visível/Oculto", "6 faixas · 5 visíveis · arraste para reordenar", per-track "Ocultar/Reexibir", "+ Adicionar faixa", "Alterações só valem depois de salvar."). Prior plan: `docs/superpowers/plans/2026-10-13-mbd-28-catalog-tracer-bullet.md`.

**Branch:** the current branch `joaovitorzanardo/mbd-29-edit-and-hide-albums-and-songs-assign-son`. Commit messages end with `(MBD-29)` and the `Co-Authored-By` trailer. Run Gradle from `api/`. Docker must be running for Testcontainers. Never `git add -A`; add files by path.

## Decisions (approved in chat, 2026-10-13)

| Question | Decision |
|---|---|
| Edit API shape | **One atomic PUT** `PUT /api/v1/admin/catalog/albums/{id}` carries the editor's whole state: `{title, artist, releaseYear?, genreIds?, hidden, songs:[{id?, title, durationSeconds?, hidden}]}`. List order is track order (1..n). An entry with `id` edits that Song (title, duration, hidden, position). An entry without `id` adds a new Song to this Album, which is what "assign Songs to an Album" means (a Song belongs to one Album for life, AD-12). Returns `200` with the admin view. |
| Tracklist integrity | Every existing Song of the Album must appear exactly once. A missing Song (that would be a delete), an id that is not this Album's (that would be a move, or it doesn't exist), or a repeated id → `422`, type `urn:musicboxd:problem:tracklist-mismatch`, properties `missingSongIds`, `unknownSongIds`, `repeatedSongIds`. Nothing is written. |
| Genres on edit | Same rules as create (≤ 10, duplicates collapsed), except a hidden genre the Album **already carries** may stay. Hiding a genre keeps the albums that use it (UX "Lista de generos"). A newly picked hidden or unknown genre → `422 unknown-genre`. `genreIds` omitted or `null` means no genres. |
| Hide toggles | `PUT /api/v1/admin/catalog/albums/{id}/hidden` and `PUT /api/v1/admin/catalog/songs/{id}/hidden`, body `{"hidden": true|false}` → `204`. Idempotent. Used by the catalog table's "Ocultar/Reexibir". |
| Admin read | `GET /api/v1/admin/catalog/albums/{id}` → the album with **all** Songs (hidden ones included, flagged), so the editor can load. |
| Public reads | `GET /api/v1/catalog/albums/{id}` → the album with its **visible** Songs only (mock: "5 visíveis"). Track numbers keep their real values, so gaps are possible. A hidden Album still resolves (`hidden: true`). `GET /api/v1/catalog/songs/{id}` → `{id, trackNumber, title, durationSeconds, hidden, album:{id, title, artist, releaseYear, hidden}}`, and it resolves hidden Songs and Songs of hidden Albums. No token needed. |
| Search | Out of scope: there is no search yet (MBD-34, whose AC already says hidden items are absent). This story persists and exposes the `hidden` flag that search filters on. |
| Genre order on reads | `album_genres` stores no order, so every read lists genres by `lower(name)`. (Create's 201 still echoes the picked order, as in MBD-28.) |
| Concurrency | The edit locks the album row (`SELECT … FOR UPDATE`), so two simultaneous saves on one Album run one after the other, and the tracklist check sees the current Songs. Last save wins. |
| Not found / bad id | Unknown album or song id → `404` Problem Details. Malformed UUID in the path → `400` Problem Details. |
| Admin list endpoint | **Left out** (not in this AC). Logged in `deferred-work.md` for MBD-53. |
| No delete | No DELETE route in `catalog`; a contract test plus a live DELETE probe assert it. |
| SPA | Out of scope (MBD-53). |

## Global Constraints

- `/api/v1/admin/**` requires `STAFF` for every HTTP method (AD-7). This story adds no security rule and must not move the admin matcher. Public reads ride the existing `GET /api/**` permitAll.
- `catalog` is the only writer of the `catalog` schema; no cross-schema foreign keys (AD-1, AD-12).
- No DELETE operation on albums, songs or genres anywhere in the API (AD-12). Deleting `album_genres` **link rows** when an edit replaces an Album's genres is fine: links are not catalog entities.
- A Song's `album_id` never changes (AD-12): every song `UPDATE` filters on both `id` and `album_id`.
- No new migration: MBD-28's `V1` already has `hidden`, `updated_at` and the deferrable `songs_album_track_key`. Never edit an applied `V` file.
- Writes set `updated_at = now()` on the rows they touch.
- Ids are UUIDs generated in Java (`UUID.randomUUID()`); timestamps are UTC ISO-8601 on the wire.
- Errors are `application/problem+json` (RFC 9457), with `urn:musicboxd:problem:<kebab>` types for the ones a client branches on.
- Repositories stay package-private. Java sources use tabs. Routes are documented by springdoc with `@ApiResponse`.
- Tests share one Postgres container across contexts: use unique titles and genre names (random suffix).

## Review Focus

- **Saving an Album that already carries a genre Staff later hid.** The editor sends that id back unchanged. The save must succeed and keep the genre, not fail with 422 the way a new pick would. (Task 3: `aGenreTheAlbumAlreadyHasStaysAfterItIsHidden`, `aNewlyPickedHiddenGenreIs422`.)
- **A tracklist that would delete or move a Song.** Leaving an existing Song out, or naming another Album's Song, must be refused with 422 and change nothing: no title change, no row moved. (Task 3: `leavingOutASongIs422AndChangesNothing`, `aSongOfAnotherAlbumIs422AndStaysWhereItIs`, `aRepeatedSongIs422`.)
- **Drag-reordering, including a new Song inserted at the top.** Swapping track numbers must not trip the `(album_id, track_number)` unique constraint with a 500. (Task 3: `reorderingRenumbersTheTracklist`.)
- **Opening hidden things by link, anonymously.** A hidden Album, a hidden Song, and a Song of a hidden Album must each return 200 without a token. An unknown id must return 404 and a malformed id 400, never 500. (Task 1: `hiddenAlbumStillOpensByLink`, `hiddenSongIsLeftOffTheAlbumPageButOpensByItsOwnLink`, `songOfAHiddenAlbumOpensByLink`, `unknownIdsAre404`, `malformedIdIs400`.)
- **A USER token on the new Staff routes.** GET editor, PUT edit and both hide toggles must each return 403 and change nothing. (Task 1: `userTokenCannotUseTheAdminRead`; Task 2: `userTokenGets403AndChangesNothing`; Task 3: `userTokenGets403AndChangesNothing`.)

---

## File Structure

**Reads (Task 1)**
- Create `api/src/main/java/com/musicboxd/api/catalog/AlbumNotFoundException.java`, `SongNotFoundException.java`: 404 Problem Details.
- Create `api/src/main/java/com/musicboxd/api/catalog/AlbumSummary.java`, `SongPageView.java`: the public Song response.
- Create `api/src/main/java/com/musicboxd/api/catalog/CatalogController.java`: public `GET /api/v1/catalog/albums/{id}`, `/songs/{id}`.
- Modify `api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java`: add reads.
- Modify `api/src/main/java/com/musicboxd/api/catalog/AlbumService.java`: add `adminView`, `publicView`, `songPage`.
- Modify `api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java`: add `GET /{id}`.
- Create `api/src/test/java/com/musicboxd/api/catalog/CatalogFixtures.java`: test helper (create an album through the API, insert genres).
- Create `api/src/test/java/com/musicboxd/api/catalog/CatalogReadTest.java`.

**Hide toggles (Task 2)**
- Create `api/src/main/java/com/musicboxd/api/catalog/VisibilityRequest.java`.
- Create `api/src/main/java/com/musicboxd/api/catalog/SongAdminController.java`: `PUT /api/v1/admin/catalog/songs/{id}/hidden`.
- Modify `AlbumAdminController.java` (`PUT /{id}/hidden`), `AlbumService.java`, `AlbumRepository.java`.
- Create `api/src/test/java/com/musicboxd/api/catalog/AlbumVisibilityTest.java`.

**Atomic edit (Task 3)**
- Create `api/src/main/java/com/musicboxd/api/catalog/TracklistMismatchException.java`.
- Modify `GenreRepository.java` (`findSelectableFor`), `AlbumRepository.java` (update writes; `insertSong` takes `hidden`), `AlbumService.java` (`update`; genre check refactor), `AlbumAdminController.java` (`PUT /{id}`, `UpdateAlbumRequest`, `TrackRequest`).
- Create `api/src/test/java/com/musicboxd/api/catalog/AlbumEditTest.java`.

**Acceptance and contract (Task 4)**
- Create `api/src/test/java/com/musicboxd/api/catalog/CatalogEditAcceptanceTest.java`.
- Modify `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`.
- Modify `_bmad-output/implementation-artifacts/deferred-work.md`: the admin list endpoint.

---

### Task 1: Read an Album or Song by id (public and Staff editor)

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/catalog/AlbumNotFoundException.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/SongNotFoundException.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/AlbumSummary.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/SongPageView.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/CatalogController.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumService.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java`
- Create: `api/src/test/java/com/musicboxd/api/catalog/CatalogFixtures.java`
- Test: `api/src/test/java/com/musicboxd/api/catalog/CatalogReadTest.java`

**Interfaces:**
- Consumes: MBD-28's `AlbumView(UUID id, String title, String artist, Integer releaseYear, boolean hidden, Instant createdAt, List<SongView> songs, List<GenreView> genres)`, `SongView(UUID id, int trackNumber, String title, Integer durationSeconds, boolean hidden)`, `GenreView(UUID id, String name, boolean hidden)`, `StaffTokens.bearer(TokenService, Role)`, and `POST /api/v1/admin/catalog/albums`.
- Produces:
  - `AlbumRepository.AlbumRow(UUID id, String title, String artist, Integer releaseYear, boolean hidden, Instant createdAt)`; `Optional<AlbumRow> findAlbum(UUID)`; `List<SongView> findSongs(UUID albumId, boolean withHidden)`; `List<GenreView> findGenres(UUID albumId)`; `Optional<SongPageView> findSongPage(UUID)`.
  - `AlbumService.adminView(UUID) : AlbumView` (all songs), `publicView(UUID) : AlbumView` (visible songs), `songPage(UUID) : SongPageView`. All throw `AlbumNotFoundException` / `SongNotFoundException`.
  - `record AlbumSummary(UUID id, String title, String artist, Integer releaseYear, boolean hidden)`, `record SongPageView(UUID id, int trackNumber, String title, Integer durationSeconds, boolean hidden, AlbumSummary album)`.
  - Test helper `CatalogFixtures.createAlbum(MockMvc, TokenService, List<UUID> genreIds, String... songTitles) : String` (201 body), `uuidAt(String json, String path) : UUID`, `genre(JdbcClient, String prefix, boolean hidden) : UUID`.

- [ ] **Step 1: Write the test helper**

`api/src/test/java/com/musicboxd/api/catalog/CatalogFixtures.java`:

```java
package com.musicboxd.api.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** Catalog rows for tests that start from an existing Album. Tests share one database: names are unique. */
final class CatalogFixtures {

	private CatalogFixtures() {
	}

	/** Created through the real Staff endpoint, songs numbered 1..n; returns the 201 body. */
	static String createAlbum(MockMvc mockMvc, TokenService tokens, List<UUID> genreIds, String... songTitles)
			throws Exception {
		String genres = genreIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
		String songs = Stream.of(songTitles).map(t -> "{\"title\":\"" + t + "\"}").collect(Collectors.joining(","));
		return mockMvc.perform(post("/api/v1/admin/catalog/albums")
				.header("Authorization", StaffTokens.bearer(tokens, Role.STAFF))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"Rumours %s","artist":"Fleetwood Mac","releaseYear":1977,"genreIds":[%s],"songs":[%s]}"""
					.formatted(UUID.randomUUID(), genres, songs)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
	}

	static UUID uuidAt(String json, String path) {
		return UUID.fromString(JsonPath.read(json, path));
	}

	/** {@code prefix} lets a test control name order, e.g. "Ambient" sorts before "Zydeco". */
	static UUID genre(JdbcClient jdbc, String prefix, boolean hidden) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO catalog.genres (id, name, hidden) VALUES (:id, :name, :hidden)")
			.param("id", id)
			.param("name", prefix + " " + id)
			.param("hidden", hidden)
			.update();
		return id;
	}
}
```

- [ ] **Step 2: Write the failing test**

`api/src/test/java/com/musicboxd/api/catalog/CatalogReadTest.java`:

```java
package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.uuidAt;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** AD-12: hidden items leave search (MBD-34) but stay resolvable by id, for anyone. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogReadTest {

	private static final String PUBLIC_ALBUM = "/api/v1/catalog/albums/{id}";
	private static final String PUBLIC_SONG = "/api/v1/catalog/songs/{id}";
	private static final String ADMIN_ALBUM = "/api/v1/admin/catalog/albums/{id}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void anyoneOpensAnAlbumByIdWithSongsInTrackOrder() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Second Hand News", "Dreams");
		UUID id = uuidAt(created, "$.id");
		mockMvc.perform(get(PUBLIC_ALBUM, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.title").value(JsonPath.<String>read(created, "$.title")))
			.andExpect(jsonPath("$.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.releaseYear").value(1977))
			.andExpect(jsonPath("$.hidden").value(false))
			.andExpect(jsonPath("$.createdAt").value(JsonPath.<String>read(created, "$.createdAt")))
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andExpect(jsonPath("$.songs[0].trackNumber").value(1))
			.andExpect(jsonPath("$.songs[0].title").value("Second Hand News"))
			.andExpect(jsonPath("$.songs[1].trackNumber").value(2))
			.andExpect(jsonPath("$.songs[1].title").value("Dreams"));
	}

	@Test
	void genresAreListedByName() throws Exception {
		UUID zydeco = CatalogFixtures.genre(jdbc, "Zydeco", false);
		UUID ambient = CatalogFixtures.genre(jdbc, "Ambient", false);
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(zydeco, ambient), "Dreams"), "$.id");
		mockMvc.perform(get(PUBLIC_ALBUM, id))
			.andExpect(jsonPath("$.genres", hasSize(2)))
			.andExpect(jsonPath("$.genres[0].id").value(ambient.toString()))
			.andExpect(jsonPath("$.genres[1].id").value(zydeco.toString()));
	}

	@Test
	void hiddenAlbumStillOpensByLink() throws Exception {
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");
		hideAlbum(id);
		mockMvc.perform(get(PUBLIC_ALBUM, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(true))
			.andExpect(jsonPath("$.songs", hasSize(1)));
	}

	@Test
	void hiddenSongIsLeftOffTheAlbumPageButOpensByItsOwnLink() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two", "Three");
		UUID album = uuidAt(created, "$.id");
		UUID two = uuidAt(created, "$.songs[1].id");
		hideSong(two);

		mockMvc.perform(get(PUBLIC_ALBUM, album))
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andExpect(jsonPath("$.songs[0].trackNumber").value(1))
			.andExpect(jsonPath("$.songs[1].trackNumber").value(3));
		mockMvc.perform(get(PUBLIC_SONG, two))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(two.toString()))
			.andExpect(jsonPath("$.trackNumber").value(2))
			.andExpect(jsonPath("$.title").value("Two"))
			.andExpect(jsonPath("$.hidden").value(true))
			.andExpect(jsonPath("$.album.id").value(album.toString()))
			.andExpect(jsonPath("$.album.title").value(JsonPath.<String>read(created, "$.title")))
			.andExpect(jsonPath("$.album.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.album.releaseYear").value(1977))
			.andExpect(jsonPath("$.album.hidden").value(false));
	}

	@Test
	void songOfAHiddenAlbumOpensByLink() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		hideAlbum(uuidAt(created, "$.id"));
		mockMvc.perform(get(PUBLIC_SONG, uuidAt(created, "$.songs[0].id")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(false))
			.andExpect(jsonPath("$.album.hidden").value(true));
	}

	@Test
	void staffEditorSeesHiddenSongs() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two", "Three");
		UUID album = uuidAt(created, "$.id");
		hideSong(uuidAt(created, "$.songs[1].id"));
		mockMvc.perform(get(ADMIN_ALBUM, album).header("Authorization", StaffTokens.bearer(tokens, Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs", hasSize(3)))
			.andExpect(jsonPath("$.songs[1].hidden").value(true));
	}

	@Test
	void userTokenCannotUseTheAdminRead() throws Exception {
		UUID album = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");
		mockMvc.perform(get(ADMIN_ALBUM, album).header("Authorization", StaffTokens.bearer(tokens, Role.USER)))
			.andExpect(status().isForbidden());
	}

	@Test
	void unknownIdsAre404() throws Exception {
		UUID missing = UUID.randomUUID();
		for (String path : List.of(PUBLIC_ALBUM, PUBLIC_SONG)) {
			mockMvc.perform(get(path, missing))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		mockMvc.perform(get(ADMIN_ALBUM, missing).header("Authorization", StaffTokens.bearer(tokens, Role.STAFF)))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void malformedIdIs400() throws Exception {
		mockMvc.perform(get("/api/v1/catalog/albums/not-a-uuid"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	private void hideAlbum(UUID id) {
		jdbc.sql("UPDATE catalog.albums SET hidden = true WHERE id = :id").param("id", id).update();
	}

	private void hideSong(UUID id) {
		jdbc.sql("UPDATE catalog.songs SET hidden = true WHERE id = :id").param("id", id).update();
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew test --tests com.musicboxd.api.catalog.CatalogReadTest`
Expected: FAIL. The GETs return 404 for every case (there are no routes yet), so `anyoneOpens…` fails on `status().isOk()`, and `malformedIdIs400` gets 404 instead of 400.

- [ ] **Step 4: Add the 404 exceptions and the Song views**

`api/src/main/java/com/musicboxd/api/catalog/AlbumNotFoundException.java`:

```java
package com.musicboxd.api.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class AlbumNotFoundException extends ErrorResponseException {

	public AlbumNotFoundException() {
		super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Album not found"), null);
	}
}
```

`api/src/main/java/com/musicboxd/api/catalog/SongNotFoundException.java`:

```java
package com.musicboxd.api.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class SongNotFoundException extends ErrorResponseException {

	public SongNotFoundException() {
		super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Song not found"), null);
	}
}
```

`api/src/main/java/com/musicboxd/api/catalog/AlbumSummary.java`:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

/** The Album a Song page links back to. */
public record AlbumSummary(UUID id, String title, String artist, Integer releaseYear, boolean hidden) {
}
```

`api/src/main/java/com/musicboxd/api/catalog/SongPageView.java`:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

/** A Song opened by its own link; resolves even when the Song or its Album is hidden (AD-12). */
public record SongPageView(UUID id, int trackNumber, String title, Integer durationSeconds, boolean hidden,
		AlbumSummary album) {
}
```

- [ ] **Step 5: Add the reads to `AlbumRepository`**

In `api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java`:

Replace the class Javadoc with:

```java
/** Reads and writes albums, their songs and genre links. Package-private: other modules will read through a catalog API (AD-1). */
```

Add these imports: `java.util.List`, `java.util.Optional`.

Add inside the class, after the constructor:

```java
	/** Album columns only; songs and genres are read separately. */
	record AlbumRow(UUID id, String title, String artist, Integer releaseYear, boolean hidden, Instant createdAt) {
	}

	Optional<AlbumRow> findAlbum(UUID id) {
		return jdbc.sql("SELECT id, title, artist, release_year, hidden, created_at FROM catalog.albums WHERE id = :id")
			.param("id", id)
			.query((rs, n) -> new AlbumRow(rs.getObject("id", UUID.class), rs.getString("title"),
					rs.getString("artist"), rs.getObject("release_year", Integer.class), rs.getBoolean("hidden"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.optional();
	}

	/** In track order. The public album page leaves hidden songs out; the admin editor shows them. */
	List<SongView> findSongs(UUID albumId, boolean withHidden) {
		return jdbc.sql("""
				SELECT id, track_number, title, duration_seconds, hidden FROM catalog.songs
				WHERE album_id = :albumId AND (:withHidden OR NOT hidden)
				ORDER BY track_number""")
			.param("albumId", albumId)
			.param("withHidden", withHidden)
			.query(SongView.class)
			.list();
	}

	/** album_genres keeps no order, so reads list genres by name. Hidden genres stay on the albums that use them. */
	List<GenreView> findGenres(UUID albumId) {
		return jdbc.sql("""
				SELECT g.id, g.name, g.hidden
				FROM catalog.album_genres ag JOIN catalog.genres g ON g.id = ag.genre_id
				WHERE ag.album_id = :albumId
				ORDER BY lower(g.name), g.id""")
			.param("albumId", albumId)
			.query(GenreView.class)
			.list();
	}

	Optional<SongPageView> findSongPage(UUID id) {
		return jdbc.sql("""
				SELECT s.id, s.track_number, s.title, s.duration_seconds, s.hidden,
				       a.id AS album_id, a.title AS album_title, a.artist, a.release_year, a.hidden AS album_hidden
				FROM catalog.songs s JOIN catalog.albums a ON a.id = s.album_id
				WHERE s.id = :id""")
			.param("id", id)
			.query((rs, n) -> new SongPageView(rs.getObject("id", UUID.class), rs.getInt("track_number"),
					rs.getString("title"), rs.getObject("duration_seconds", Integer.class), rs.getBoolean("hidden"),
					new AlbumSummary(rs.getObject("album_id", UUID.class), rs.getString("album_title"),
							rs.getString("artist"), rs.getObject("release_year", Integer.class),
							rs.getBoolean("album_hidden"))))
			.optional();
	}
```

`created_at` is mapped by hand, as in `insertAlbum`: the driver hands back `OffsetDateTime`, not `Instant`.

- [ ] **Step 6: Add the reads to `AlbumService`**

In `api/src/main/java/com/musicboxd/api/catalog/AlbumService.java`, add inside the class, after `create`:

```java
	/** The editor's view: every song, hidden ones flagged. */
	@Transactional(readOnly = true)
	AlbumView adminView(UUID id) {
		return view(id, true);
	}

	/** The Album page: visible songs only. A hidden Album still resolves (AD-12). */
	@Transactional(readOnly = true)
	AlbumView publicView(UUID id) {
		return view(id, false);
	}

	@Transactional(readOnly = true)
	SongPageView songPage(UUID id) {
		return albums.findSongPage(id).orElseThrow(SongNotFoundException::new);
	}

	private AlbumView view(UUID id, boolean withHiddenSongs) {
		var album = albums.findAlbum(id).orElseThrow(AlbumNotFoundException::new);
		return new AlbumView(album.id(), album.title(), album.artist(), album.releaseYear(), album.hidden(),
				album.createdAt(), albums.findSongs(id, withHiddenSongs), albums.findGenres(id));
	}
```

- [ ] **Step 7: Add the Staff editor read**

In `api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java`, add imports `org.springframework.web.bind.annotation.GetMapping` and `org.springframework.web.bind.annotation.PathVariable`. Then add after `create`:

```java
	@GetMapping("/{id}")
	@ApiResponse(responseCode = "200", description = "The album with every song, hidden ones included, for the editor")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No album with this id")
	AlbumView get(@PathVariable UUID id) {
		return albums.adminView(id);
	}
```

- [ ] **Step 8: Add the public controller**

`api/src/main/java/com/musicboxd/api/catalog/CatalogController.java`:

```java
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
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `./gradlew test --tests com.musicboxd.api.catalog.CatalogReadTest`
Expected: PASS. `malformedIdIs400` passes because `CatalogExceptionHandler` (a `ResponseEntityExceptionHandler` scoped to this package) renders the `TypeMismatchException`.

- [ ] **Step 10: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/catalog/AlbumNotFoundException.java \
  api/src/main/java/com/musicboxd/api/catalog/SongNotFoundException.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumSummary.java \
  api/src/main/java/com/musicboxd/api/catalog/SongPageView.java \
  api/src/main/java/com/musicboxd/api/catalog/CatalogController.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumService.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java \
  api/src/test/java/com/musicboxd/api/catalog/CatalogFixtures.java \
  api/src/test/java/com/musicboxd/api/catalog/CatalogReadTest.java
git commit -m "Open an Album or Song by id, hidden or not; Staff editor sees hidden songs (MBD-29)"
```

---

### Task 2: Hide and unhide an Album or a Song

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/catalog/VisibilityRequest.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/SongAdminController.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumService.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java`
- Test: `api/src/test/java/com/musicboxd/api/catalog/AlbumVisibilityTest.java`

**Interfaces:**
- Consumes: Task 1's `CatalogFixtures`, `AlbumNotFoundException`, `SongNotFoundException`, and the public/admin GETs.
- Produces: `record VisibilityRequest(@NotNull Boolean hidden)`; `AlbumRepository.setAlbumHidden(UUID, boolean) : int` and `setSongHidden(UUID, boolean) : int` (rows changed); `AlbumService.setAlbumHidden(UUID, boolean)` and `setSongHidden(UUID, boolean)`; routes `PUT /api/v1/admin/catalog/albums/{id}/hidden` and `PUT /api/v1/admin/catalog/songs/{id}/hidden` → 204.

- [ ] **Step 1: Write the failing test**

`api/src/test/java/com/musicboxd/api/catalog/AlbumVisibilityTest.java`:

```java
package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.uuidAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** The catalog table's "Ocultar"/"Reexibir": toggles visibility, never deletes (UX, AD-12). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AlbumVisibilityTest {

	private static final String ALBUM_HIDDEN = "/api/v1/admin/catalog/albums/{id}/hidden";
	private static final String SONG_HIDDEN = "/api/v1/admin/catalog/songs/{id}/hidden";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffHidesAndUnhidesAnAlbum() throws Exception {
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");

		putHidden(ALBUM_HIDDEN, id, Role.STAFF, "{\"hidden\":true}").andExpect(status().isNoContent());
		assertThat(albumHidden(id)).isTrue();
		mockMvc.perform(get("/api/v1/catalog/albums/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(true));

		putHidden(ALBUM_HIDDEN, id, Role.STAFF, "{\"hidden\":false}").andExpect(status().isNoContent());
		assertThat(albumHidden(id)).isFalse();
	}

	@Test
	void hidingTwiceIsFine() throws Exception {
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");
		putHidden(ALBUM_HIDDEN, id, Role.STAFF, "{\"hidden\":true}").andExpect(status().isNoContent());
		putHidden(ALBUM_HIDDEN, id, Role.STAFF, "{\"hidden\":true}").andExpect(status().isNoContent());
		assertThat(albumHidden(id)).isTrue();
	}

	@Test
	void staffHidesASongAndItLeavesTheAlbumPage() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two");
		UUID album = uuidAt(created, "$.id");
		UUID two = uuidAt(created, "$.songs[1].id");

		putHidden(SONG_HIDDEN, two, Role.STAFF, "{\"hidden\":true}").andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/catalog/albums/{id}", album)).andExpect(jsonPath("$.songs", hasSize(1)));
		mockMvc.perform(get("/api/v1/catalog/songs/{id}", two))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(true));
		mockMvc.perform(get("/api/v1/admin/catalog/albums/{id}", album)
				.header("Authorization", StaffTokens.bearer(tokens, Role.STAFF)))
			.andExpect(jsonPath("$.songs[1].hidden").value(true));

		putHidden(SONG_HIDDEN, two, Role.STAFF, "{\"hidden\":false}").andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/catalog/albums/{id}", album)).andExpect(jsonPath("$.songs", hasSize(2)));
	}

	@Test
	void unknownIdsAre404() throws Exception {
		for (String path : List.of(ALBUM_HIDDEN, SONG_HIDDEN)) {
			putHidden(path, UUID.randomUUID(), Role.STAFF, "{\"hidden\":true}")
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
	}

	@Test
	void hiddenIsRequired() throws Exception {
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");
		for (String body : new String[] { "{}", "{\"hidden\":null}" }) {
			putHidden(ALBUM_HIDDEN, id, Role.STAFF, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(albumHidden(id)).isFalse();
	}

	@Test
	void userTokenGets403AndChangesNothing() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		UUID album = uuidAt(created, "$.id");
		UUID song = uuidAt(created, "$.songs[0].id");
		putHidden(ALBUM_HIDDEN, album, Role.USER, "{\"hidden\":true}").andExpect(status().isForbidden());
		putHidden(SONG_HIDDEN, song, Role.USER, "{\"hidden\":true}").andExpect(status().isForbidden());
		assertThat(albumHidden(album)).isFalse();
		assertThat(jdbc.sql("SELECT hidden FROM catalog.songs WHERE id = :id").param("id", song)
			.query(Boolean.class).single()).isFalse();
	}

	private ResultActions putHidden(String path, UUID id, Role role, String body) throws Exception {
		return mockMvc.perform(put(path, id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private boolean albumHidden(UUID id) {
		return jdbc.sql("SELECT hidden FROM catalog.albums WHERE id = :id").param("id", id)
			.query(Boolean.class).single();
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests com.musicboxd.api.catalog.AlbumVisibilityTest`
Expected: FAIL. The STAFF PUTs return 404 or 405 instead of 204, since there is no handler yet. `userTokenGets403AndChangesNothing` already passes, because security rejects the call before routing.

- [ ] **Step 3: Add the request record**

`api/src/main/java/com/musicboxd/api/catalog/VisibilityRequest.java`:

```java
package com.musicboxd.api.catalog;

import jakarta.validation.constraints.NotNull;

/** Body of the hide/unhide toggles. Hiding never deletes (AD-12). */
public record VisibilityRequest(@NotNull Boolean hidden) {
}
```

- [ ] **Step 4: Add the writes to `AlbumRepository`**

Add inside the class:

```java
	/** Returns the rows changed: 0 means no such album. */
	int setAlbumHidden(UUID id, boolean hidden) {
		return jdbc.sql("UPDATE catalog.albums SET hidden = :hidden, updated_at = now() WHERE id = :id")
			.param("id", id)
			.param("hidden", hidden)
			.update();
	}

	/** Returns the rows changed: 0 means no such song. */
	int setSongHidden(UUID id, boolean hidden) {
		return jdbc.sql("UPDATE catalog.songs SET hidden = :hidden, updated_at = now() WHERE id = :id")
			.param("id", id)
			.param("hidden", hidden)
			.update();
	}
```

- [ ] **Step 5: Add the toggles to `AlbumService`**

Add inside the class:

```java
	@Transactional
	void setAlbumHidden(UUID id, boolean hidden) {
		if (albums.setAlbumHidden(id, hidden) == 0) {
			throw new AlbumNotFoundException();
		}
	}

	@Transactional
	void setSongHidden(UUID id, boolean hidden) {
		if (albums.setSongHidden(id, hidden) == 0) {
			throw new SongNotFoundException();
		}
	}
```

- [ ] **Step 6: Add the album toggle route**

In `AlbumAdminController.java`, add the import `org.springframework.web.bind.annotation.PutMapping`, then add:

```java
	@PutMapping("/{id}/hidden")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@ApiResponse(responseCode = "204", description = "Visibility set; a hidden album leaves search but still opens by id")
	@ApiResponse(responseCode = "400", description = "hidden missing")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No album with this id")
	void setHidden(@PathVariable UUID id, @Valid @RequestBody VisibilityRequest request) {
		albums.setAlbumHidden(id, request.hidden());
	}
```

- [ ] **Step 7: Add the song toggle controller**

`api/src/main/java/com/musicboxd/api/catalog/SongAdminController.java`:

```java
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
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew test --tests com.musicboxd.api.catalog.AlbumVisibilityTest --tests com.musicboxd.api.catalog.CatalogReadTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/catalog/VisibilityRequest.java \
  api/src/main/java/com/musicboxd/api/catalog/SongAdminController.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumService.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java \
  api/src/test/java/com/musicboxd/api/catalog/AlbumVisibilityTest.java
git commit -m "Let Staff hide and unhide an Album or Song; hidden items still open by id (MBD-29)"
```

---

### Task 3: Save the whole Album in one atomic edit (metadata, genres, visibility, tracklist)

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/catalog/TracklistMismatchException.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumService.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java`
- Test: `api/src/test/java/com/musicboxd/api/catalog/AlbumEditTest.java`

**Interfaces:**
- Consumes: Task 1's `AlbumService.adminView(UUID)`, `AlbumNotFoundException`, `CatalogFixtures`; MBD-28's `UnknownGenreException(List<UUID>)`, `GenreRepository.findVisible(Collection<UUID>)`, constraint `catalog.songs_album_track_key` (DEFERRABLE INITIALLY IMMEDIATE).
- Produces:
  - `AlbumAdminController.UpdateAlbumRequest(String title, String artist, Integer releaseYear, List<UUID> genreIds, Boolean hidden, List<TrackRequest> songs)` and `AlbumAdminController.TrackRequest(UUID id, String title, Integer durationSeconds, Boolean hidden)`.
  - `AlbumService.update(UUID, UpdateAlbumRequest) : AlbumView`.
  - `GenreRepository.findSelectableFor(UUID albumId, Collection<UUID> ids) : Map<UUID, GenreView>`.
  - `AlbumRepository.lockAlbum(UUID) : boolean`, `songIds(UUID) : List<UUID>`, `updateAlbum(UUID, String, String, Integer, boolean)`, `deferTrackNumberCheck()`, `updateSong(UUID id, UUID albumId, int trackNumber, String title, Integer durationSeconds, boolean hidden)`, `replaceGenres(UUID, List<UUID>)`. `insertSong` gains a final `boolean hidden` parameter.
  - `TracklistMismatchException(List<UUID> missing, List<UUID> unknown, List<UUID> repeated)`, type `urn:musicboxd:problem:tracklist-mismatch`.

- [ ] **Step 1: Write the failing test**

`api/src/test/java/com/musicboxd/api/catalog/AlbumEditTest.java`:

```java
package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.genre;
import static com.musicboxd.api.catalog.CatalogFixtures.uuidAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** The editor's "Salvar": the whole Album state in one call, applied all or nothing. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AlbumEditTest {

	private static final String ALBUM = "/api/v1/admin/catalog/albums/{id}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffEditsTitleArtistYearGenresAndVisibility() throws Exception {
		UUID rock = genre(jdbc, "Rock", false);
		UUID pop = genre(jdbc, "Pop", false);
		String created = createAlbum(mockMvc, tokens, List.of(rock), "Dreams");
		UUID id = uuidAt(created, "$.id");
		UUID dreams = uuidAt(created, "$.songs[0].id");
		String title = "Tusk " + UUID.randomUUID();

		edit(Role.STAFF, id, """
				{"title":"  %s ","artist":" Fleetwood Mac ","releaseYear":1979,"genreIds":["%s"],"hidden":true,
				 "songs":[{"id":"%s","title":"Dreams","hidden":false}]}""".formatted(title, pop, dreams))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.releaseYear").value(1979))
			.andExpect(jsonPath("$.hidden").value(true))
			.andExpect(jsonPath("$.genres", hasSize(1)))
			.andExpect(jsonPath("$.genres[0].id").value(pop.toString()))
			.andExpect(jsonPath("$.songs", hasSize(1)))
			.andExpect(jsonPath("$.songs[0].id").value(dreams.toString()));

		assertThat(albumTitle(id)).isEqualTo(title);
		assertThat(genreIdsOf(id)).containsExactly(pop);
	}

	@Test
	void staffAddsSongsToTheAlbum() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Second Hand News", "Dreams");
		UUID id = uuidAt(created, "$.id");
		UUID a = uuidAt(created, "$.songs[0].id");
		UUID b = uuidAt(created, "$.songs[1].id");

		edit(Role.STAFF, id, body(titleOf(created), "", false,
				track(a, "Second Hand News"), track(b, "Dreams"), newTrack("Go Your Own Way")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs", hasSize(3)))
			.andExpect(jsonPath("$.songs[2].id").isString())
			.andExpect(jsonPath("$.songs[2].trackNumber").value(3))
			.andExpect(jsonPath("$.songs[2].title").value("Go Your Own Way"))
			.andExpect(jsonPath("$.songs[2].hidden").value(false));

		assertThat(songTitles(id)).containsExactly("Second Hand News", "Dreams", "Go Your Own Way");
	}

	@Test
	void reorderingRenumbersTheTracklist() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two", "Three");
		UUID id = uuidAt(created, "$.id");
		UUID one = uuidAt(created, "$.songs[0].id");
		UUID two = uuidAt(created, "$.songs[1].id");
		UUID three = uuidAt(created, "$.songs[2].id");

		// A new song at the top and the old last track moved up: every number changes hands.
		edit(Role.STAFF, id, body(titleOf(created), "", false,
				newTrack("Zero"), track(three, "Three"), track(one, "One"), track(two, "Two")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs[1].id").value(three.toString()))
			.andExpect(jsonPath("$.songs[1].trackNumber").value(2));

		assertThat(songTitles(id)).containsExactly("Zero", "Three", "One", "Two");
	}

	@Test
	void staffEditsAndHidesASongInTheTracklist() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams", "Songbird");
		UUID id = uuidAt(created, "$.id");
		UUID dreams = uuidAt(created, "$.songs[0].id");
		UUID songbird = uuidAt(created, "$.songs[1].id");

		edit(Role.STAFF, id, body(titleOf(created), "", false,
				"{\"id\":\"%s\",\"title\":\"Dreams (Remastered)\",\"durationSeconds\":257,\"hidden\":false}".formatted(dreams),
				"{\"id\":\"%s\",\"title\":\"Songbird\",\"hidden\":true}".formatted(songbird)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs[0].title").value("Dreams (Remastered)"))
			.andExpect(jsonPath("$.songs[0].durationSeconds").value(257))
			.andExpect(jsonPath("$.songs[1].hidden").value(true));

		mockMvc.perform(get("/api/v1/catalog/albums/{id}", id))
			.andExpect(jsonPath("$.songs", hasSize(1)))
			.andExpect(jsonPath("$.songs[0].id").value(dreams.toString()));
	}

	@Test
	void leavingOutASongIs422AndChangesNothing() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two");
		UUID id = uuidAt(created, "$.id");
		UUID one = uuidAt(created, "$.songs[0].id");
		UUID two = uuidAt(created, "$.songs[1].id");

		edit(Role.STAFF, id, body("Renamed " + UUID.randomUUID(), "", false, track(one, "One")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:tracklist-mismatch"))
			.andExpect(jsonPath("$.missingSongIds", hasSize(1)))
			.andExpect(jsonPath("$.missingSongIds[0]").value(two.toString()))
			.andExpect(jsonPath("$.unknownSongIds", hasSize(0)))
			.andExpect(jsonPath("$.repeatedSongIds", hasSize(0)));

		assertThat(albumTitle(id)).isEqualTo(titleOf(created));
		assertThat(songTitles(id)).containsExactly("One", "Two");
	}

	@Test
	void aSongOfAnotherAlbumIs422AndStaysWhereItIs() throws Exception {
		String mine = createAlbum(mockMvc, tokens, List.of(), "One");
		String theirs = createAlbum(mockMvc, tokens, List.of(), "Elsewhere");
		UUID id = uuidAt(mine, "$.id");
		UUID one = uuidAt(mine, "$.songs[0].id");
		UUID other = uuidAt(theirs, "$.songs[0].id");

		edit(Role.STAFF, id, body(titleOf(mine), "", false, track(one, "One"), track(other, "Elsewhere")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.unknownSongIds[0]").value(other.toString()));

		assertThat(jdbc.sql("SELECT album_id FROM catalog.songs WHERE id = :id").param("id", other)
			.query(UUID.class).single()).isEqualTo(uuidAt(theirs, "$.id"));
		assertThat(songTitles(id)).containsExactly("One");
	}

	@Test
	void aRepeatedSongIs422() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One");
		UUID one = uuidAt(created, "$.songs[0].id");
		edit(Role.STAFF, uuidAt(created, "$.id"), body(titleOf(created), "", false, track(one, "One"), track(one, "One")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.repeatedSongIds[0]").value(one.toString()));
	}

	@Test
	void aGenreTheAlbumAlreadyHasStaysAfterItIsHidden() throws Exception {
		UUID kept = genre(jdbc, "Psicodelia", false);
		String created = createAlbum(mockMvc, tokens, List.of(kept), "Dreams");
		jdbc.sql("UPDATE catalog.genres SET hidden = true WHERE id = :id").param("id", kept).update();

		edit(Role.STAFF, uuidAt(created, "$.id"), body(titleOf(created), ids(kept), false,
				track(uuidAt(created, "$.songs[0].id"), "Dreams")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.genres[0].id").value(kept.toString()))
			.andExpect(jsonPath("$.genres[0].hidden").value(true));
	}

	@Test
	void aNewlyPickedHiddenGenreIs422() throws Exception {
		UUID hidden = genre(jdbc, "Vaporwave", true);
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		UUID id = uuidAt(created, "$.id");

		edit(Role.STAFF, id, body(titleOf(created), ids(hidden), false, track(uuidAt(created, "$.songs[0].id"), "Dreams")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:unknown-genre"))
			.andExpect(jsonPath("$.genreIds[0]").value(hidden.toString()));
		assertThat(genreIdsOf(id)).isEmpty();
	}

	@Test
	void unknownAlbumIs404() throws Exception {
		edit(Role.STAFF, UUID.randomUUID(), body("Ghost", "", false, newTrack("Boo")))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void invalidEditsAre400AndChangeNothing() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One");
		UUID id = uuidAt(created, "$.id");
		UUID one = uuidAt(created, "$.songs[0].id");
		String t = track(one, "One");
		int tooLate = Year.now(ZoneOffset.UTC).getValue() + 2;
		String[] bodies = {
			body("   ", "", false, t),
			"{\"title\":\"T\",\"artist\":\"A\",\"songs\":[" + t + "]}",
			body("T", "", false),
			"{\"title\":\"T\",\"artist\":\"A\",\"hidden\":false,\"songs\":[{\"id\":\"" + one + "\",\"title\":\"One\"}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"hidden\":false,\"songs\":[{\"id\":\"" + one + "\",\"title\":\" \",\"hidden\":false}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"hidden\":false,\"releaseYear\":" + tooLate + ",\"songs\":[" + t + "]}",
		};
		for (String b : bodies) {
			edit(Role.STAFF, id, b)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(albumTitle(id)).isEqualTo(titleOf(created));
	}

	@Test
	void userTokenGets403AndChangesNothing() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One");
		UUID id = uuidAt(created, "$.id");
		edit(Role.USER, id, body("Hijacked", "", true, track(uuidAt(created, "$.songs[0].id"), "One")))
			.andExpect(status().isForbidden());
		assertThat(albumTitle(id)).isEqualTo(titleOf(created));
	}

	private ResultActions edit(Role role, UUID id, String body) throws Exception {
		return mockMvc.perform(put(ALBUM, id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	/** {@code genres} and {@code tracks} are JSON fragments: see {@link #ids}, {@link #track}, {@link #newTrack}. */
	private static String body(String title, String genres, boolean hidden, String... tracks) {
		return "{\"title\":\"%s\",\"artist\":\"Fleetwood Mac\",\"releaseYear\":1977,\"genreIds\":[%s],\"hidden\":%s,\"songs\":[%s]}"
			.formatted(title, genres, hidden, String.join(",", tracks));
	}

	private static String ids(UUID... ids) {
		return Stream.of(ids).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
	}

	private static String track(UUID id, String title) {
		return "{\"id\":\"%s\",\"title\":\"%s\",\"hidden\":false}".formatted(id, title);
	}

	private static String newTrack(String title) {
		return "{\"title\":\"%s\",\"hidden\":false}".formatted(title);
	}

	private static String titleOf(String albumJson) {
		return JsonPath.read(albumJson, "$.title");
	}

	private String albumTitle(UUID id) {
		return jdbc.sql("SELECT title FROM catalog.albums WHERE id = :id").param("id", id).query(String.class).single();
	}

	private List<String> songTitles(UUID albumId) {
		return jdbc.sql("SELECT title FROM catalog.songs WHERE album_id = :id ORDER BY track_number")
			.param("id", albumId).query(String.class).list();
	}

	private List<UUID> genreIdsOf(UUID albumId) {
		return jdbc.sql("SELECT genre_id FROM catalog.album_genres WHERE album_id = :id")
			.param("id", albumId).query(UUID.class).list();
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests com.musicboxd.api.catalog.AlbumEditTest`
Expected: FAIL. `PUT /api/v1/admin/catalog/albums/{id}` has no handler (Task 2 only mapped `/{id}/hidden`), so STAFF calls get 405 where 200/422/400 are expected.

- [ ] **Step 3: Add the tracklist exception**

`api/src/main/java/com/musicboxd/api/catalog/TracklistMismatchException.java`:

```java
package com.musicboxd.api.catalog;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An edit's tracklist must list every song of the album exactly once: leaving one out would delete it and
 * naming another album's would move it, and neither is supported (AD-12). The three id lists let the SPA
 * (MBD-53) reload the editor.
 */
public class TracklistMismatchException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:tracklist-mismatch");

	public TracklistMismatchException(List<UUID> missing, List<UUID> unknown, List<UUID> repeated) {
		super(HttpStatus.UNPROCESSABLE_CONTENT, problem(missing, unknown, repeated), null);
	}

	private static ProblemDetail problem(List<UUID> missing, List<UUID> unknown, List<UUID> repeated) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
				"The tracklist must list every song of this album exactly once");
		problem.setType(TYPE);
		problem.setTitle("Tracklist mismatch");
		problem.setProperty("missingSongIds", missing);
		problem.setProperty("unknownSongIds", unknown);
		problem.setProperty("repeatedSongIds", repeated);
		return problem;
	}
}
```

- [ ] **Step 4: Let an edit keep genres the album already has**

In `api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java`, add after `findVisible`:

```java
	/** For an edit: visible genres, plus hidden ones this album already carries (hiding keeps existing uses, UX "Lista de generos"). */
	Map<UUID, GenreView> findSelectableFor(UUID albumId, Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("""
				SELECT g.id, g.name, g.hidden FROM catalog.genres g
				WHERE g.id IN (:ids)
				  AND (NOT g.hidden OR EXISTS (
				      SELECT 1 FROM catalog.album_genres ag WHERE ag.album_id = :albumId AND ag.genre_id = g.id))""")
			.param("ids", ids)
			.param("albumId", albumId)
			.query(GenreView.class)
			.list()
			.stream()
			.collect(Collectors.toMap(GenreView::id, Function.identity()));
	}
```

- [ ] **Step 5: Add the edit writes to `AlbumRepository`**

Change `insertSong` to take `hidden` (an edit may add a song that is hidden from the start):

```java
	void insertSong(UUID id, UUID albumId, int trackNumber, String title, Integer durationSeconds, boolean hidden) {
		jdbc.sql("""
				INSERT INTO catalog.songs (id, album_id, track_number, title, duration_seconds, hidden)
				VALUES (:id, :albumId, :trackNumber, :title, :durationSeconds, :hidden)""")
			.param("id", id)
			.param("albumId", albumId)
			.param("trackNumber", trackNumber)
			.param("title", title)
			.param("durationSeconds", durationSeconds, Types.INTEGER)
			.param("hidden", hidden)
			.update();
	}
```

Add inside the class:

```java
	/** Serializes concurrent edits of one album; false means no such album. */
	boolean lockAlbum(UUID id) {
		return jdbc.sql("SELECT id FROM catalog.albums WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(UUID.class)
			.optional()
			.isPresent();
	}

	List<UUID> songIds(UUID albumId) {
		return jdbc.sql("SELECT id FROM catalog.songs WHERE album_id = :albumId ORDER BY track_number")
			.param("albumId", albumId)
			.query(UUID.class)
			.list();
	}

	void updateAlbum(UUID id, String title, String artist, Integer releaseYear, boolean hidden) {
		jdbc.sql("""
				UPDATE catalog.albums
				SET title = :title, artist = :artist, release_year = :releaseYear, hidden = :hidden, updated_at = now()
				WHERE id = :id""")
			.param("id", id)
			.param("title", title)
			.param("artist", artist)
			.param("releaseYear", releaseYear, Types.SMALLINT)
			.param("hidden", hidden)
			.update();
	}

	/** Lets one transaction renumber a tracklist: (album_id, track_number) uniqueness is checked at commit. */
	void deferTrackNumberCheck() {
		jdbc.sql("SET CONSTRAINTS catalog.songs_album_track_key DEFERRED").update();
	}

	/** Filters on the album too: a song never changes album (AD-12). */
	void updateSong(UUID id, UUID albumId, int trackNumber, String title, Integer durationSeconds, boolean hidden) {
		jdbc.sql("""
				UPDATE catalog.songs
				SET track_number = :trackNumber, title = :title, duration_seconds = :durationSeconds,
				    hidden = :hidden, updated_at = now()
				WHERE id = :id AND album_id = :albumId""")
			.param("id", id)
			.param("albumId", albumId)
			.param("trackNumber", trackNumber)
			.param("title", title)
			.param("durationSeconds", durationSeconds, Types.INTEGER)
			.param("hidden", hidden)
			.update();
	}

	/** Removes link rows only; genres themselves are never deleted (AD-12). */
	void replaceGenres(UUID albumId, List<UUID> genreIds) {
		jdbc.sql("DELETE FROM catalog.album_genres WHERE album_id = :albumId").param("albumId", albumId).update();
		genreIds.forEach(genreId -> linkGenre(albumId, genreId));
	}
```

- [ ] **Step 6: Add the request records and the route**

In `AlbumAdminController.java`, add after `SongRequest`:

```java
	/** The editor's whole state (UX: "Alterações só valem depois de salvar"). Songs are listed in track order. */
	public record UpdateAlbumRequest(
			@NotBlank @Size(max = 200) String title,
			@NotBlank @Size(max = 200) String artist,
			// The upper bound (next year) needs the clock, so AlbumService checks it.
			@Min(1000) Integer releaseYear,
			@Size(max = 10) List<@NotNull UUID> genreIds,
			@NotNull Boolean hidden,
			@NotNull @Size(min = 1, max = 200) List<@NotNull @Valid TrackRequest> songs) {

		public UpdateAlbumRequest {
			title = title == null ? null : title.strip();
			artist = artist == null ? null : artist.strip();
			genreIds = genreIds == null ? List.of() : genreIds.stream().distinct().toList();
		}
	}

	/** With {@code id}: an existing song of this album. Without: a new song, assigned to this album for life (AD-12). */
	public record TrackRequest(
			UUID id,
			@NotBlank @Size(max = 200) String title,
			@Min(1) @Max(86400) Integer durationSeconds,
			@NotNull Boolean hidden) {

		public TrackRequest {
			title = title == null ? null : title.strip();
		}
	}
```

And add the route after `get`:

```java
	@PutMapping("/{id}")
	@ApiResponse(responseCode = "200", description = "Album saved: metadata, genres, visibility and tracklist, all or nothing")
	@ApiResponse(responseCode = "400", description = "Invalid title, artist, year, hidden, songs or genre list")
	@ApiResponse(responseCode = "403", description = "Caller is not Staff")
	@ApiResponse(responseCode = "404", description = "No album with this id")
	@ApiResponse(responseCode = "422", description = "A new genre does not exist or is hidden (type urn:musicboxd:problem:unknown-genre), or the tracklist leaves out, repeats or borrows a song (type urn:musicboxd:problem:tracklist-mismatch)")
	AlbumView update(@PathVariable UUID id, @Valid @RequestBody UpdateAlbumRequest request) {
		return albums.update(id, request);
	}
```

- [ ] **Step 7: Implement `AlbumService.update` and share the genre check**

In `AlbumService.java`:

Add imports `java.util.LinkedHashSet`, `java.util.Set` and `com.musicboxd.api.catalog.AlbumAdminController.TrackRequest`, `com.musicboxd.api.catalog.AlbumAdminController.UpdateAlbumRequest`.

In `create`, replace `List<GenreView> picked = visibleGenres(request.genreIds());` with:

```java
		List<GenreView> picked = requireAll(request.genreIds(), genres.findVisible(request.genreIds()));
```

and pass `false` as the new last argument of `albums.insertSong(...)`.

Replace the private `visibleGenres` method with:

```java
	/** Checked before any write. Unknown and unselectable ids are refused together, in request order. */
	private static List<GenreView> requireAll(List<UUID> ids, Map<UUID, GenreView> found) {
		List<UUID> missing = ids.stream().filter(id -> !found.containsKey(id)).toList();
		if (!missing.isEmpty()) {
			throw new UnknownGenreException(missing);
		}
		return ids.stream().map(found::get).toList();
	}
```

Add after `create`:

```java
	/**
	 * One transaction, under a row lock on the album: every check runs before any write, then the album,
	 * its tracklist (numbered by position) and its genre links are rewritten.
	 */
	@Transactional
	AlbumView update(UUID id, UpdateAlbumRequest request) {
		requirePlausibleYear(request.releaseYear());
		if (!albums.lockAlbum(id)) {
			throw new AlbumNotFoundException();
		}
		List<GenreView> picked = requireAll(request.genreIds(), genres.findSelectableFor(id, request.genreIds()));
		requireSameSongs(id, request.songs());

		albums.updateAlbum(id, request.title(), request.artist(), request.releaseYear(), request.hidden());
		albums.deferTrackNumberCheck();
		for (int i = 0; i < request.songs().size(); i++) {
			TrackRequest track = request.songs().get(i);
			if (track.id() == null) {
				albums.insertSong(UUID.randomUUID(), id, i + 1, track.title(), track.durationSeconds(), track.hidden());
			}
			else {
				albums.updateSong(track.id(), id, i + 1, track.title(), track.durationSeconds(), track.hidden());
			}
		}
		albums.replaceGenres(id, picked.stream().map(GenreView::id).toList());
		return view(id, true);
	}

	/** Every existing song exactly once: nothing is deleted or moved (AD-12). */
	private void requireSameSongs(UUID albumId, List<TrackRequest> tracks) {
		Set<UUID> existing = new LinkedHashSet<>(albums.songIds(albumId));
		Set<UUID> sent = new LinkedHashSet<>();
		Set<UUID> repeated = new LinkedHashSet<>();
		for (TrackRequest track : tracks) {
			if (track.id() != null && !sent.add(track.id())) {
				repeated.add(track.id());
			}
		}
		List<UUID> unknown = sent.stream().filter(songId -> !existing.contains(songId)).toList();
		List<UUID> missing = existing.stream().filter(songId -> !sent.contains(songId)).toList();
		if (!missing.isEmpty() || !unknown.isEmpty() || !repeated.isEmpty()) {
			throw new TracklistMismatchException(missing, unknown, List.copyOf(repeated));
		}
	}
```

Notes for the implementer:
- The order is deliberate: year, lock (404), genres (422), tracklist (422), then writes. Every refusal happens before the first write, so the `@Transactional` rollback is a backstop rather than the mechanism.
- `deferTrackNumberCheck` must run inside the transaction and before the first song write. Without it, the reorder test fails on `songs_album_track_key` while the numbers change hands.
- Once `requireSameSongs` passes, the existing songs get exactly the numbers 1..n alongside the new ones, so the deferred check at commit always holds.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.musicboxd.api.catalog.*'`
Expected: PASS, including MBD-28's `AlbumAdminTest`, which exercises the `create` refactor.

- [ ] **Step 9: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/catalog/TracklistMismatchException.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumService.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java \
  api/src/test/java/com/musicboxd/api/catalog/AlbumEditTest.java
git commit -m "Let Staff save an Album's details, genres, visibility and tracklist in one call; songs are added, never removed or moved (MBD-29)"
```

---

### Task 4: Acceptance, contract, and the deferred admin list

**Files:**
- Create: `api/src/test/java/com/musicboxd/api/catalog/CatalogEditAcceptanceTest.java`
- Modify: `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`
- Modify: `_bmad-output/implementation-artifacts/deferred-work.md`

**Interfaces:**
- Consumes: every route from Tasks 1–3, plus `CatalogFixtures`.
- Produces: no production code. This task only proves the story's AC end to end and pins the OpenAPI contract.

- [ ] **Step 1: Write the acceptance test**

`api/src/test/java/com/musicboxd/api/catalog/CatalogEditAcceptanceTest.java`:

```java
package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.uuidAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/**
 * MBD-29 Verify: Staff edits an Album, adds a Song to it, and hides the Album; the hidden Album
 * disappears from search but still opens at its direct link; no delete endpoint exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogEditAcceptanceTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffEditsAddsASongAndHidesTheAlbumWhichStillOpensByLink() throws Exception {
		String staff = StaffTokens.bearer(tokens, Role.STAFF);
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		UUID id = uuidAt(created, "$.id");
		UUID dreams = uuidAt(created, "$.songs[0].id");
		String title = "Rumours (Deluxe) " + UUID.randomUUID();

		// Edit the album and add a song to it, in one save.
		String saved = mockMvc.perform(put("/api/v1/admin/catalog/albums/{id}", id)
				.header("Authorization", staff)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"%s","artist":"Fleetwood Mac","releaseYear":1977,"genreIds":[],"hidden":false,
						 "songs":[{"id":"%s","title":"Dreams","hidden":false},
						          {"title":"Silver Springs","durationSeconds":287,"hidden":false}]}"""
					.formatted(title, dreams)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andReturn().getResponse().getContentAsString();
		UUID silverSprings = uuidAt(saved, "$.songs[1].id");

		// Hide it, as the catalog table's "Ocultar" does.
		mockMvc.perform(put("/api/v1/admin/catalog/albums/{id}/hidden", id)
				.header("Authorization", staff)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"hidden\":true}"))
			.andExpect(status().isNoContent());

		// Search (MBD-34) leaves out rows with this flag; it is stored here.
		assertThat(jdbc.sql("SELECT hidden FROM catalog.albums WHERE id = :id").param("id", id)
			.query(Boolean.class).single()).isTrue();

		// The direct links keep working for anyone, with no token.
		mockMvc.perform(get("/api/v1/catalog/albums/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.hidden").value(true))
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andExpect(jsonPath("$.songs[1].title").value("Silver Springs"));
		mockMvc.perform(get("/api/v1/catalog/songs/{id}", silverSprings))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.album.id").value(id.toString()))
			.andExpect(jsonPath("$.album.hidden").value(true));
	}

	@Test
	void nothingInTheCatalogCanBeDeleted() throws Exception {
		String staff = StaffTokens.bearer(tokens, Role.STAFF);
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		UUID id = uuidAt(created, "$.id");
		UUID song = uuidAt(created, "$.songs[0].id");

		for (String path : List.of("/api/v1/admin/catalog/albums/" + id, "/api/v1/admin/catalog/songs/" + song,
				"/api/v1/admin/catalog/genres/" + UUID.randomUUID(), "/api/v1/catalog/albums/" + id,
				"/api/v1/catalog/songs/" + song)) {
			int status = mockMvc.perform(delete(path).header("Authorization", staff)).andReturn().getResponse().getStatus();
			assertThat(status).as("DELETE %s", path).isIn(404, 405);
		}
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.songs WHERE id = :id AND album_id = :album")
			.param("id", song).param("album", id).query(Long.class).single()).isEqualTo(1);
	}
}
```

- [ ] **Step 2: Extend the contract test**

Replace the test method in `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java` with:

```java
	@Test
	void catalogRoutesAreInTheContractAndNothingDeletes() throws Exception {
		String docs = mockMvc.perform(get("/api/v1/api-docs")).andReturn().getResponse().getContentAsString();
		Map<String, Map<String, Object>> paths = JsonPath.read(docs, "$.paths");

		assertThat(paths.get("/api/v1/admin/catalog/albums")).containsKey("post");
		assertThat(paths.get("/api/v1/admin/catalog/albums/{id}")).containsKeys("get", "put");
		assertThat(paths.get("/api/v1/admin/catalog/albums/{id}/hidden")).containsKey("put");
		assertThat(paths.get("/api/v1/admin/catalog/songs/{id}/hidden")).containsKey("put");
		assertThat(paths.get("/api/v1/admin/catalog/genres")).containsKey("post");
		assertThat(paths.get("/api/v1/catalog/albums/{id}")).containsKey("get");
		assertThat(paths.get("/api/v1/catalog/songs/{id}")).containsKey("get");
		paths.forEach((path, operations) -> {
			if (path.contains("/catalog") || path.contains("/albums") || path.contains("/songs") || path.contains("/genres")) {
				assertThat(operations).as("DELETE on %s (AD-12)", path).doesNotContainKey("delete");
			}
		});
	}
```

- [ ] **Step 3: Run the new and changed tests**

Run: `./gradlew test --tests com.musicboxd.api.catalog.CatalogEditAcceptanceTest --tests com.musicboxd.api.catalog.CatalogContractTest`
Expected: PASS, because the production code is already in place from Tasks 1–3. If `nothingInTheCatalogCanBeDeleted` reports a status other than 404/405 (for example 403 or 500), stop and investigate: it means a DELETE reached something it should not.

- [ ] **Step 4: Log the deferred admin list endpoint**

Append to `_bmad-output/implementation-artifacts/deferred-work.md`:

```markdown
- source_plan: `docs/superpowers/plans/2026-10-13-mbd-29-edit-and-hide-albums-and-songs.md`
  summary: Admin catalog list endpoint (GET /api/v1/admin/catalog/albums with a text filter, hidden albums included) for the admin "Catálogo" table ("Filtrar catálogo", "8 de 214 álbuns").
  evidence: Not in MBD-29's acceptance criteria; left out by the user's choice on 2026-10-13. The admin SPA cannot list albums without it.
  status: open -> MBD-53 (admin SPA)
```

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew test`
Expected: PASS, including `AdminRouteProtectionTest` (unchanged: its honest-404 probe is `/api/v1/admin/reviews/7`) and `OpenApiExportBootTest` (nothing new queries at startup).

- [ ] **Step 6: Commit**

```bash
git add api/src/test/java/com/musicboxd/api/catalog/CatalogEditAcceptanceTest.java \
  api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java \
  _bmad-output/implementation-artifacts/deferred-work.md
git commit -m "Prove MBD-29 end to end: edit, add a song, hide, still opens by link, nothing deletes (MBD-29)"
```
