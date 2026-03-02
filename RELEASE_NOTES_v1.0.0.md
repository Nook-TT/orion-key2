# v1.0.0

First public self-hosted release of the project template.

## Highlights

- Prepared for public GitHub publishing without shipping real production secrets
- Added `.env` and runtime data ignore rules to prevent accidental secret commits
- Added `PUBLISHING.md` and `UPGRADE.md` for release and maintenance workflows
- Added a source-build `docker-compose.yml` template suitable for self-hosting
- Removed hard-coded deployment domain usage from tracked deployment files
- Added a clearer Quick Start section to both Chinese and English READMEs

## Security Changes

- No default admin account is seeded
- `JWT_SECRET` is now required instead of falling back to a shared default value
- `PASSWORD_PLAIN` now defaults to `false`
- Backend application logging now defaults to `INFO`
- Guest access to `GET /orders/{id}/export` requires authentication
- Guest access to `POST /orders/deliver` is now blocked; the endpoint is admin-only
- Order delivery is triggered automatically on payment success and admin mark-paid actions
- Storefront order query now reads delivery results without triggering delivery
- Production builds no longer use mock API fallbacks unless explicitly enabled

## Breaking Changes

- Deployers must provide a real `JWT_SECRET`
- Deployers must provide their own domain via `NEXT_PUBLIC_BASE_URL`
- Existing custom tooling that called guest `POST /orders/deliver` must be updated
- Storefront integrations should use `GET /orders/{id}/delivery` for read-only delivery results

## Recommended Post-Deploy Steps

1. Copy `.env.example` to `.env` and fill in your own values
2. Create the first admin account manually
3. Configure your reverse proxy and SSL
4. Configure payment channels before accepting live orders
