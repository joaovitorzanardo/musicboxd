package com.musicboxd.api.accounts;

import java.util.UUID;

/** What the API shows about an account: never the password hash. */
public record AccountView(UUID id, String email, String username) {
}
