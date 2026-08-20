# CI/CD Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add GitHub Actions workflows that test both halves of the monorepo with coverage, run CodeQL over both languages, and publish a backend container image to GHCR on merges to `main`.

**Architecture:** Two workflow files. `ci.yml` runs a `backend` job (Maven against a Postgres service container) and a `frontend` job (npm) in parallel, then a `docker` job gated to pushes on `main`. `codeql.yml` runs a two-language matrix on its own schedule. Coverage instrumentation is added to `pom.xml` and the Vitest config so the existing test commands emit reports, which upload as workflow artifacts.

**Tech Stack:** GitHub Actions, Maven + Spring Boot 4.1.0 (Java 21), Vite/Vitest 4 (Node 22), JaCoCo 0.8.15, `@vitest/coverage-v8`, Docker multi-stage build, GHCR.

**Spec:** [docs/superpowers/specs/2026-08-19-ci-cd-pipeline-design.md](../specs/2026-08-19-ci-cd-pipeline-design.md)

## Global Constraints

- **Branch:** all work lands on `feature/ci-cd`. Never commit to `main`.
- **Commits:** never add a `Co-Authored-By` trailer (per `CLAUDE.md`). Ask before committing.
- **Java:** CI uses Temurin **21**. `pom.xml` targets release 21 and must not change.
- **Node:** CI uses **22** (Vite 8 requires Node 20.19+ or 22.12+).
- **JaCoCo:** pin **0.8.15**. Java 21 support landed in 0.8.11; 0.8.13+ is required for the JDK 24 many contributors run locally. 0.8.15 is current.
- **Postgres:** `postgres:16`, database/user/password all `receipt_splitter`, published on port **5432** — identical to `docker-compose.yml`. Do not add datasource overrides; `application.yml` already points at `localhost:5432`.
- **Action versions (verified current 2026-08-19):** `actions/checkout@v7`, `actions/setup-java@v5`, `actions/setup-node@v7`, `actions/upload-artifact@v7`, `github/codeql-action@v4`, `docker/login-action@v4`, `docker/metadata-action@v6`, `docker/build-push-action@v7`.
- **Image:** `ghcr.io/<owner>/receiptparser-backend`, tags `sha-<short>` and `latest`. GHCR rejects uppercase — the owner is `JacksonFalgoust`, so the name **must** be lowercased. `docker/metadata-action` does this automatically; a hand-rolled `docker build -t` would not.
- **No coverage thresholds, no third-party coverage service, no hosting deploy jobs.** All deferred (see Task 6).

## Preflight

Local verification needs Docker Desktop, which was **not running** when this plan was written. Start it before Task 1 — `docker compose up -d` and `docker build` both depend on it.

Local JDK is 24 while CI uses 21. The build targets release 21 either way, so bytecode matches, but the local *test JVM* differs. This is why JaCoCo 0.8.15 (not 0.8.11) is pinned.

## Note on TDD for this plan

Workflow YAML has no unit test — nothing meaningful asserts against a `.yml` file without running it on a runner. So the red/green cycle here is: **run the exact command the workflow will run, and confirm the artifact it depends on appears.** Tasks 1–3 follow real TDD against real commands. Tasks 4–5 substitute static validation (`actionlint`) plus the fact that their underlying commands were already proven green in Tasks 1–3. The genuine end-to-end confirmation is the first PR run, and the plan says so rather than pretending otherwise.

## File Structure

| File | Responsibility |
|---|---|
| `backend/pom.xml` (modify) | JaCoCo plugin — coverage agent + report |
| `frontend/package.json` (modify) | `@vitest/coverage-v8` dep, `coverage` script |
| `frontend/vitest.config.ts` (modify) | Coverage provider, reporters, exclusions |
| `backend/Dockerfile` (create) | Multi-stage build → runnable image |
| `backend/.dockerignore` (create) | Keep local `target/` out of build context |
| `.github/workflows/ci.yml` (create) | Test, coverage, image publish |
| `.github/workflows/codeql.yml` (create) | Security analysis, two languages |
| `TODO.md` (modify) | Deferred CI/CD work |

---

### Task 1: Backend coverage + fix the `mvnw` permission bit

**Files:**
- Modify: `backend/pom.xml` (the `<build><plugins>` block)
- Modify (file mode only): `backend/mvnw`

**Interfaces:**
- Consumes: nothing.
- Produces: `backend/target/site/jacoco/{index.html,jacoco.xml,jacoco.csv}` after `./mvnw -B verify`. Task 4 uploads this path.

- [ ] **Step 1: Start Postgres and establish a green baseline**

```bash
docker compose up -d
cd backend && ./mvnw -B verify
```

Expected: BUILD SUCCESS, 2 tests run (`ReceiptSplitterApplicationTests`, `ScaffoldStubsTests`). If this fails, stop — it is a pre-existing problem, not something this plan introduced.

- [ ] **Step 2: Confirm no coverage report exists (the failing test)**

```bash
ls backend/target/site/jacoco/
```

Expected: FAIL — "No such file or directory".

- [ ] **Step 3: Add the JaCoCo plugin**

In `backend/pom.xml`, inside `<build><plugins>`, after the existing `spring-boot-maven-plugin`:

```xml
			<plugin>
				<groupId>org.jacoco</groupId>
				<artifactId>jacoco-maven-plugin</artifactId>
				<version>0.8.15</version>
				<executions>
					<execution>
						<id>prepare-agent</id>
						<goals>
							<goal>prepare-agent</goal>
						</goals>
					</execution>
					<execution>
						<id>report</id>
						<phase>verify</phase>
						<goals>
							<goal>report</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
```

`prepare-agent` defaults to the `initialize` phase, so it is armed before Surefire runs. `report` is bound to `verify` so the existing CI command produces it with no extra Maven invocation.

- [ ] **Step 4: Run to verify the report appears**

```bash
cd backend && ./mvnw -B verify && ls target/site/jacoco/
```

Expected: PASS — `index.html`, `jacoco.xml`, and `jacoco.csv` all listed. Coverage numbers will be near-zero; that is expected and is the baseline the ratchet starts from.

- [ ] **Step 5: Make `mvnw` executable in git**

`backend/mvnw` is currently committed as mode `100644`. On the Ubuntu runner `./mvnw` would fail with "Permission denied" before Maven ever starts.

```bash
git update-index --chmod=+x backend/mvnw
git ls-files -s backend/mvnw
```

Expected: mode reads `100755`.

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml
git commit -m "ci: add JaCoCo coverage and make mvnw executable"
git show --stat --format="" HEAD | grep mvnw
```

Do **not** `git add backend/mvnw` — Step 5 already staged it via `update-index`, and re-adding it on a machine with `core.filemode=false` (the Windows default) risks dropping the mode change back to `100644`. The `git show` line confirms the mode change made it into the commit.

---

### Task 2: Frontend coverage

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vitest.config.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `npm run coverage` script; report at `frontend/coverage/` (includes `lcov.info`). Task 4 uploads this path.

- [ ] **Step 1: Confirm the script does not exist (the failing test)**

```bash
cd frontend && npm run coverage
```

Expected: FAIL — `npm error Missing script: "coverage"`.

- [ ] **Step 2: Install the coverage provider**

```bash
cd frontend && npm install -D "@vitest/coverage-v8@^4.1.11"
```

The major version must track `vitest` (currently `^4.1.11`). Vitest refuses to run when the provider major diverges.

- [ ] **Step 3: Add the `coverage` script**

In `frontend/package.json`, in `"scripts"`, after `"build"`:

```json
    "coverage": "vitest run --coverage",
```

Kept separate from `"test"` so the everyday local loop (`npm run test`) stays fast; CI calls `coverage` instead.

- [ ] **Step 4: Configure coverage**

Replace the body of `frontend/vitest.config.ts` with:

```ts
import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config.ts'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html', 'lcov'],
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx'],
      },
    },
  }),
)
```

`main.tsx` is excluded because it is the DOM mount entrypoint — it cannot be unit tested and would permanently drag the number down for no signal.

- [ ] **Step 5: Run to verify**

```bash
cd frontend && npm run coverage && ls coverage/
```

Expected: PASS — a coverage table prints, and `coverage/` contains `lcov.info` and `index.html`.

- [ ] **Step 6: Confirm `coverage/` is git-ignored**

```bash
cd frontend && git check-ignore -v coverage/ || echo "NOT IGNORED - add it"
```

If not ignored, append `coverage` to `frontend/.gitignore`.

- [ ] **Step 7: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vitest.config.ts frontend/.gitignore
git commit -m "ci: add Vitest coverage reporting"
```

---

### Task 3: Backend Dockerfile

**Files:**
- Create: `backend/Dockerfile`
- Create: `backend/.dockerignore`

**Interfaces:**
- Consumes: nothing.
- Produces: an image exposing `8080`, entrypoint `java -jar /app/app.jar`. Task 4 builds it with `context: ./backend`.

- [ ] **Step 1: Confirm the build fails (the failing test)**

```bash
docker build -t receiptparser-backend:dev ./backend
```

Expected: FAIL — "failed to read dockerfile".

- [ ] **Step 2: Write `backend/.dockerignore`**

```
target/
.mvn/
mvnw
mvnw.cmd
HELP.md
*.md
.gitignore
.gitattributes
```

The build stage uses the Maven image's own `mvn`, so the wrapper is not needed in the context. Excluding `target/` keeps a local build's output from leaking into the image.

- [ ] **Step 3: Write `backend/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Dependencies resolve in their own layer before `src` is copied, so a source-only change is a cache hit. Tests are skipped here because the `backend` CI job already ran them and running them in the image build would require a database inside the build.

If `dependency:go-offline` fails to resolve a plugin dependency (a known rough edge with some Spring Boot setups), replace that line with `RUN mvn -B -q dependency:resolve` — it is a cache optimization, not a correctness requirement.

- [ ] **Step 4: Build and verify the jar landed**

```bash
docker build -t receiptparser-backend:dev ./backend
docker run --rm --entrypoint sh receiptparser-backend:dev -c "ls -l /app/app.jar && id"
```

Expected: PASS — `app.jar` listed with non-zero size, and `id` reports `uid=... (app)` confirming it does not run as root.

- [ ] **Step 5: Verify the image actually boots against Postgres**

```bash
docker network ls --filter name=postgres --format "{{.Name}}"
docker run --rm --network receiptparser_default \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/receipt_splitter \
  -p 8080:8080 receiptparser-backend:dev
```

If the network name differs, take the real one from the `docker network ls` output above.

Expected: PASS — Spring Boot banner, then "Started ReceiptSplitterApplication". Stop with Ctrl+C. This proves the packaged jar runs, not merely that it compiled.

- [ ] **Step 6: Commit**

```bash
git add backend/Dockerfile backend/.dockerignore
git commit -m "ci: add multi-stage Dockerfile for backend"
```

---

### Task 4: `ci.yml`

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `./mvnw -B verify` (Task 1), `npm run coverage` (Task 2), `backend/Dockerfile` (Task 3).
- Produces: artifacts `backend-coverage` and `frontend-coverage`; image `ghcr.io/<owner>/receiptparser-backend`.

- [ ] **Step 1: Write the workflow**

```yaml
name: CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  backend:
    name: Backend tests
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_DB: receipt_splitter
          POSTGRES_USER: receipt_splitter
          POSTGRES_PASSWORD: receipt_splitter
        ports:
          - 5432:5432
        options: >-
          --health-cmd "pg_isready -U receipt_splitter -d receipt_splitter"
          --health-interval 5s
          --health-timeout 5s
          --health-retries 10
    steps:
      - uses: actions/checkout@v7

      - name: Set up Java 21
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Test with coverage
        working-directory: backend
        run: ./mvnw -B verify

      - name: Upload coverage report
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: backend-coverage
          path: backend/target/site/jacoco/

  frontend:
    name: Frontend checks
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v7

      - name: Set up Node 22
        uses: actions/setup-node@v7
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - run: npm ci
      - run: npm run lint
      - run: npm run coverage
      - run: npm run build

      - name: Upload coverage report
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: frontend-coverage
          path: frontend/coverage/

  docker:
    name: Build and publish image
    needs: [backend, frontend]
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v7

      - name: Log in to GHCR
        uses: docker/login-action@v4
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Derive tags
        id: meta
        uses: docker/metadata-action@v6
        with:
          images: ghcr.io/${{ github.repository_owner }}/receiptparser-backend
          tags: |
            type=sha,prefix=sha-
            type=raw,value=latest

      - name: Build and push
        uses: docker/build-push-action@v7
        with:
          context: ./backend
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

`upload-artifact` uses `if: always()` so a failing test run still surfaces its coverage report — that is exactly when it is most useful.

`docker/metadata-action` lowercases the image name, which is required because the owner `JacksonFalgoust` contains uppercase and GHCR rejects it.

- [ ] **Step 2: Validate the YAML**

```bash
docker run --rm -v "$PWD:/repo" --workdir /repo rhysd/actionlint:latest -color
```

Expected: PASS — no output, exit 0. Fix anything reported before continuing.

- [ ] **Step 3: Re-run the underlying commands once more**

```bash
docker compose up -d
cd backend && ./mvnw -B verify
cd ../frontend && npm ci && npm run lint && npm run coverage && npm run build
```

Expected: all green. This is the real proof the workflow's commands are correct; the runner configuration itself is only confirmed by the first PR run.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add test, coverage, and image publish workflow"
```

---

### Task 5: `codeql.yml`

**Files:**
- Create: `.github/workflows/codeql.yml`

**Interfaces:**
- Consumes: `backend/mvnw` executable bit (Task 1).
- Produces: security alerts on the repo's Security tab.

- [ ] **Step 1: Write the workflow**

```yaml
name: CodeQL

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
  schedule:
    - cron: '0 6 * * 1'

concurrency:
  group: codeql-${{ github.ref }}
  cancel-in-progress: true

jobs:
  analyze:
    name: Analyze ${{ matrix.language }}
    runs-on: ubuntu-latest
    permissions:
      security-events: write
      contents: read
      actions: read
    strategy:
      fail-fast: false
      matrix:
        include:
          - language: java-kotlin
            build-mode: manual
          - language: javascript-typescript
            build-mode: none
    steps:
      - uses: actions/checkout@v7

      - name: Set up Java 21
        if: matrix.language == 'java-kotlin'
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Initialize CodeQL
        uses: github/codeql-action/init@v4
        with:
          languages: ${{ matrix.language }}
          build-mode: ${{ matrix.build-mode }}

      - name: Build Java (manual build mode)
        if: matrix.build-mode == 'manual'
        working-directory: backend
        run: ./mvnw -B -DskipTests package

      - name: Analyze
        uses: github/codeql-action/analyze@v4
        with:
          category: /language:${{ matrix.language }}
```

`fail-fast: false` so a Java failure does not cancel the TypeScript analysis. The weekly cron matters because CodeQL's query packs update continuously — without it the repo stops learning about new patterns whenever development pauses.

The `manual` build mode with `-DskipTests` is deliberate: `autobuild` may infer a Maven lifecycle that reaches `test`, which would make the security scan depend on a live Postgres.

- [ ] **Step 2: Validate**

```bash
docker run --rm -v "$PWD:/repo" --workdir /repo rhysd/actionlint:latest -color
```

Expected: PASS — no output, exit 0.

- [ ] **Step 3: Verify the manual build command works with no database**

Stop Postgres first, so the claim is actually tested rather than assumed:

```bash
docker compose down
cd backend && ./mvnw -B -DskipTests package
```

Expected: BUILD SUCCESS with nothing listening on 5432 — proving the security scan does not depend on a database. This is the whole reason `build-mode: manual` was chosen over `autobuild`, so it is worth confirming rather than trusting.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/codeql.yml
git commit -m "ci: add CodeQL analysis for Java and TypeScript"
```

---

### Task 6: Record deferred CI/CD work in `TODO.md`

**Files:**
- Modify: `TODO.md`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Replace section 7 with a version that references the pipeline**

`TODO.md` section 7 currently reads "Deployment". Replace its body with:

```markdown
## 7. Deployment

CI is in place (see
[the CI/CD spec](docs/superpowers/specs/2026-08-19-ci-cd-pipeline-design.md)):
every PR runs both test suites with coverage plus CodeQL, and every merge to
`main` publishes `ghcr.io/jacksonfalgoust/receiptparser-backend`. What
remains is pointing a host at that image.

- [ ] Backend → Railway or Render (per decision in section 1), pulling the
      published GHCR image rather than building from source
- [ ] Postgres instance on the same host, with `SPRING_DATASOURCE_URL`,
      username, and password injected as environment variables
- [ ] Frontend → Vercel or Netlify, with `VITE_API_BASE_URL` pointed at the
      deployed backend
- [ ] Vision API key and DB creds as server-side env vars/secrets, never
      shipped to the client
- [ ] Add the deploy job to `ci.yml`, gated on the `docker` job succeeding
```

- [ ] **Step 2: Add a new section 9 after "8. Optional polish"**

```markdown
## 9. CI/CD follow-ups

Deliberately deferred when the pipeline was built. Roughly in the order
they become worth doing.

**Blocked on real tests existing (sections 2–6):**

- [ ] Coverage thresholds — add `jacoco:check` rules and Vitest
      `coverage.thresholds` once `ReceiptParser` has its Vision fixtures.
      A threshold against today's stub code would be arbitrary; one set to
      0% is theater.
- [ ] Publish coverage somewhere visible — Codecov, or an HTML report
      pushed to GitHub Pages. Adds an external dependency and a token, so
      it only pays for itself once the number means something.
- [ ] Playwright happy-path smoke test in CI (see section 8) — needs the
      full stack running in the workflow, so it lands after deployment.

**Blocked on deployment (section 7):**

- [ ] Deploy job consuming the GHCR image, gated on `docker` succeeding.
- [ ] Post-deploy smoke check against the live URL, so a green pipeline
      means "it is actually serving", not just "it built".
- [ ] Staging environment and a manual promotion gate to production.

**Not blocked — can be done any time:**

- [ ] Dependabot (`.github/dependabot.yml`) for three ecosystems: `maven`
      in `/backend`, `npm` in `/frontend`, and `github-actions` in `/`.
      The third matters most — it is what stops the pinned action versions
      from silently going stale.
- [ ] Branch protection on `main`: require the `Backend tests`,
      `Frontend checks`, and both `Analyze` checks to pass before merge.
      This is a repo setting, not a file, so it cannot be committed.
- [ ] Testcontainers for the backend suite, replacing the Postgres service
      container and removing the "`docker compose up -d` must be running"
      precondition for local `mvn test` too. Genuinely better than the
      current setup; kept out of the CI change because it is application
      config, not pipeline config.
- [ ] Pin actions by commit SHA instead of major tag, for supply-chain
      hardening. Dependabot can keep the SHAs current.
- [ ] Container image scanning (Trivy or Grype) on the built image, and
      SBOM generation via `docker/build-push-action`'s `sbom: true`.
      CodeQL covers source; neither covers the base image's OS packages.
- [ ] Multi-arch image (`linux/amd64,linux/arm64`) via QEMU + Buildx —
      only needed if the chosen host runs ARM.
- [ ] Release tagging: semver tags on `main` producing versioned image
      tags alongside `sha-` and `latest`.
- [ ] Enable secret scanning and push protection in repo settings.
```

- [ ] **Step 3: Verify the links resolve**

```bash
grep -n "2026-08-19-ci-cd-pipeline-design" TODO.md
ls docs/superpowers/specs/2026-08-19-ci-cd-pipeline-design.md
```

Expected: both succeed.

- [ ] **Step 4: Commit**

```bash
git add TODO.md
git commit -m "docs: record deferred CI/CD work in TODO"
```

---

### Task 7: Open the pull request

**Files:** none.

- [ ] **Step 1: Confirm the full local suite is green**

```bash
docker compose up -d
cd backend && ./mvnw -B verify
cd ../frontend && npm ci && npm run lint && npm run coverage && npm run build
cd .. && docker build -t receiptparser-backend:dev ./backend
```

Expected: all green. Do not proceed otherwise.

- [ ] **Step 2: Push and open the PR** (ask before running — `CLAUDE.md` requires it)

```bash
git push -u origin feature/ci-cd
gh pr create --base main --title "Add CI/CD pipeline" --body "Implements docs/superpowers/specs/2026-08-19-ci-cd-pipeline-design.md"
```

- [ ] **Step 3: Watch the first run — this is the real verification**

```bash
gh run watch
```

The `docker` job will be skipped (correctly — it is gated to pushes on `main`). Expect `Backend tests`, `Frontend checks`, and two `Analyze` jobs. The image publish only happens after merge.

- [ ] **Step 4: Delete the branch after merge** (per `CLAUDE.md`)

```bash
git checkout main && git pull && git branch -d feature/ci-cd
```
