<div align="center">

# Orion Key

**Automated Digital Goods Delivery Platform**

自动化数字商品（卡密）发卡平台

[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-22-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen?logo=springboot)
![Next.js](https://img.shields.io/badge/Next.js-16-black?logo=next.js)
![React](https://img.shields.io/badge/React-19-61dafb?logo=react)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14+-336791?logo=postgresql&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178c6?logo=typescript&logoColor=white)
![Tailwind CSS](https://img.shields.io/badge/Tailwind%20CSS-3.4-38bdf8?logo=tailwindcss&logoColor=white)
![pnpm](https://img.shields.io/badge/pnpm-9+-f69220?logo=pnpm&logoColor=white)

[简体中文](README.md) | English

</div>

---

## Public Release Notes

This repository is a self-hosted delivery platform template intended for deployment on your own server.

- No real production secrets are included; copy `.env.example` and fill in your own values before deployment
- No default admin account is seeded; create the first admin manually after installation
- Every deployment should use a fresh database password, JWT secret, payment config, and domain
- Suitable as a base template for self-hosting, private forks, and secondary development

---

## Quick Start

1. Copy the environment template:
   `cp .env.example .env`
2. Edit `.env` and replace the database, JWT, domain, mail, and other placeholders with your own values
3. Choose one startup mode:
   Source build: `docker compose up -d --build`
   Image deploy: `docker compose -f docker-compose.prod.yml up -d`
4. Configure your own reverse proxy, domain, and SSL outside this repository
5. After first boot, create the first admin account manually, then configure payment channels and site settings

Read [UPGRADE.md](UPGRADE.md) before upgrading an existing deployment.

---

## Screenshots

<details open>
<summary><b>Storefront</b></summary>
<br>

| Home (Light) | Home (Dark) |
|:---:|:---:|
| ![Home Light](.github/assets/home-light.png) | ![Home Dark](.github/assets/home-dark.png) |

| Product Detail (Light) | Product Detail (Dark) |
|:---:|:---:|
| ![Detail Light](.github/assets/detail-light.png) | ![Detail Dark](.github/assets/detail-dark.png) |

| Order Query (Light) | Order Query (Dark) |
|:---:|:---:|
| ![Order Light](.github/assets/order-light.png) | ![Order Dark](.github/assets/order-dark.png) |

</details>

<details open>
<summary><b>Admin Panel</b></summary>
<br>

| Dashboard (Light) | Dashboard (Dark) |
|:---:|:---:|
| ![Admin Light](.github/assets/admin-light.png) | ![Admin Dark](.github/assets/admin-dark.png) |

</details>

---

## Features

|  |  |
|---|---|
| 🛒 **Auto Delivery** — Automatic key distribution after payment | 🎨 **Theming** — Light/dark mode with multiple accent colors |
| 📦 **Product Management** — Categories, stock control, bulk key import | 🔒 **Security** — Stateless JWT auth + BCrypt encryption |
| 💳 **Multi-Payment** — Extensible payment architecture (WeChat/Alipay) | 🛡️ **Risk Control** — IP rate limiting, brute-force protection, order anti-fraud |
| 📊 **Admin Dashboard** — Sales overview, order/user/site management | 🔍 **Order Tracking** — Query keys by order number (guest & member) |
| 🛍️ **Shopping Cart** — Multi-item checkout in one order | ⚙️ **Site Config** — Announcements, popups, maintenance mode via admin panel |

---

## Tech Stack

| Layer | Technologies |
|-------|-------------|
| **Frontend** | Next.js 16 · React 19 · TypeScript · Tailwind CSS 3 · shadcn/ui |
| **Backend** | Spring Boot 3.4 · Java 22 · Spring Data JPA · Spring Security |
| **Database** | PostgreSQL 14+ |
| **Auth** | JWT (jjwt) · BCrypt |
| **Build** | pnpm (frontend) · Maven (backend) |

### Monorepo Structure

> pnpm workspaces monorepo — frontend and backend managed together.

```
orion-key/
├── apps/
│   ├── web/                          # Next.js frontend
│   │   ├── app/
│   │   │   ├── (store)/              # Storefront routes (home, product, cart, order, payment…)
│   │   │   └── admin/                # Admin panel routes (dashboard, products, keys, orders…)
│   │   ├── features/                 # Business feature modules
│   │   ├── services/                 # API client layer (unified backend calls)
│   │   ├── hooks/                    # Custom React hooks
│   │   ├── components/               # Shared UI components (shadcn/ui)
│   │   ├── types/                    # TypeScript type definitions
│   │   └── next.config.mjs           # Next.js config (includes API proxy rewrites)
│   │
│   └── api/                          # Spring Boot backend
│       └── src/main/
│           ├── java/com/orionkey/
│           │   ├── controller/       # REST controllers (storefront + admin)
│           │   ├── entity/           # JPA entities (16 tables)
│           │   ├── repository/       # Data access layer
│           │   ├── service/          # Business logic layer
│           │   ├── config/           # Security, JWT, CORS config
│           │   └── model/            # DTOs / VOs
│           └── resources/
│               ├── application.yml   # App config (DB, JWT, mail, uploads, etc.)
│               └── data.sql          # Seed data (admin account, site config, payment channels)
│
├── docker-compose.prod.yml           # Production Docker Compose
├── .env.example                      # Environment variable template
├── pnpm-workspace.yaml               # Monorepo workspace declaration
└── ui_picture/                       # Project screenshots
```

---

## Prerequisites

Ensure the following tools are installed before getting started:

| Tool | Version | Notes |
|------|---------|-------|
| Java | 22+ | Backend runtime |
| Maven | 3.9+ | Backend build tool |
| Node.js | 20+ | Frontend runtime |
| pnpm | 9+ | Frontend package manager (`npm i -g pnpm`) |
| PostgreSQL | 14+ | Database — create a database and user before starting |

---

## Configuration

Main config file: `apps/api/src/main/resources/application.yml`

All settings support **environment variable overrides** (`${ENV_VAR:default}`). Edit the yml directly for local dev; use env vars for production.

### Database

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/orion_key}
    username: ${DB_USERNAME:orionkey}
    password: ${DB_PASSWORD:your_password}
```

Tables are auto-created on first startup (`ddl-auto: update`). After startup, run the seed SQL once to insert site config and demo data (it no longer creates a default admin account):

```bash
psql -U orionkey -d orion_key -f apps/api/src/main/resources/data.sql
```

> The SQL uses `WHERE NOT EXISTS` guards — safe to run multiple times.

### JWT Authentication

```yaml
jwt:
  secret: ${JWT_SECRET:orion-key-dev-secret-key-must-be-at-least-256-bits-long-for-hs256}
  expiration: 86400000  # 24 hours
```

**Must** replace with a random secret for production:

```bash
openssl rand -base64 64
```

### Password Encryption Mode

```yaml
security:
  password-plain: ${PASSWORD_PLAIN:true}  # true=plaintext (dev), false=BCrypt (production)
```

- **Local dev**: `true` (default) — passwords stored in plaintext for easy debugging
- **Production**: set to `false` to enable BCrypt — **reset all user passwords before switching**

### Email

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.example.com}
    port: ${MAIL_PORT:465}
    username: ${MAIL_USERNAME:your@email.com}
    password: ${MAIL_PASSWORD:your_password}

mail:
  enabled: ${MAIL_ENABLED:true}       # Master switch — set false to disable all emails
  site-url: ${MAIL_SITE_URL:https://your-domain.com}
```

### File Uploads

```yaml
upload:
  path: ${UPLOAD_PATH:./uploads}                # File storage path
  url-prefix: ${UPLOAD_URL_PREFIX:/api/uploads}  # Access URL prefix
```

---

## Local Development

### Option A: Start Separately

**Start backend:**

```bash
cd apps/api
mvn spring-boot:run
# Running at http://localhost:8083/api
```

**Start frontend:**

```bash
cd apps/web
pnpm install
pnpm dev
# Running at http://localhost:3000
```

### Option B: Start Frontend from Monorepo Root

```bash
# From project root
pnpm install
pnpm dev:web
# Equivalent to: pnpm --filter @orion-key/web dev
```

> **API Proxy**: `next.config.mjs` rewrites `/api/*` to `http://localhost:8083` automatically — no CORS setup needed. Set `BACKEND_URL` env var if your backend runs on a different port.

### Verify

- Health check: `GET http://localhost:8083/api/categories`
- No default admin account is seeded; create one manually after deployment

---

## Docker Deployment

The project provides `docker-compose.prod.yml` for production — one container each for frontend and backend, communicating over Docker's internal network.

### 1. Configure Environment Variables

Copy `.env.example` to `.env` and fill in your values:

```bash
cp .env.example .env
```

Key variables:

```env
# Database
DB_URL=jdbc:postgresql://your-db-host:5432/orion_key
DB_USERNAME=orionkey
DB_PASSWORD=your_strong_password

# Security (must change)
JWT_SECRET=generate with: openssl rand -base64 64
PASSWORD_PLAIN=false

# Email (set MAIL_ENABLED=false to disable)
MAIL_ENABLED=true
MAIL_HOST=smtp.example.com
MAIL_USERNAME=your@email.com
MAIL_PASSWORD=your_password

# Docker images (built by CI/CD, or specify manually)
API_IMAGE=ghcr.io/your-org/orion-key-api:latest
WEB_IMAGE=ghcr.io/your-org/orion-key-web:latest
```

### 2. Start

```bash
docker compose -f docker-compose.prod.yml pull    # Pull latest images
docker compose -f docker-compose.prod.yml up -d    # Start in background
```

> Uploaded files are persisted via the `./uploads` volume mount — data survives container rebuilds. The frontend container accesses the backend via Docker internal network at `http://api:8083`. For production, add an Nginx reverse proxy in front for HTTPS and static assets.

---

## License

[MIT](LICENSE) © 2025 Riven
