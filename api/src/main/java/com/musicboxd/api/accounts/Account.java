package com.musicboxd.api.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

record Account(UUID id, String email, String passwordHash, OffsetDateTime emailVerifiedAt) {

	boolean emailVerified() {
		return emailVerifiedAt != null;
	}

	@Override
	public String toString() {
		return "Account[id=" + id + ", email=" + email + ", passwordHash=***, emailVerified=" + emailVerified() + "]";
	}
}
