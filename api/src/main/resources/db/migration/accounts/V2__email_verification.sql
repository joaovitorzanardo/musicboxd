-- MBD-18: login requires a verified email (AD-8).
ALTER TABLE accounts.accounts ADD COLUMN email_verified_at timestamptz;

-- Accounts created before verification existed (MBD-17 smoke tests) are grandfathered in,
-- so this deploy locks nobody out.
UPDATE accounts.accounts SET email_verified_at = created_at;

-- One-time links. Only SHA-256(token) is stored; the raw token exists only in the email.
CREATE TABLE accounts.email_verification_tokens (
	token_hash  text        PRIMARY KEY,
	account_id  uuid        NOT NULL REFERENCES accounts.accounts (id),
	expires_at  timestamptz NOT NULL,
	consumed_at timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX email_verification_tokens_account_idx ON accounts.email_verification_tokens (account_id);
