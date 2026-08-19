# Receipt Splitter

Photograph a restaurant/grocery receipt, OCR it into structured line items,
let each participant in a group claim what they had on a shared
live-updating page, and compute what everyone owes the payer.

A portfolio project pairing a Spring Boot backend with a React (Vite +
TypeScript) frontend. Full architecture, data model, and design rationale
live in [ARCHITECTURE.md](ARCHITECTURE.md).

## Local setup

**1. Postgres** (Docker Compose, from the repo root):

```bash
docker compose up -d
docker compose ps   # confirm the postgres service is healthy
```

**2. Backend** (Spring Boot, Maven — requires Postgres running from step 1;
tests connect to it directly):

```bash
cd backend
mvn test               # run the test suite
mvn spring-boot:run    # or: run the app on http://localhost:8080
```

**3. Frontend** (Vite + React + TypeScript):

```bash
cd frontend
npm install
npm run dev             # dev server on http://localhost:5173
```

## Project status

Scaffolding only — no domain logic, no OCR/Vision integration, no real
endpoints yet. See
[docs/superpowers/plans/2026-08-18-project-scaffolding.md](docs/superpowers/plans/2026-08-18-project-scaffolding.md)
for what the scaffolding pass covered.
