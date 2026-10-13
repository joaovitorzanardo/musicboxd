# MBD-30 Rework: Align the Genre Endpoints with MBD-29 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebase MBD-30 onto `main`, which now contains MBD-29. Drop the admin genre list, which is not in the ticket. Reshape the genre edit to MBD-29's conventions: `PUT /genres/{id}` renames, and `PUT /genres/{id}/hidden` hides or shows again.

**Architecture:** MBD-29 (merged as `8f9a7c3`) settled how catalog admin edits look:
- A full-state `PUT /{id}` returns the view.
- A separate `PUT /{id}/hidden` toggle takes the shared `VisibilityRequest(@NotNull Boolean hidden)` and returns `204`.
- A repository `int set…Hidden` returns rows changed, and the service turns `0` into a `…NotFoundException`.
- Tests build rows with `CatalogFixtures`.

This branch predates that. It has a partial-update `PATCH` (both fields optional, a hand-written "send something" 400, and `coalesce(CAST(:x AS …))` SQL for null binds) and its own album-creating test helper. This plan replaces those with MBD-29's shapes. It also removes the `GET` list, matching MBD-29's choice to defer the admin album list to MBD-53. No schema change.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring JDBC `JdbcClient`, Jakarta Validation, PostgreSQL 18, JUnit 5 / MockMvc / AssertJ, Testcontainers.

**Spec:** Jira MBD-30 / `_bmad-output/initiative-musicboxd/epic-catalogo-busca/story-staff-manages-the-genre-list.md`. Its acceptance criterion: "Staff adds a genre and edits its name; a USER token gets 403 on both endpoints." Architecture: AD-12 (hide, never delete) and AD-13 (genre list in `catalog`). UX: `EXPERIENCE.md` "Lista de generos (admin)", with "Renomear" and "Ocultar". Conventions to match: `AlbumAdminController`, `SongAdminController`, `VisibilityRequest`, `CatalogFixtures` and `AlbumVisibilityTest` on `origin/main`. This plan supersedes the endpoint shapes in `docs/superpowers/plans/2026-10-13-mbd-30-staff-manages-the-genre-list.md`.

**Environment:** Run Gradle from `api/` with `JAVA_HOME=~/.local/jdk-25` (there is no system JDK in WSL). Docker Desktop must be running for Testcontainers. Never `git add -A`. Commit messages end with `(MBD-30)` and the `Co-Authored-By` trailer.

## Decisions (proposed, confirm on review)

| Question | Decision | Why |
|---|---|---|
| Bring in `main` | **Rebase** onto `origin/main` and **drop** the list commit `f2c1f11`. Do not merge. | The branch was never pushed (no `origin/...mbd-30...`), so rewriting it is safe. The PR then shows only what ships, with no merge commit and no add-then-revert. |
| Admin `GET /genres` list with album counts | **Remove.** Log it in `deferred-work.md` → MBD-53. | It is not in MBD-30's acceptance criterion. MBD-29 made the same call for the admin album list ("left out by the user's choice", deferred to MBD-53). |
| Rename route | `PUT /api/v1/admin/catalog/genres/{id}` with `{name}` → `200 GenreView`. | Matches `PUT /albums/{id}`, which returns the view. A genre's only editable field is its name, so `{name}` is its whole editable state. |
| Hide route | `PUT /api/v1/admin/catalog/genres/{id}/hidden` with `VisibilityRequest` → `204`. | Identical to `/albums/{id}/hidden` and `/songs/{id}/hidden`. It reuses the record and the "rows changed → 404" pattern. |
| Keep hide at all? | **Keep.** | The ticket says "edit", UX "Lista de generos" has "Ocultar", and no other story owns genre hiding. It costs about 15 lines because it reuses `VisibilityRequest`. |
| Request record | One `GenreNameRequest` for both `POST` and `PUT`, replacing `CreateGenreRequest` and `EditGenreRequest`. | The two bodies are now identical (stripped, 1–50 characters). No committed client depends on the schema name; MBD-53 is not built. |
| Gone with the `PATCH` | The `EditGenreRequest` both-optional shape, the "Send name, hidden, or both" 400, and the `coalesce(CAST…)` UPDATE. | A required `name` and a required `hidden` make all three unnecessary. This also clears the review's deferred minors on null binds, silently dropped typo fields, the contract not showing "at least one", and the inline 400. |
| Test setup | Use `CatalogFixtures.genre(jdbc, prefix, hidden)` and `CatalogFixtures.createAlbum(...)`. Delete `createdId` and `createAlbumWith`. | MBD-29 added these fixtures for exactly this. `AlbumAdminTest.hiddenGenreIsRefusedWith422` already covers "hidden genre → 422", so this file doesn't repeat it. |

## Global Constraints

- `/api/v1/admin/**` requires `STAFF` for every method (MBD-21). Add no security rule.
- No DELETE on catalog routes (AD-12). `CatalogContractTest` asserts this.
- Genre names are stripped, 1–50 characters, and unique ignoring case (`genres_name_key` on `lower(name)`). A clash gives `409`, type `urn:musicboxd:problem:genre-taken`.
- Errors are `application/problem+json` through `CatalogExceptionHandler`.
- Tests share one database. Every genre a test asserts on gets a unique name, and `CatalogFixtures.genre` appends a UUID.
- Java sources use tabs. Repositories stay package-private.

## Review Focus

- **MBD-29 behaviour lost or broken during the rebase**, for example `GenreRepository.findSelectableFor` or the contract test's MBD-29 assertions. Pinned by the full suite run in Task 1, Step 4.
- **Renaming a genre to only a different case of its own name.** Must succeed. Pinned by Task 2: `renameMayChangeOnlyTheCase`.
- **Renaming a hidden genre.** It must stay hidden; renaming must not reset visibility. Pinned by Task 2: `renamingAHiddenGenreKeepsItHidden`.
- **Hiding twice, then showing again.** Both calls return 204, and once shown the genre can be picked for a new album again. Pinned by Task 2: `staffHidesAndShowsAGenreAgain`.
- **A USER token on either new route.** Must get 403 and leave the row unchanged. Pinned by Task 2: `userTokenCannotRenameOrHideAGenre`.

---

## File Structure

All Java paths are under `api/src/`.

- `main/java/com/musicboxd/api/catalog/GenreAdminController.java`: one `GenreNameRequest`, plus `create`, `rename` and `setHidden`.
- `main/java/com/musicboxd/api/catalog/GenreService.java`: `create`, `rename` and `setHidden`; the `taken(...)` helper stays.
- `main/java/com/musicboxd/api/catalog/GenreRepository.java`: `rename` (`UPDATE … RETURNING`) and `setHidden` (returns rows changed), plus MBD-29's `findSelectableFor`.
- `main/java/com/musicboxd/api/catalog/GenreNotFoundException.java`: unchanged.
- `test/java/com/musicboxd/api/catalog/GenreAdminTest.java`: rewritten for the two `PUT` routes, using `CatalogFixtures`.
- `test/java/com/musicboxd/api/catalog/CatalogContractTest.java`: MBD-29's version plus the two genre `put` routes.
- `_bmad-output/implementation-artifacts/deferred-work.md`: new entry for the admin genre list.
- `docs/superpowers/plans/2026-10-13-mbd-30-staff-manages-the-genre-list.md`: a "superseded" note at the top.

---

### Task 1: Rebase onto `main`, drop the list commit, log the deferral

**Files:**
- Modify (conflict resolution): `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`, and `api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java` only if git reports a conflict there
- Modify: `_bmad-output/implementation-artifacts/deferred-work.md`
- Modify: `docs/superpowers/plans/2026-10-13-mbd-30-staff-manages-the-genre-list.md`

**Interfaces:**
- Consumes: `origin/main` at `8f9a7c3` (MBD-29).
- Produces: the branch as `origin/main` + `91bfcbe'` (the `PATCH` commit, rebased) + `d13c406'` (the first plan) + this task's docs commit. On it: `GenreView`, `GenreNotFoundException`, `GenreRepository.update(...)` (Task 2 replaces it), `CatalogFixtures`, `VisibilityRequest`.

- [ ] **Step 1: Rebase, dropping `f2c1f11`**

```bash
git fetch origin
git status --short   # must be empty except untracked plan files
GIT_SEQUENCE_EDITOR="sed -i 's/^pick f2c1f11/drop f2c1f11/'" git rebase -i origin/main
```

Expected: the rebase stops on `91bfcbe` with a conflict in `CatalogContractTest.java`. Both sides renamed the test method and added lines.

- [ ] **Step 2: Resolve the conflicts**

`CatalogContractTest.java`: take `main`'s version and add one line after the genres `post` assertion. The test method becomes:

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
		assertThat(paths.get("/api/v1/admin/catalog/genres/{id}")).containsKey("patch");
		assertThat(paths.get("/api/v1/catalog/albums/{id}")).containsKey("get");
		assertThat(paths.get("/api/v1/catalog/songs/{id}")).containsKey("get");
		paths.forEach((path, operations) -> {
			if (path.contains("/catalog") || path.contains("/albums") || path.contains("/songs") || path.contains("/genres")) {
				assertThat(operations).as("DELETE on %s (AD-12)", path).doesNotContainKey("delete");
			}
		});
	}
```

`GenreRepository.java`, only if it conflicts: keep both sides. That means `import java.util.Optional;`, the branch's `update(...)` after `insert`, and `main`'s `findSelectableFor(...)` after `findVisible`.

```bash
git add api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java   # plus GenreRepository.java if it conflicted
GIT_EDITOR=true git rebase --continue
git log --oneline origin/main..HEAD
```

Expected: two commits, "Let Staff rename, hide and show a genre again…" and "Add the MBD-30 implementation plan". No "list every genre" commit, and `GenreAdminView.java` does not exist.

- [ ] **Step 3: Log the deferral and mark the old plan superseded**

Append to `_bmad-output/implementation-artifacts/deferred-work.md`:

```markdown
- source_plan: `docs/superpowers/plans/2026-10-13-mbd-30-align-with-mbd-29.md`
  summary: Admin genre list endpoint (GET /api/v1/admin/catalog/genres, hidden genres included, each with its album count) for the admin "Gêneros" screen ("{n} álbuns", "Ativo").
  evidence: Not in MBD-30's acceptance criteria; dropped to match MBD-29's deferral of the admin album list. The admin SPA cannot list genres without it. The index catalog.album_genres_genre_idx already exists for the count.
  status: open -> MBD-53 (admin SPA)
```

Insert this right below the title line of `docs/superpowers/plans/2026-10-13-mbd-30-staff-manages-the-genre-list.md`:

```markdown
> **Superseded in part (2026-10-13):** after MBD-29 merged, `docs/superpowers/plans/2026-10-13-mbd-30-align-with-mbd-29.md` replaced the `PATCH` with `PUT /{id}` + `PUT /{id}/hidden` and dropped the admin `GET` list (deferred to MBD-53).
```

- [ ] **Step 4: Run the full suite on the rebased tree**

Run (from `api/`): `JAVA_HOME=~/.local/jdk-25 ./gradlew test`
Expected: BUILD SUCCESSFUL. All of MBD-29's tests (`AlbumEditTest`, `AlbumVisibilityTest`, `CatalogReadTest`, `CatalogEditAcceptanceTest`) pass next to this branch's `GenreAdminTest`.

- [ ] **Step 5: Commit**

```bash
git add _bmad-output/implementation-artifacts/deferred-work.md docs/superpowers/plans/2026-10-13-mbd-30-staff-manages-the-genre-list.md docs/superpowers/plans/2026-10-13-mbd-30-align-with-mbd-29.md
git commit -m "$(cat <<'EOF'
Defer the admin genre list to MBD-53, as MBD-29 did for albums; plan the rework (MBD-30)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Replace the `PATCH` with `PUT /{id}` (rename) and `PUT /{id}/hidden` (toggle)

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreService.java`
- Modify: `api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java`
- Test: `api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java` (rewrite), `api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java`

**Interfaces:**
- Consumes:
  - `CatalogFixtures.genre(JdbcClient, String prefix, boolean hidden) → UUID` (name = `prefix + " " + id`)
  - `CatalogFixtures.createAlbum(MockMvc, TokenService, List<UUID> genreIds, String... songTitles) → String` (asserts 201)
  - `VisibilityRequest(@NotNull Boolean hidden)`, `GenreNotFoundException()`, `GenreView(UUID, String, boolean)`
- Produces:
  - `public record GenreAdminController.GenreNameRequest(@NotBlank @Size(max = 50) String name)`
  - `GenreView GenreService.rename(UUID id, String name)`
  - `void GenreService.setHidden(UUID id, boolean hidden)`
  - `Optional<GenreView> GenreRepository.rename(UUID id, String name)`
  - `int GenreRepository.setHidden(UUID id, boolean hidden)`
  - Routes: `PUT /api/v1/admin/catalog/genres/{id}` → 200 `GenreView`; `PUT /api/v1/admin/catalog/genres/{id}/hidden` → 204

- [ ] **Step 1: Rewrite the tests**

Replace `GenreAdminTest.java` with:

```java
package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.genre;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** The admin "Gêneros" list: add, rename, hide; nothing is deleted (UX, AD-12). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GenreAdminTest {

	private static final String GENRES = "/api/v1/admin/catalog/genres";
	private static final String GENRE = GENRES + "/{id}";
	private static final String GENRE_HIDDEN = GENRE + "/hidden";

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

	@Test
	void staffRenamesAGenre() throws Exception {
		UUID id = genre(jdbc, "Rock", false);
		String renamed = "Hard Rock " + UUID.randomUUID();
		rename(Role.STAFF, id, "{\"name\":\"  " + renamed + "  \"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.name").value(renamed))
			.andExpect(jsonPath("$.hidden").value(false));
		assertThat(stored(id).name()).isEqualTo(renamed);
	}

	@Test
	void renameMayChangeOnlyTheCase() throws Exception {
		UUID id = genre(jdbc, "rock", false);
		String upper = stored(id).name().toUpperCase();
		rename(Role.STAFF, id, "{\"name\":\"" + upper + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value(upper));
	}

	@Test
	void renamingAHiddenGenreKeepsItHidden() throws Exception {
		UUID id = genre(jdbc, "Axé", true);
		rename(Role.STAFF, id, "{\"name\":\"Axé music " + UUID.randomUUID() + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(true));
		assertThat(stored(id).hidden()).isTrue();
	}

	@Test
	void renameToAnotherGenresNameIs409AndChangesNothing() throws Exception {
		String taken = stored(genre(jdbc, "Jazz", false)).name();
		UUID id = genre(jdbc, "Blues", false);
		GenreView before = stored(id);
		rename(Role.STAFF, id, "{\"name\":\" " + taken.toLowerCase() + " \"}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:genre-taken"));
		assertThat(stored(id)).isEqualTo(before);
	}

	@Test
	void invalidRenamesAre400AndChangeNothing() throws Exception {
		UUID id = genre(jdbc, "Funk", false);
		GenreView before = stored(id);
		for (String body : new String[] { "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(51) + "\"}", "{}",
				"{\"name\":null}" }) {
			rename(Role.STAFF, id, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(stored(id)).isEqualTo(before);
	}

	/** Hidden genres leave the options for new picks (AlbumAdminTest.hiddenGenreIsRefusedWith422); shown again, they are back. */
	@Test
	void staffHidesAndShowsAGenreAgain() throws Exception {
		UUID id = genre(jdbc, "MPB", false);

		setHidden(Role.STAFF, id, "{\"hidden\":true}").andExpect(status().isNoContent());
		setHidden(Role.STAFF, id, "{\"hidden\":true}").andExpect(status().isNoContent());
		assertThat(stored(id).hidden()).isTrue();

		setHidden(Role.STAFF, id, "{\"hidden\":false}").andExpect(status().isNoContent());
		assertThat(stored(id).hidden()).isFalse();
		createAlbum(mockMvc, tokens, List.of(id), "Águas de Março");
	}

	@Test
	void missingHiddenIs400() throws Exception {
		UUID id = genre(jdbc, "Forró", false);
		setHidden(Role.STAFF, id, "{}")
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertThat(stored(id).hidden()).isFalse();
	}

	@Test
	void unknownGenreIs404AndMalformedIdIs400() throws Exception {
		UUID unknown = UUID.randomUUID();
		rename(Role.STAFF, unknown, "{\"name\":\"Anything\"}").andExpect(status().isNotFound());
		setHidden(Role.STAFF, unknown, "{\"hidden\":true}").andExpect(status().isNotFound());
		rename(Role.STAFF, "not-a-uuid", "{\"name\":\"Anything\"}")
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void userTokenCannotRenameOrHideAGenre() throws Exception {
		UUID id = genre(jdbc, "Samba", false);
		GenreView before = stored(id);
		rename(Role.USER, id, "{\"name\":\"Hijacked " + UUID.randomUUID() + "\"}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		setHidden(Role.USER, id, "{\"hidden\":true}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertThat(stored(id)).isEqualTo(before);
	}

	private ResultActions create(Role role, String body) throws Exception {
		return mockMvc.perform(post(GENRES)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions rename(Role role, Object id, String body) throws Exception {
		return mockMvc.perform(put(GENRE, id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions setHidden(Role role, Object id, String body) throws Exception {
		return mockMvc.perform(put(GENRE_HIDDEN, id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private GenreView stored(UUID id) {
		return jdbc.sql("SELECT id, name, hidden FROM catalog.genres WHERE id = :id")
			.param("id", id)
			.query(GenreView.class).single();
	}

	private long countNamed(String name) {
		return jdbc.sql("SELECT count(*) FROM catalog.genres WHERE name = :name").param("name", name)
			.query(Long.class).single();
	}
}
```

In `CatalogContractTest.java`, replace the line `assertThat(paths.get("/api/v1/admin/catalog/genres/{id}")).containsKey("patch");` with:

```java
		assertThat(paths.get("/api/v1/admin/catalog/genres/{id}")).containsKey("put").doesNotContainKey("patch");
		assertThat(paths.get("/api/v1/admin/catalog/genres/{id}/hidden")).containsKey("put");
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `api/`): `JAVA_HOME=~/.local/jdk-25 ./gradlew test --tests com.musicboxd.api.catalog.GenreAdminTest --tests com.musicboxd.api.catalog.CatalogContractTest`
Expected: FAIL on the new rename and hide tests (`405 Method Not Allowed` for `PUT`) and on the contract test (no `put` key). The four create tests PASS. `userTokenCannotRenameOrHideAGenre` may pass already because the route guard answers first.

- [ ] **Step 3: Reshape the repository**

In `GenreRepository.java`, replace `update(...)` with the two methods below. Keep `import java.util.Optional;`.

```java
	/** Empty when no genre has this id. Visibility is left as it is. */
	Optional<GenreView> rename(UUID id, String name) {
		return jdbc.sql("UPDATE catalog.genres SET name = :name WHERE id = :id RETURNING id, name, hidden")
			.param("id", id)
			.param("name", name)
			.query(GenreView.class)
			.optional();
	}

	/** Returns the rows changed: 0 means no such genre. */
	int setHidden(UUID id, boolean hidden) {
		return jdbc.sql("UPDATE catalog.genres SET hidden = :hidden WHERE id = :id")
			.param("id", id)
			.param("hidden", hidden)
			.update();
	}
```

- [ ] **Step 4: Reshape the service**

Replace `GenreService.java` with:

```java
package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	@Transactional
	GenreView rename(UUID id, String name) {
		try {
			return genres.rename(id, name).orElseThrow(GenreNotFoundException::new);
		}
		catch (DuplicateKeyException e) {
			throw taken(e);
		}
	}

	/** Hidden genres leave the options for new picks but stay on the albums and profiles that use them. */
	@Transactional
	void setHidden(UUID id, boolean hidden) {
		if (genres.setHidden(id, hidden) == 0) {
			throw new GenreNotFoundException();
		}
	}

	/** The unique index ignores case, so it also catches a rename onto another genre's name in any case. */
	private static RuntimeException taken(DuplicateKeyException e) {
		return Constraints.violated(e, GenreRepository.NAME_UNIQUE) ? new GenreTakenException() : e;
	}
}
```

- [ ] **Step 5: Reshape the controller**

Replace `GenreAdminController.java` with:

```java
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
```

- [ ] **Step 6: Run the full suite**

Run (from `api/`): `JAVA_HOME=~/.local/jdk-25 ./gradlew test`
Expected: BUILD SUCCESSFUL, 0 failures. `grep -rn "EditGenreRequest\|CreateGenreRequest\|PatchMapping\|coalesce" api/src` prints nothing.

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/catalog/GenreAdminController.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreService.java \
  api/src/main/java/com/musicboxd/api/catalog/GenreRepository.java \
  api/src/test/java/com/musicboxd/api/catalog/GenreAdminTest.java \
  api/src/test/java/com/musicboxd/api/catalog/CatalogContractTest.java
git commit -m "$(cat <<'EOF'
Rename a genre with PUT and hide it with PUT /hidden, matching MBD-29's album and song routes (MBD-30)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

## Acceptance check

- Staff adds a genre: `staffCreatesAGenre`.
- Staff edits its name: `staffRenamesAGenre`.
- A USER token gets 403 on create: `userTokenCannotCreateAGenre`.
- A USER token gets 403 on edit: `userTokenCannotRenameOrHideAGenre`, which covers both rename and hide.
