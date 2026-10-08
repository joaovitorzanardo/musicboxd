package com.musicboxd.api.db;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;

/** Tells which Postgres constraint (or unique index) a failed write ran into. */
public final class Constraints {

	private Constraints() {
	}

	public static boolean violated(DataAccessException e, String constraint) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null
					&& constraint.equals(psql.getServerErrorMessage().getConstraint())) {
				return true;
			}
		}
		return false;
	}
}
