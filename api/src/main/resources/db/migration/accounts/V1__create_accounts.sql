-- Credentials only. The username lives in profiles.profiles (AD-13).
CREATE TABLE accounts.accounts (
	id            uuid        PRIMARY KEY,
	email         text        NOT NULL,
	password_hash text        NOT NULL,
	created_at    timestamptz NOT NULL DEFAULT now()
);

-- Emails are stored lowercased by AccountService; the index guarantees it regardless.
CREATE UNIQUE INDEX accounts_email_key ON accounts.accounts (lower(email));
