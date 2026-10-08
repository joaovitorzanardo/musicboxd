package com.musicboxd.api.accounts;

import java.util.UUID;

record Account(UUID id, String email, String passwordHash) {
}
