package com.musicboxd.api.catalog;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An edit's tracklist must list every song of the album exactly once: leaving one out would delete it and
 * naming another album's would move it, and neither is supported (AD-12). The three id lists let the SPA
 * (MBD-53) reload the editor.
 */
public class TracklistMismatchException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:tracklist-mismatch");

	public TracklistMismatchException(List<UUID> missing, List<UUID> unknown, List<UUID> repeated) {
		super(HttpStatus.UNPROCESSABLE_CONTENT, problem(missing, unknown, repeated), null);
	}

	private static ProblemDetail problem(List<UUID> missing, List<UUID> unknown, List<UUID> repeated) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
				"The tracklist must list every song of this album exactly once");
		problem.setType(TYPE);
		problem.setTitle("Tracklist mismatch");
		problem.setProperty("missingSongIds", missing);
		problem.setProperty("unknownSongIds", unknown);
		problem.setProperty("repeatedSongIds", repeated);
		return problem;
	}
}
