-- MBD-21: one role per account (AD-8). STAFF manages the catalog through /api/v1/admin/**.
-- It rides in the access token as the `role` claim, read here at every login and refresh,
-- so a change takes effect at the next access-token expiry. Every existing account is a USER.
ALTER TABLE accounts.accounts
	ADD COLUMN role text NOT NULL DEFAULT 'USER'
	CONSTRAINT accounts_role_check CHECK (role IN ('USER', 'STAFF'));
