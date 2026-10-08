-- MBD-21: the first STAFF account. There is no self-service promotion in the MVP; the email comes from
-- MUSICBOXD_BOOTSTRAP_STAFF_EMAIL (validated and lowercased by AccountsConfig). Empty matches nobody.
-- ${flyway:timestamp} changes the checksum on every start, so Flyway re-applies this file each time:
-- registering the account after the variable was set needs only an api restart. It never demotes.
UPDATE accounts.accounts SET role = 'STAFF' WHERE lower(email) = '${bootstrap_staff_email}';
