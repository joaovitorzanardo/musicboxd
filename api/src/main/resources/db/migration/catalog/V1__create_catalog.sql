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
