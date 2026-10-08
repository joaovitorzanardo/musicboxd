-- Minimal profiles stub (MBD-17): username is owned here, not by accounts (AD-13).
-- epic-perfil-favoritos extends this table (avatar, cover, bio, genres, favorites); never recreate it.
-- id equals the accounts.accounts id; no cross-schema foreign key (AD-1).
CREATE TABLE profiles.profiles (
	id         uuid        PRIMARY KEY,
	username   text        NOT NULL CHECK (username ~ '^[A-Za-z0-9_]{3,20}$'),
	created_at timestamptz NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness; the stored value keeps the casing the person chose.
CREATE UNIQUE INDEX profiles_username_key ON profiles.profiles (lower(username));
