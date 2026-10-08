-- MBD-21: the first STAFF account. There is no self-service promotion in the MVP; the email comes from
-- MUSICBOXD_BOOTSTRAP_STAFF_EMAIL (validated and lowercased by AccountsConfig). Empty matches nobody.
-- This is an afterMigrate callback, not a migration: Flyway runs it on every migrate(), even with nothing
-- pending, and records nothing in flyway_schema_history, so an older image still validates after a rollback.
-- Registering the account after the variable was set needs only an api restart. Only a verified account is
-- promoted. It never demotes.
UPDATE accounts.accounts SET role = 'STAFF' WHERE lower(email) = '${bootstrap_staff_email}' AND email_verified_at IS NOT NULL;
