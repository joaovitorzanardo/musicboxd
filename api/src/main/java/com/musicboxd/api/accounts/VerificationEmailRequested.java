package com.musicboxd.api.accounts;

import java.util.UUID;

/** Published inside the registration or resend transaction; the email goes out after it commits. */
record VerificationEmailRequested(UUID accountId, String email, String rawToken) {

	@Override
	public String toString() {
		return "VerificationEmailRequested[accountId=" + accountId + ", email=" + email + ", rawToken=***]";
	}
}
