# CI/CD Pipeline — Design Spec

**Date:** 2026-08-19
**Status:** Approved for planning

## Purpose

Give the repo an automated quality gate on every pull request and a
reproducible deployable artifact on every merge to `main`. Three things,
specifically: run the full test suite for both halves of the monorepo and
capture a coverage report from each, run CodeQL security analysis over both
languages, and publish a versioned backend container image.

This spec covers the pipeline only. It deliberately stops short of wiring a
hosting provider, because the provider choice is still an open item — see
[TODO.md](../../../TODO.md) section 1 and the main spec's "Open Items for
Implementation Planning."

## Scope decision: CI now, CD to the registry only

Backend hosting (Railway vs. Render) and frontend hosting (Vercel vs.
Netlify) are both undecided and both blocking. Writing deploy jobs now would
mean inventing secret names for accounts that do not exist, leaving the repo
with permanently-red workflow steps and dead configuration.

So the delivery pipeline terminates at a container image in GitHub Container
Registry:

```
PR   ──▶ backend tests + frontend checks + CodeQL          (gate only)
main ──▶ ...same... ──▶ docker build ──▶ ghcr.io image     (artifact)
                                             │
                                             ╵ (later) Railway / Render
```

The image is host-agnostic, and the `Dockerfile` that produces it is the
exact input both Railway and Render consume. When section 7 of `TODO.md`
comes up, it becomes wiring rather than authoring.

## Files added

```
.github/workflows/ci.yml        test + build + publish
.github/workflows/codeql.yml    security analysis
backend/Dockerfile              multi-stage build
backend/.dockerignore
```

## Files changed

Coverage instrumentation is the one place this work reaches into project
config rather than staying in `.github/`:

- `backend/pom.xml` — add the JaCoCo plugin.
- `frontend/package.json` — add the `@vitest/coverage-v8` devDependency and
  a `coverage` script.
- `frontend/vitest.config.ts` — coverage reporters and exclusions.

No changes to application source or `application.yml`.

## Coverage

Measurement is in scope; publishing to a third-party service and gating on a
threshold are not (see "Out of scope").

The reasoning is timing. Coverage instrumentation is cheapest to add before
the code exists. Today the backend has `contextLoads()` and
`ScaffoldStubsTests`, and the frontend has a placeholder `App.test.tsx` —
so the first numbers will be near-meaningless, and that is precisely the
point: every subsequent PR shows its own delta against a baseline that
started at nothing. Retrofitting coverage after sections 2–6 of `TODO.md`
land means introducing a number that reads as a verdict on work already
finished, which is a much harder thing to act on.

This matters more than usual for this repo. `ReceiptParser` is described in
`CLAUDE.md` as its most-ownable code and its Vision-fixture tests as the
highest-value test target; a coverage report is the artifact that shows that
intent was carried through. This is also a portfolio piece, so the report
has an audience beyond the author.

- **Backend:** JaCoCo, `prepare-agent` bound before tests and `report` bound
  to `verify`, emitting XML and HTML into `target/site/jacoco/`. The plan
  should pin a JaCoCo release with Java 21 class-file support — older
  versions fail outright on newer bytecode, so this is a hard floor, not a
  preference.
- **Frontend:** `@vitest/coverage-v8` (v8 is Vitest's default provider),
  reporters `text`, `html`, and `lcov`, output to `frontend/coverage/`. A
  `coverage` script (`vitest run --coverage`) keeps the flag out of the
  everyday `npm test` loop so local runs stay fast.

Both reports upload via `actions/upload-artifact` and are retained for the
default period. No badge, no PR comment, no build failure on a low number.

## Workflow: `ci.yml`

**Triggers:** pull requests targeting `main`; pushes to `main`.

**Concurrency:** grouped per ref with `cancel-in-progress`, so a fast
follow-up push to a PR supersedes the in-flight run instead of queueing
behind it.

### Job: `backend`

Runs `./mvnw -B verify` against a `postgres:16` service container whose
database name, user, password, and port match
[docker-compose.yml](../../../docker-compose.yml) exactly.

Both existing tests are `@SpringBootTest` and connect to a hardcoded
`jdbc:postgresql://localhost:5432/receipt_splitter` from `application.yml`.
Because the job's steps run directly on the runner host — not inside a
container — the service's published port 5432 is reachable at `localhost`,
which is precisely what that URL expects. **This is why the pipeline needs
no datasource configuration of its own** — no override env vars, no CI
profile, no second `application.yml`.

The service declares a `pg_isready` healthcheck. Without it, Maven can start
before Postgres accepts connections and the run fails intermittently on a
race rather than on a real defect.

Java 21 Temurin via `actions/setup-java`, with its built-in `cache: maven`.

`verify` also produces the JaCoCo report, which uploads as an artifact. No
extra Maven invocation is needed — binding `report` to `verify` means the
existing command covers it.

### Job: `frontend`

`npm ci`, then `npm run lint` (oxlint), `npm run coverage`
(`vitest run --coverage`), then `npm run build`. The coverage script
replaces a bare `npm run test` in CI rather than running alongside it —
running the suite twice for the same assertions would only cost time.

There is no separate typecheck step because there does not need to be: the
`build` script is `tsc -b && vite build`, so `tsc -b` **is** the typecheck,
and a type error fails the job before Vite runs. Adding a fourth step would
run the compiler twice for the same signal.

Node 22 LTS via `actions/setup-node` with `cache: npm`. Vite 8 requires
Node 20.19+ or 22.12+; 22 is the current LTS and clears that floor.

### Job: `docker`

`needs: [backend, frontend]`, and gated to pushes on `main` — a pull request
never publishes an image. Builds `backend/Dockerfile` and pushes to
`ghcr.io/jacksonfalgoust/receiptparser-backend`, tagged both `sha-<short>`
for immutability and `latest` for convenience.

Authenticates with the automatic `GITHUB_TOKEN` under `packages: write`.
**No secrets to configure manually.**

## Workflow: `codeql.yml`

**Triggers:** pull requests targeting `main`; pushes to `main`; a weekly
cron. The cron matters because CodeQL's query packs are updated
continuously — a repo that only scans on commit stops learning about newly
published vulnerability patterns the moment development pauses.

A matrix over the two languages present:

| Language | Build mode |
|---|---|
| `java-kotlin` | `manual` — explicit `./mvnw -B -DskipTests package` between `init` and `analyze` |
| `javascript-typescript` | `none` |

**Why `manual` and not `autobuild` for Java.** CodeQL needs compiled Java to
build its database, and `autobuild` infers a Maven lifecycle to invoke. If
that inferred lifecycle reaches the `test` phase, the scan inherits this
project's live-Postgres requirement and fails for a reason that has nothing
to do with security. Driving the build explicitly with `-DskipTests` removes
the guess: the security scan never touches a database, and it reuses the
Maven cache.

`build-mode: none` is documented for interpreted languages and is correct
for the TypeScript half.

Default query suite. Permissions scoped to `security-events: write`.

## Container image

Multi-stage `backend/Dockerfile`:

1. **Build stage** — `maven:3.9-eclipse-temurin-21`. Copies `pom.xml` and
   resolves dependencies as its own layer before copying source, so an
   unchanged dependency set is a cache hit. Packages with `-DskipTests`;
   the `backend` job has already run them, and running them here would
   require a database inside the build.
2. **Runtime stage** — `eclipse-temurin:21-jre-alpine`, JRE only, running as
   a non-root user.

`.dockerignore` excludes `target/`, so a local build's output cannot leak
into the image context.

## Rejected alternatives

- **Testcontainers instead of a service container.** Genuinely better
  long-term — it would remove the "`docker compose up -d` must be running"
  precondition for local `mvn test` too. Rejected here because it means a
  new dependency and a test-config change, which is application work, not
  pipeline work. Worth its own task.
- **Spring Boot buildpacks (`spring-boot:build-image`) instead of a
  Dockerfile.** Would keep a Dockerfile out of the repo entirely, but it
  pulls a ~1GB Paketo builder on each run and yields less control over the
  base image. A Dockerfile is also directly consumable by both candidate
  hosts, which the buildpack path would eventually need anyway.
- **Archiving the JAR instead of building an image.** Lightest option, but a
  bare JAR is not deployable to Railway or Render without adding a
  Dockerfile or buildpack step later regardless.

## Out of scope

- Any hosting-provider deploy job (Railway, Render, Vercel, Netlify) and its
  secrets, pending the decision in `TODO.md` section 1.
- Frontend artifact publishing — Vercel and Netlify both build from source
  themselves, so CI builds the frontend to prove it compiles, not to ship a
  bundle.
- Branch protection rules and required-check configuration, which are repo
  settings rather than files.
- **Coverage publishing and gating** — no Codecov or comparable service (an
  external dependency plus a token to manage, and a PR-comment integration
  that is noise until real tests exist), and no minimum-coverage threshold
  that fails a build. A threshold set against stub code would be arbitrary;
  one set to 0% is theater. Revisit once `ReceiptParser` has its Vision
  fixtures and there is a real number to hold a line on.
- Dependency scanning (Dependabot) and release tagging.

## Testing

The pipeline itself is verified by running locally, before commit, the exact
commands the workflows run:

- `docker compose up -d`, then `./mvnw -B verify` — confirms the backend
  suite passes against a real Postgres and that JaCoCo writes a report.
- `npm ci && npm run lint && npm run coverage && npm run build` in
  `frontend/`, confirming `coverage/` is populated.
- `docker build` on `backend/Dockerfile`, confirming the image builds and
  the packaged app starts.
- Workflow YAML linted with `actionlint` if available.

The GitHub-hosted runner environment cannot be executed locally, so final
confirmation of the workflows themselves is the first PR run. That boundary
is stated rather than glossed: local passes prove the commands are right,
not that the runner configuration is.
