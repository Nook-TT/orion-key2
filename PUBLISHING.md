# Public Release Workflow

This repository is intended to be published as application code only. Do not commit any server-specific secrets or infrastructure state.

Release checklist:
- Keep `.env` private. Commit `.env.example` only.
- Keep runtime data private. Do not commit `uploads/`, database dumps, logs, or TLS assets.
- Use placeholder domains only in tracked files. Replace any real domain with `your-domain.com`.
- Do not seed a default admin account. Each deployer must create their own admin manually.
- Require deployers to generate a fresh `JWT_SECRET` and database password.
- Keep dangerous endpoints locked down by default. Public order lookup can stay open only if ownership checks are enforced.

Suggested repo structure:
- Track application code, Dockerfiles, `.env.example`, `docker-compose.prod.yml`, docs.
- Keep Nginx, Certbot, Cloudflare, payment merchant credentials, and real `.env` outside Git.

Before publishing:
1. Run `git status --short` and verify `.env` is not tracked.
2. Run `rg -n "shop\\.52lo\\.com|JWT_SECRET=|POSTGRES_PASSWORD=|DB_PASSWORD=|MAIL_PASSWORD=" .` and confirm there are no real secrets.
3. Commit on a dedicated release branch, then push to your own GitHub repository.

Recommended release process:
1. Publish tagged versions such as `v1.0.0`.
2. Add release notes for new config keys, schema changes, and breaking changes.
3. Keep `UPGRADE.md` current so existing users can update safely.
