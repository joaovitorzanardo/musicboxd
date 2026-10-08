-- MBD-19: opaque refresh tokens (AD-8). Only SHA-256(token) is stored; the raw token lives in the cookie.
-- Each login starts a family; each refresh adds a token to it. Reusing a rotated-out token after the
-- grace window revokes the family. Revocation lives on the family row, which every rotation locks, so a
-- successor being issued at the same moment cannot escape it.
CREATE TABLE accounts.refresh_token_families (
	id          uuid        PRIMARY KEY,
	account_id  uuid        NOT NULL REFERENCES accounts.accounts (id),
	revoked_at  timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX refresh_token_families_account_idx ON accounts.refresh_token_families (account_id);

CREATE TABLE accounts.refresh_tokens (
	token_hash  text        PRIMARY KEY,
	family_id   uuid        NOT NULL REFERENCES accounts.refresh_token_families (id),
	account_id  uuid        NOT NULL REFERENCES accounts.accounts (id),
	expires_at  timestamptz NOT NULL,
	rotated_at  timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX refresh_tokens_family_idx ON accounts.refresh_tokens (family_id);
CREATE INDEX refresh_tokens_account_idx ON accounts.refresh_tokens (account_id);
