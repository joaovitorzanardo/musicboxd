- source_plan: `_bmad-output/initiative-musicboxd/epic-plataforma-base/story-tracer-bullet-api-spa-and-local-compose-talk-to-each-other-plan.md`
  summary: Replace the hardcoded local-dev Postgres credentials in deploy/docker-compose.yml with environment-file-based secrets.
  evidence: Real (musicboxd/musicboxd hardcoded in the compose file), but scoped to production secrets handling per AD-11 ("secrets live in environment files on the host, outside the repo"), which is entry 1.5's ("Production Compose stack behind nginx TLS") job, not this tracer-bullet ticket's.
