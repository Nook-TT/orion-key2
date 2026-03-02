# Upgrade Guide

Use this process for every release upgrade.

1. Back up the database and uploaded files before changing anything.
2. Read the release notes and compare `.env.example` with your current `.env`.
3. Add any new environment variables manually. Do not overwrite your existing `.env`.
4. Pull the new code or image tag.
5. Rebuild or pull containers, then restart:
   - Source build: `docker compose up -d --build`
   - Image deploy: `docker compose -f docker-compose.prod.yml pull && docker compose -f docker-compose.prod.yml up -d`
6. Verify:
   - API health and public pages load
   - Login works
   - Order creation and delivery flow still works
7. If a release includes schema changes, run the documented migration before traffic is switched back.

Operational rules:
- Never store secrets in Git.
- Keep your Nginx and TLS config outside this repository.
- Test upgrades on a staging server before production if you have active users.
