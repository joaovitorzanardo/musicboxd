# MBD-28 Tracer Bullet: Staff Creates an Album Behind /admin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The `catalog` module exists: its Postgres schema (albums, songs, genres, album↔genre links) and two Staff-only endpoints. `POST /api/v1/admin/catalog/genres` creates a genre; `POST /api/v1/admin/catalog/albums` creates an Album with its tracklist and genres in one call. A STAFF token gets 201; a USER token gets 403.

**Architecture:** A new package `com.musicboxd.api.catalog`, shaped like `profiles`: its own Flyway instance over the `catalog` schema (`ModuleFlyway`), package-private `JdbcClient` repositories, `@Transactional` services, and controllers under `/api/v1/admin/catalog/**`. MBD-21's `SecurityConfig` already requires `STAFF` on `/api/v1/admin/**` for every method, so no security change is needed. Errors are RFC 9457 Problem Details through a module-scoped `ResponseEntityExceptionHandler`, as in `accounts`.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC 7, Spring Security 7), Flyway (one instance per module schema), Spring JDBC `JdbcClient`, Jakarta Validation, PostgreSQL 18, JUnit 5 / MockMvc / AssertJ / JsonPath, Testcontainers.

**Spec:** Jira MBD-28 / `_bmad-output/initiative-musicboxd/epic-catalogo-busca/story-tracer-bullet-staff-creates-an-album-behind-admin.md` ("A STAFF token creates an Album; a USER token gets 403 on the same endpoint"). Epic: `epic-catalogo-busca.md`. Architecture: `ARCHITECTURE-SPINE.md` AD-1 (module owns its schema), AD-7 (`/api/v1/admin/**` requires STAFF), AD-12 (catalog entities never deleted; songs never move between albums), AD-13 (genre list lives in `catalog`; Staff endpoints at `/api/v1/admin/<module>/**`). UX: `EXPERIENCE.md` flow "Cadastrar e ocultar album (Staff)" and `mockups/admin.html` (title, artist, year, genres from the controlled list, tracks). The design was approved in chat on 2026-10-13. Its decisions are below.

**Branch:** work on the current branch `joaovitorzanardo/mbd-28-tracer-bullet-staff-creates-an-album-behi` (off `main`, which has MBD-21). Commit messages end with `(MBD-28)` and the `Co-Authored-By` trailer. Run Gradle from `api/`. Docker must be running for Testcontainers. Never `git add -A`; add files by path.

## Decisions (approved 2026-10-13)

| Question | Decision |
|---|---|
| Schema | `catalog` schema, `V1__create_catalog.sql`: `albums`, `songs`, `genres`, `album_genres`. No `ON DELETE` anywhere (AD-12). No cover column (MBD-32), no `pg_trgm` index (search story). |
| Album fields | `title`, `artist` (plain text; there is no Artist entity in the SPEC), `release_year` (optional), `hidden`. |
| Song fields | `album_id` (NOT NULL FK, never changes: AD-12), `track_number`, `title`, `duration_seconds` (optional), `hidden`. `(album_id, track_number)` unique, `DEFERRABLE` so MBD-29 can reorder inside one transaction. |
| Genre fields | `name` unique ignoring case, `hidden`. |
| Genre endpoint | `POST /api/v1/admin/catalog/genres {name}` → `201 {id, name, hidden:false}`. Name stripped, 1–50 chars. Duplicate (any case) → `409`, type `urn:musicboxd:problem:genre-taken`. Rename/hide stay in MBD-30. |
| Album endpoint | `POST /api/v1/admin/catalog/albums {title, artist, releaseYear?, genreIds?, songs}` → `201` with the album, its songs and its genres. |
| Album validation | `title`, `artist`: stripped, 1–200 chars. `releaseYear`: 1000 to next year (UTC). `genreIds`: ≤ 10, duplicates collapsed, request order kept. `songs`: 1–200 (an Album has one or more Songs, glossary); each `{title 1–200, durationSeconds? 1–86400}`. `trackNumber` = position in the list (1..n); the client never sends it. Bean-validation failures → `400` Problem Details. |
| Unknown / hidden genre | `422`, type `urn:musicboxd:problem:unknown-genre`, property `genreIds` listing the offending ids. Checked before any insert. |
| Atomicity | Album, songs and genre links are written in one transaction; any failure writes nothing. |
| Response | `{id, title, artist, releaseYear, hidden, createdAt, songs:[{id, trackNumber, title, durationSeconds, hidden}], genres:[{id, name, hidden}]}`. No `Location` header (no GET route yet). |
| No delete | No DELETE route in `catalog` (AD-12); a contract test asserts it. |
| SPA | Out of scope (MBD-53). |

## Global Constraints

- `/api/v1/admin/**` requires `STAFF` for every HTTP method (AD-7); this story adds no security rule and must not move the admin matcher.
- `catalog` is the only writer of the `catalog` schema; no cross-schema foreign keys (AD-1, AD-12).
- Migrations live in `api/src/main/resources/db/migration/catalog/`; this is `V1`. Never edit an applied `V` file.
- No DELETE operation on albums, songs or genres anywhere in the API (AD-12).
- Ids are UUIDs generated in Java (`UUID.randomUUID()`), as in `accounts`/`profiles`; timestamps are UTC ISO-8601 on the wire.
- Errors are `application/problem+json` (RFC 9457), with `urn:musicboxd:problem:<kebab>` types for the ones a client branches on.
- Repositories stay package-private. Java sources use tabs. Routes are documented by springdoc with `@ApiResponse`.
- Tests share one Postgres container across contexts: use unique names (random suffix) for genres and titles you assert on.

## Review Focus

- **A USER (or anonymous) caller reaching either catalog route.** Must be 403 (401 without a token) and must write nothing. (Task 2: `userTokenCannotCreateAGenre`; Task 3: `userTokenGets403AndWritesNothing`, `anonymousCallerGets401`.)
- **A hidden genre picked for a new album.** Must be refused like an unknown one: hidden genres leave the selectable list (UX "Lista de generos"). (Task 3: `hiddenGenreIsRefusedWith422`.)
- **A half-written album after a failure.** A bad genre after songs are valid must not leave an album row behind. (Task 3: `unknownGenreIsRefusedWith422AndWritesNothing`.)
- **The same genre id twice in the request.** Must link once, not fail on the `album_genres` primary key with a 500. (Task 3: `repeatedGenreIdIsLinkedOnce`.)
- **Whitespace-only and padded input.** `"   "` is a blank title (400); `"  Rumours  "` is stored as `Rumours`; `" rock "` clashes with `Rock` (409). (Task 2: `genreNameIsStrippedAndClashesIgnoringCase`; Task 3: `blankOrMissingFieldsAre400`, `titlesAreStripped`.)

---

## File Structure

**Schema (Task 1)**
- Create `api/src/main/resources/db/migration/catalog/V1__create_catalog.sql`: the four tables.
- Create `api/src/main/java/com/musicboxd/api/catalog/CatalogConfig.java`: the module's Flyway bean.
- Create `api/src/test/java/com/musicboxd/api/catalog/CatalogSchemaTest.java`.

**Genres (Task 2)**
- Create `api/src/main/java/com/musicboxd/api/catalog/CatalogExceptionHandler.java`: Problem Details for the module.
- Create `api/src/main/java/com/musicboxd/api/catalog/GenreView.java`: `record GenreView(UUID id, String name, boolean hidden)`.
- Create `api/src/main/java/com/musicboxd/api/catalog/GenreTakenException.java`.
- Create `api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java`.
- Create `api/src/main/java/com/musicboxd/api/catalog/GenreService.java`.
- Create `api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java`.
- Create `api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java`.
- Create `api/src/test/java/com/musicboxd/api/catalog/StaffTokens.java`: test helper for bearer headers.

**Albums (Task 3)**
- Create `api/src/main/java/com/musicboxd/api/catalog/AlbumView.java`, `SongView.java`.
- Create `api/src/main/java/com/musicboxd/api/catalog/UnknownGenreException.java`.
- Create `api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java`.
- Create `api/src/main/java/com/musicboxd/api/catalog/AlbumService.java`.
- Create `api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java`.
- Create `api/src/test/java/com/musicboxd/api/catalog/AlbumAdminTest.java`.
- Create `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`: routes in the OpenAPI contract, no DELETE.
- Modify `api/src/test/java/com/musicboxd/api/security/AdminRouteProtectionTest.java`: the "honest 404" probe moves off the now-real albums path.

---

### Task 1: The `catalog` schema

**Files:**
- Create: `api/src/main/resources/db/migration/catalog/V1__create_catalog.sql`
- Create: `api/src/main/java/com/musicboxd/api/catalog/CatalogConfig.java`
- Test: `api/src/test/java/com/musicboxd/api/catalog/CatalogSchemaTest.java`

**Interfaces:**
- Consumes: `com.musicboxd.api.db.ModuleFlyway.forSchema(DataSource, String)`.
- Produces: tables `catalog.albums`, `catalog.songs`, `catalog.genres`, `catalog.album_genres`; unique index `genres_name_key` on `lower(name)`; constraint `songs_album_track_key`.

- [ ] **Step 1: Write the failing test**

```java
package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.musicboxd.api.TestcontainersConfiguration;

/** The HTTP edge validates first (Tasks 2-3); these constraints are the backstop. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogSchemaTest {

	@Autowired
	private JdbcClient jdbc;

	@Test
	void albumWithSongsIsStoredVisibleByDefault() {
		UUID album = insertAlbum("Rumours");
		insertSong(album, 1);
		insertSong(album, 2);
		assertThat(jdbc.sql("SELECT hidden FROM catalog.albums WHERE id = :id").param("id", album)
			.query(Boolean.class).single()).isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.songs WHERE album_id = :id").param("id", album)
			.query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void trackNumbersAreUniquePerAlbum() {
		UUID album = insertAlbum("Rumours");
		insertSong(album, 1);
		assertThatThrownBy(() -> insertSong(album, 1)).isInstanceOf(DuplicateKeyException.class);
		// Another album may reuse the number.
		insertSong(insertAlbum("Tusk"), 1);
	}

	@Test
	void songNeedsAnExistingAlbum() {
		assertThatThrownBy(() -> insertSong(UUID.randomUUID(), 1))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingAnAlbumNeverCascadesToItsSongs() {
		// AD-12: nothing deletes catalog rows; if something tried, the songs would block it.
		UUID album = insertAlbum("Rumours");
		insertSong(album, 1);
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM catalog.albums WHERE id = :id").param("id", album).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void genreNamesAreUniqueIgnoringCase() {
		String name = "Rock " + UUID.randomUUID();
		insertGenre(name);
		assertThatThrownBy(() -> insertGenre(name.toUpperCase())).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void albumGenreLinkNeedsBothSides() {
		UUID album = insertAlbum("Rumours");
		UUID genre = insertGenre("Rock " + UUID.randomUUID());
		jdbc.sql("INSERT INTO catalog.album_genres (album_id, genre_id) VALUES (:a, :g)")
			.param("a", album).param("g", genre).update();
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO catalog.album_genres (album_id, genre_id) VALUES (:a, :g)")
			.param("a", album).param("g", UUID.randomUUID()).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void blankTitlesAndImplausibleValuesAreRejected() {
		assertThatThrownBy(() -> insertAlbum("   ")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO catalog.albums (id, title, artist, release_year) VALUES (:id, 'X', 'Y', 999)""")
			.param("id", UUID.randomUUID()).update()).isInstanceOf(DataIntegrityViolationException.class);
		UUID album = insertAlbum("Rumours");
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO catalog.songs (id, album_id, track_number, title, duration_seconds)
				VALUES (:id, :album, 1, 'Dreams', 0)""")
			.param("id", UUID.randomUUID()).param("album", album).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertAlbum(String title) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO catalog.albums (id, title, artist) VALUES (:id, :title, 'Fleetwood Mac')")
			.param("id", id).param("title", title).update();
		return id;
	}

	private void insertSong(UUID album, int trackNumber) {
		jdbc.sql("INSERT INTO catalog.songs (id, album_id, track_number, title) VALUES (:id, :album, :n, 'Dreams')")
			.param("id", UUID.randomUUID()).param("album", album).param("n", trackNumber).update();
	}

	private UUID insertGenre(String name) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO catalog.genres (id, name) VALUES (:id, :name)").param("id", id).param("name", name).update();
		return id;
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.musicboxd.api.catalog.CatalogSchemaTest`
Expected: FAIL, `relation "catalog.albums" does not exist`.

- [ ] **Step 3: Write the migration and the Flyway bean**

`api/src/main/resources/db/migration/catalog/V1__create_catalog.sql`:

```sql
-- MBD-28: the catalog module's schema. Albums, songs and genres are never deleted (AD-12), so no
-- foreign key here cascades. Other modules hold catalog ids without cross-schema foreign keys (AD-1).
-- Later stories extend these tables (cover art MBD-32, search index, edit/hide MBD-29); never recreate them.
CREATE TABLE catalog.albums (
	id           uuid        PRIMARY KEY,
	title        text        NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
	artist       text        NOT NULL CHECK (length(btrim(artist)) BETWEEN 1 AND 200),
	-- The API caps it at next year; the database only rules out the absurd.
	release_year smallint    CHECK (release_year BETWEEN 1000 AND 9999),
	hidden       boolean     NOT NULL DEFAULT false,
	created_at   timestamptz NOT NULL DEFAULT now(),
	updated_at   timestamptz NOT NULL DEFAULT now()
);

-- A song belongs to one album for life: moving songs between albums is not supported (AD-12).
CREATE TABLE catalog.songs (
	id               uuid        PRIMARY KEY,
	album_id         uuid        NOT NULL REFERENCES catalog.albums (id),
	track_number     integer     NOT NULL CHECK (track_number >= 1),
	title            text        NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
	duration_seconds integer     CHECK (duration_seconds BETWEEN 1 AND 86400),
	hidden           boolean     NOT NULL DEFAULT false,
	created_at       timestamptz NOT NULL DEFAULT now(),
	updated_at       timestamptz NOT NULL DEFAULT now(),
	-- Deferrable so a reorder (MBD-29) can swap numbers inside one transaction.
	CONSTRAINT songs_album_track_key UNIQUE (album_id, track_number) DEFERRABLE INITIALLY IMMEDIATE
);

-- The controlled genre list Staff manage (AD-13); profiles stores genre ids only. Hidden, never deleted.
CREATE TABLE catalog.genres (
	id         uuid        PRIMARY KEY,
	name       text        NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 50),
	hidden     boolean     NOT NULL DEFAULT false,
	created_at timestamptz NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness; the stored value keeps the casing Staff chose.
CREATE UNIQUE INDEX genres_name_key ON catalog.genres (lower(name));

CREATE TABLE catalog.album_genres (
	album_id uuid NOT NULL REFERENCES catalog.albums (id),
	genre_id uuid NOT NULL REFERENCES catalog.genres (id),
	PRIMARY KEY (album_id, genre_id)
);

-- Admin "{n} álbuns" per genre (MBD-30) counts by genre.
CREATE INDEX album_genres_genre_idx ON catalog.album_genres (genre_id);
```

`api/src/main/java/com/musicboxd/api/catalog/CatalogConfig.java`:

```java
package com.musicboxd.api.catalog;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.musicboxd.api.db.ModuleFlyway;

@Configuration
class CatalogConfig {

	@ConditionalOnProperty(name = "musicboxd.migrations.enabled", havingValue = "true", matchIfMissing = true)
	@Bean(initMethod = "migrate")
	Flyway catalogFlyway(DataSource dataSource) {
		return ModuleFlyway.forSchema(dataSource, "catalog");
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.musicboxd.api.catalog.CatalogSchemaTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add api/src/main/resources/db/migration/catalog/V1__create_catalog.sql \
  api/src/main/java/com/musicboxd/api/catalog/CatalogConfig.java \
  api/src/test/java/com/musicboxd/api/catalog/CatalogSchemaTest.java
git commit -m "Add the catalog schema: albums, songs, genres and their links, with nothing that cascades (MBD-28)"
```

---

### Task 2: Staff creates a genre

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/catalog/CatalogExceptionHandler.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/GenreView.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/GenreTakenException.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/GenreService.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java`
- Test: `api/src/test/java/com/musicboxd/api/catalog/StaffTokens.java`, `api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java`

**Interfaces:**
- Consumes: Task 1 tables; `TokenService.issue(UUID, Role).value()` (tests).
- Produces:
  - `public record GenreView(UUID id, String name, boolean hidden)`
  - `GenreRepository.findVisible(Collection<UUID> ids) : Map<UUID, GenreView>` (package-private; Task 3 uses it)
  - `GenreService.create(String name) : GenreView`
  - `CatalogExceptionHandler` (Task 3's exceptions ride it)
  - test helper `StaffTokens.bearer(TokenService, Role) : String`

- [ ] **Step 1: Write the failing test**

`api/src/test/java/com/musicboxd/api/catalog/StaffTokens.java`:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

final class StaffTokens {

	private StaffTokens() {
	}

	static String bearer(TokenService tokens, Role role) {
		return "Bearer " + tokens.issue(UUID.randomUUID(), role).value();
	}
}
```

`api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java`:

```java
package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GenreAdminTest {

	private static final String GENRES = "/api/v1/admin/catalog/genres";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffCreatesAGenre() throws Exception {
		String name = "Rock " + UUID.randomUUID();
		create(Role.STAFF, "{\"name\":\"" + name + "\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.name").value(name))
			.andExpect(jsonPath("$.hidden").value(false));
		assertThat(countNamed(name)).isEqualTo(1);
	}

	@Test
	void genreNameIsStrippedAndClashesIgnoringCase() throws Exception {
		String name = "Rock " + UUID.randomUUID();
		create(Role.STAFF, "{\"name\":\"  " + name + "  \"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value(name));
		create(Role.STAFF, "{\"name\":\" " + name.toUpperCase() + " \"}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:genre-taken"));
	}

	@Test
	void blankOrTooLongNamesAre400() throws Exception {
		for (String body : new String[] { "{\"name\":\"   \"}", "{}", "{\"name\":\"" + "x".repeat(51) + "\"}" }) {
			create(Role.STAFF, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
	}

	@Test
	void userTokenCannotCreateAGenre() throws Exception {
		String name = "Rock " + UUID.randomUUID();
		create(Role.USER, "{\"name\":\"" + name + "\"}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertThat(countNamed(name)).isZero();
	}

	private ResultActions create(Role role, String body) throws Exception {
		return mockMvc.perform(post(GENRES)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private long countNamed(String name) {
		return jdbc.sql("SELECT count(*) FROM catalog.genres WHERE name = :name").param("name", name)
			.query(Long.class).single();
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.musicboxd.api.catalog.GenreAdminTest`
Expected: FAIL. `staffCreatesAGenre` gets 404 (no route); `userTokenCannotCreateAGenre` already passes (MBD-21's rule).

- [ ] **Step 3: Write the implementation**

`CatalogExceptionHandler.java`:

```java
package com.musicboxd.api.catalog;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders this module's errors (our ErrorResponseExceptions, bean-validation failures,
 * unreadable JSON) as RFC 9457 Problem Details instead of an empty error response.
 */
@RestControllerAdvice(basePackageClasses = CatalogExceptionHandler.class)
class CatalogExceptionHandler extends ResponseEntityExceptionHandler {
}
```

`GenreView.java`:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

public record GenreView(UUID id, String name, boolean hidden) {
}
```

`GenreTakenException.java`:

```java
package com.musicboxd.api.catalog;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The type lets the admin SPA (MBD-53) show the message under the genre name field. */
public class GenreTakenException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:genre-taken");

	public GenreTakenException() {
		super(HttpStatus.CONFLICT, problem(), null);
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A genre with this name already exists");
		problem.setType(TYPE);
		problem.setTitle("Genre taken");
		return problem;
	}
}
```

`GenreRepository.java`:

```java
package com.musicboxd.api.catalog;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class GenreRepository {

	static final String NAME_UNIQUE = "genres_name_key";

	private final JdbcClient jdbc;

	GenreRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(UUID id, String name) {
		jdbc.sql("INSERT INTO catalog.genres (id, name) VALUES (:id, :name)")
			.param("id", id)
			.param("name", name)
			.update();
	}

	/** Hidden genres are left out: they are no longer selectable (UX, Lista de generos). */
	Map<UUID, GenreView> findVisible(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("SELECT id, name, hidden FROM catalog.genres WHERE id IN (:ids) AND NOT hidden")
			.param("ids", ids)
			.query(GenreView.class)
			.list()
			.stream()
			.collect(Collectors.toMap(GenreView::id, Function.identity()));
	}
}
```

`GenreService.java`:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.musicboxd.api.db.Constraints;

/** The controlled genre list (AD-13). Rename and hide arrive with MBD-30. */
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
			if (Constraints.violated(e, GenreRepository.NAME_UNIQUE)) {
				throw new GenreTakenException();
			}
			throw e;
		}
		return new GenreView(id, name, false);
	}
}
```

`GenreAdminController.java`:

```java
package com.musicboxd.api.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Staff-only by route: SecurityConfig requires STAFF on /api/v1/admin/** (MBD-21). */
@RestController
@RequestMapping("/api/v1/admin/catalog/genres")
public class GenreAdminController {

	public record CreateGenreRequest(@NotBlank @Size(max = 50) String name) {

		/** Validation sees the stripped name, matching what is stored. */
		public CreateGenreRequest {
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
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.musicboxd.api.catalog.GenreAdminTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/catalog/CatalogExceptionHandler.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreView.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreTakenException.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreService.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java \
  api/src/test/java/com/musicboxd/api/catalog/StaffTokens.java \
  api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java
git commit -m "Let Staff add a genre to the controlled list, 409 on a name taken in any case (MBD-28)"
```

---

### Task 3: Staff creates an Album with its tracklist and genres

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/catalog/SongView.java`, `AlbumView.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/UnknownGenreException.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/AlbumService.java`
- Create: `api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java`
- Modify: `api/src/test/java/com/musicboxd/api/security/AdminRouteProtectionTest.java` (`staffTokenReachesAdminRoutesWithEveryMethod`)
- Test: `api/src/test/java/com/musicboxd/api/catalog/AlbumAdminTest.java`, `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`

**Interfaces:**
- Consumes: `GenreRepository.findVisible(Collection<UUID>) : Map<UUID, GenreView>`, `GenreView`, `CatalogExceptionHandler`, `StaffTokens.bearer(TokenService, Role)` (Task 2); the `Clock` bean (`security.JwtConfig.clock()`, UTC).
- Produces:
  - `public record SongView(UUID id, int trackNumber, String title, Integer durationSeconds, boolean hidden)`
  - `public record AlbumView(UUID id, String title, String artist, Integer releaseYear, boolean hidden, Instant createdAt, List<SongView> songs, List<GenreView> genres)`
  - `AlbumAdminController.CreateAlbumRequest(String title, String artist, Integer releaseYear, List<UUID> genreIds, List<SongRequest> songs)`, `SongRequest(String title, Integer durationSeconds)`
  - `AlbumService.create(CreateAlbumRequest) : AlbumView`

- [ ] **Step 1: Write the failing tests**

`api/src/test/java/com/musicboxd/api/catalog/AlbumAdminTest.java`:

```java
package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Year;
import java.time.ZoneOffset;
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

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** MBD-28 acceptance: a STAFF token creates an Album; a USER token gets 403 on the same endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AlbumAdminTest {

	private static final String ALBUMS = "/api/v1/admin/catalog/albums";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffCreatesAnAlbumWithSongsAndGenres() throws Exception {
		UUID rock = genre();
		UUID pop = genre();
		String title = uniqueTitle();
		String body = create(Role.STAFF, """
				{"title":"%s","artist":"Fleetwood Mac","releaseYear":1977,"genreIds":["%s","%s"],
				 "songs":[{"title":"Second Hand News","durationSeconds":163},{"title":"Dreams"}]}"""
			.formatted(title, rock, pop))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.releaseYear").value(1977))
			.andExpect(jsonPath("$.hidden").value(false))
			.andExpect(jsonPath("$.createdAt").isString())
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andExpect(jsonPath("$.songs[0].trackNumber").value(1))
			.andExpect(jsonPath("$.songs[0].title").value("Second Hand News"))
			.andExpect(jsonPath("$.songs[0].durationSeconds").value(163))
			.andExpect(jsonPath("$.songs[1].trackNumber").value(2))
			.andExpect(jsonPath("$.songs[1].durationSeconds").doesNotExist())
			.andExpect(jsonPath("$.genres[0].id").value(rock.toString()))
			.andExpect(jsonPath("$.genres[1].id").value(pop.toString()))
			.andReturn().getResponse().getContentAsString();

		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		assertThat(jdbc.sql("SELECT title FROM catalog.songs WHERE album_id = :id ORDER BY track_number")
			.param("id", id).query(String.class).list()).containsExactly("Second Hand News", "Dreams");
		assertThat(jdbc.sql("SELECT genre_id FROM catalog.album_genres WHERE album_id = :id")
			.param("id", id).query(UUID.class).list()).containsExactlyInAnyOrder(rock, pop);
	}

	@Test
	void albumWithoutGenresOrYearIsFine() throws Exception {
		create(Role.STAFF, album(uniqueTitle(), ""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.releaseYear").doesNotExist())
			.andExpect(jsonPath("$.genres", hasSize(0)));
	}

	@Test
	void userTokenGets403AndWritesNothing() throws Exception {
		String title = uniqueTitle();
		create(Role.USER, album(title, ""))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(403));
		assertThat(albumsTitled(title)).isZero();
	}

	@Test
	void anonymousCallerGets401() throws Exception {
		mockMvc.perform(post(ALBUMS).contentType(MediaType.APPLICATION_JSON).content(album(uniqueTitle(), "")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unknownGenreIsRefusedWith422AndWritesNothing() throws Exception {
		String title = uniqueTitle();
		UUID missing = UUID.randomUUID();
		create(Role.STAFF, album(title, "\"genreIds\":[\"" + genre() + "\",\"" + missing + "\"],"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:unknown-genre"))
			.andExpect(jsonPath("$.genreIds", hasSize(1)))
			.andExpect(jsonPath("$.genreIds[0]").value(missing.toString()));
		assertThat(albumsTitled(title)).isZero();
	}

	@Test
	void hiddenGenreIsRefusedWith422() throws Exception {
		UUID hidden = genre();
		jdbc.sql("UPDATE catalog.genres SET hidden = true WHERE id = :id").param("id", hidden).update();
		create(Role.STAFF, album(uniqueTitle(), "\"genreIds\":[\"" + hidden + "\"],"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.genreIds[0]").value(hidden.toString()));
	}

	@Test
	void repeatedGenreIdIsLinkedOnce() throws Exception {
		UUID rock = genre();
		create(Role.STAFF, album(uniqueTitle(), "\"genreIds\":[\"" + rock + "\",\"" + rock + "\"],"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.genres", hasSize(1)));
	}

	@Test
	void titlesAreStripped() throws Exception {
		String title = uniqueTitle();
		create(Role.STAFF, """
				{"title":"  %s  ","artist":"  Fleetwood Mac ","songs":[{"title":"  Dreams "}]}""".formatted(title))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.songs[0].title").value("Dreams"));
	}

	@Test
	void blankOrMissingFieldsAre400() throws Exception {
		String[] bodies = {
			"{\"title\":\"   \",\"artist\":\"A\",\"songs\":[{\"title\":\"S\"}]}",
			"{\"artist\":\"A\",\"songs\":[{\"title\":\"S\"}]}",
			"{\"title\":\"T\",\"artist\":\"A\"}",
			"{\"title\":\"T\",\"artist\":\"A\",\"songs\":[]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"songs\":[{\"title\":\" \"}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"songs\":[{\"title\":\"S\",\"durationSeconds\":0}]}",
			"{\"title\":\"" + "x".repeat(201) + "\",\"artist\":\"A\",\"songs\":[{\"title\":\"S\"}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"releaseYear\":999,\"songs\":[{\"title\":\"S\"}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"genreIds\":[null],\"songs\":[{\"title\":\"S\"}]}",
		};
		for (String body : bodies) {
			create(Role.STAFF, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
	}

	@Test
	void releaseYearMayBeNextYearButNotLater() throws Exception {
		int next = Year.now(ZoneOffset.UTC).getValue() + 1;
		create(Role.STAFF, album(uniqueTitle(), "\"releaseYear\":" + next + ","))
			.andExpect(status().isCreated());
		create(Role.STAFF, album(uniqueTitle(), "\"releaseYear\":" + (next + 1) + ","))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	private ResultActions create(Role role, String body) throws Exception {
		return mockMvc.perform(post(ALBUMS)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	/** {@code extra} is spliced in before "songs", e.g. {@code "releaseYear":1977,}. */
	private static String album(String title, String extra) {
		return "{\"title\":\"%s\",\"artist\":\"Fleetwood Mac\",%s\"songs\":[{\"title\":\"Dreams\"}]}"
			.formatted(title, extra);
	}

	private UUID genre() {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO catalog.genres (id, name) VALUES (:id, :name)")
			.param("id", id).param("name", "Genre " + id).update();
		return id;
	}

	private long albumsTitled(String title) {
		return jdbc.sql("SELECT count(*) FROM catalog.albums WHERE title = :title").param("title", title)
			.query(Long.class).single();
	}

	private static String uniqueTitle() {
		return "Rumours " + UUID.randomUUID();
	}
}
```

`api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`:

```java
package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;

/** The catalog routes ride the springdoc pipeline (AD-7), and none of them deletes (AD-12). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogContractTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void createRoutesAreInTheContractAndNothingDeletes() throws Exception {
		String docs = mockMvc.perform(get("/api/v1/api-docs")).andReturn().getResponse().getContentAsString();
		Map<String, Map<String, Object>> paths = JsonPath.read(docs, "$.paths");

		assertThat(paths.get("/api/v1/admin/catalog/albums")).containsKey("post");
		assertThat(paths.get("/api/v1/admin/catalog/genres")).containsKey("post");
		paths.forEach((path, operations) -> {
			if (path.contains("/catalog") || path.contains("/albums") || path.contains("/songs") || path.contains("/genres")) {
				assertThat(operations).as("DELETE on %s (AD-12)", path).doesNotContainKey("delete");
			}
		});
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.musicboxd.api.catalog.AlbumAdminTest' --tests 'com.musicboxd.api.catalog.CatalogContractTest'`
Expected: FAIL. Staff requests get 404 (no route); the contract test finds no albums path. `userTokenGets403AndWritesNothing` and `anonymousCallerGets401` already pass.

- [ ] **Step 3: Write the implementation**

`SongView.java`:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

public record SongView(UUID id, int trackNumber, String title, Integer durationSeconds, boolean hidden) {
}
```

`AlbumView.java`:

```java
package com.musicboxd.api.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Songs in track order; genres in the order Staff picked them. */
public record AlbumView(UUID id, String title, String artist, Integer releaseYear, boolean hidden,
		Instant createdAt, List<SongView> songs, List<GenreView> genres) {
}
```

`UnknownGenreException.java`:

```java
package com.musicboxd.api.catalog;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** A picked genre that does not exist or is hidden; {@code genreIds} names them so the SPA can drop them. */
public class UnknownGenreException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:unknown-genre");

	public UnknownGenreException(List<UUID> genreIds) {
		super(HttpStatus.UNPROCESSABLE_CONTENT, problem(genreIds), null);
	}

	private static ProblemDetail problem(List<UUID> genreIds) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
				"Some genres do not exist or are hidden");
		problem.setType(TYPE);
		problem.setTitle("Unknown genre");
		problem.setProperty("genreIds", genreIds);
		return problem;
	}
}
```

`AlbumRepository.java`:

```java
package com.musicboxd.api.catalog;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes albums, their songs and genre links. Package-private: other modules will read through a catalog API (AD-1). */
@Repository
class AlbumRepository {

	private final JdbcClient jdbc;

	AlbumRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns the database's created_at, so the response matches what is stored. */
	Instant insertAlbum(UUID id, String title, String artist, Integer releaseYear) {
		return jdbc.sql("""
				INSERT INTO catalog.albums (id, title, artist, release_year)
				VALUES (:id, :title, :artist, :releaseYear)
				RETURNING created_at""")
			.param("id", id)
			.param("title", title)
			.param("artist", artist)
			.param("releaseYear", releaseYear, Types.SMALLINT)
			.query((rs, n) -> rs.getObject("created_at", OffsetDateTime.class).toInstant())
			.single();
	}

	void insertSong(UUID id, UUID albumId, int trackNumber, String title, Integer durationSeconds) {
		jdbc.sql("""
				INSERT INTO catalog.songs (id, album_id, track_number, title, duration_seconds)
				VALUES (:id, :albumId, :trackNumber, :title, :durationSeconds)""")
			.param("id", id)
			.param("albumId", albumId)
			.param("trackNumber", trackNumber)
			.param("title", title)
			.param("durationSeconds", durationSeconds, Types.INTEGER)
			.update();
	}

	void linkGenre(UUID albumId, UUID genreId) {
		jdbc.sql("INSERT INTO catalog.album_genres (album_id, genre_id) VALUES (:albumId, :genreId)")
			.param("albumId", albumId)
			.param("genreId", genreId)
			.update();
	}
}
```

`AlbumService.java`:

```java
package com.musicboxd.api.catalog;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import com.musicboxd.api.catalog.AlbumAdminController.CreateAlbumRequest;

@Service
class AlbumService {

	private final AlbumRepository albums;
	private final GenreRepository genres;
	private final Clock clock;

	AlbumService(AlbumRepository albums, GenreRepository genres, Clock clock) {
		this.albums = albums;
		this.genres = genres;
		this.clock = clock;
	}

	/** One transaction: the album, its tracklist (numbered by position) and its genres, or nothing. */
	@Transactional
	AlbumView create(CreateAlbumRequest request) {
		requirePlausibleYear(request.releaseYear());
		List<GenreView> picked = visibleGenres(request.genreIds());

		UUID albumId = UUID.randomUUID();
		Instant createdAt = albums.insertAlbum(albumId, request.title(), request.artist(), request.releaseYear());

		var songs = new ArrayList<SongView>();
		for (int i = 0; i < request.songs().size(); i++) {
			var song = request.songs().get(i);
			var view = new SongView(UUID.randomUUID(), i + 1, song.title(), song.durationSeconds(), false);
			albums.insertSong(view.id(), albumId, view.trackNumber(), view.title(), view.durationSeconds());
			songs.add(view);
		}
		picked.forEach(genre -> albums.linkGenre(albumId, genre.id()));

		return new AlbumView(albumId, request.title(), request.artist(), request.releaseYear(), false, createdAt,
				List.copyOf(songs), picked);
	}

	/** Checked before any insert. Unknown and hidden ids are refused together. */
	private List<GenreView> visibleGenres(List<UUID> ids) {
		Map<UUID, GenreView> found = genres.findVisible(ids);
		List<UUID> missing = ids.stream().filter(id -> !found.containsKey(id)).toList();
		if (!missing.isEmpty()) {
			throw new UnknownGenreException(missing);
		}
		return ids.stream().map(found::get).toList();
	}

	/** Upcoming releases may be entered ahead of time, up to next year (UTC). */
	private void requirePlausibleYear(Integer year) {
		int latest = Year.now(clock).getValue() + 1;
		if (year != null && year > latest) {
			throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
					ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "releaseYear must not be after " + latest),
					null);
		}
	}
}
```

`AlbumAdminController.java`:

```java
package com.musicboxd.api.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
}
```

Notes for the implementer:
- `genreIds.stream().distinct().toList()` keeps `null` elements, so `List<@NotNull UUID>` still turns `[null]` into a 400. `List.copyOf` would throw during deserialization instead.
- If `HttpStatus.UNPROCESSABLE_CONTENT` does not resolve, this Spring version predates it: use `HttpStatus.UNPROCESSABLE_ENTITY` in `UnknownGenreException`, and `status().isUnprocessableEntity()` in the test.

- [ ] **Step 4: Move the "honest 404" probe off the now-real route**

In `api/src/test/java/com/musicboxd/api/security/AdminRouteProtectionTest.java`, `staffTokenReachesAdminRoutesWithEveryMethod`, replace:

```java
		// Past security, an unknown admin route is an honest 404.
		mockMvc.perform(get("/api/v1/admin/catalog/albums").header("Authorization", bearer(Role.STAFF)))
			.andExpect(status().isNotFound());
```

with:

```java
		// Past security, an unknown admin route is an honest 404 (catalog/albums is real since MBD-28).
		mockMvc.perform(get("/api/v1/admin/reviews/7").header("Authorization", bearer(Role.STAFF)))
			.andExpect(status().isNotFound());
```

Leave the class Javadoc on `AdminProbe` as it is: the probe still covers every method on a route that answers all of them.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.musicboxd.api.catalog.*' --tests 'com.musicboxd.api.security.AdminRouteProtectionTest'`
Expected: PASS.

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew test`
Expected: PASS, including `OpenApiExportBootTest`: `catalogFlyway` is skipped when `musicboxd.migrations.enabled=false`, and the repositories make no queries at startup.

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/catalog/SongView.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumView.java \
  api/src/main/java/com/musicboxd/api/catalog/UnknownGenreException.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumRepository.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumService.java \
  api/src/main/java/com/musicboxd/api/catalog/AlbumAdminController.java \
  api/src/test/java/com/musicboxd/api/catalog/AlbumAdminTest.java \
  api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java \
  api/src/test/java/com/musicboxd/api/security/AdminRouteProtectionTest.java
git commit -m "Let Staff create an Album with its tracklist and genres in one call; USER gets 403 (MBD-28)"
```
