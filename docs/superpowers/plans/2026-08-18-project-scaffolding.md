# Project Scaffolding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up runnable, empty-but-wired skeletons for the Spring Boot backend and the React frontend, plus a local Postgres via Docker Compose, so later feature work has a project to build inside.

**Architecture:** Monorepo with `backend/` (Maven, Spring Boot) and `frontend/` (Vite, React + TypeScript) as sibling folders under the repo root, alongside a root `docker-compose.yml` for local Postgres. Backend gets stub entity/controller/config classes matching the main spec's architecture diagram; frontend gets stub route components wired into a router. No domain logic, REST handlers, WebSocket handlers, or Vision API integration in this pass.

**Tech Stack:** Spring Boot 4.0.x (GA, Java 21, Maven), Spring Web/WebSocket/Data JPA, PostgreSQL driver, Validation, DevTools; Postgres 16 via Docker Compose; Vite 7 + React + TypeScript, react-router, @tanstack/react-query, zustand, @stomp/stompjs + sockjs-client; Vitest + Testing Library.

**Spec:** [docs/superpowers/specs/2026-08-18-project-scaffolding-design.md](../specs/2026-08-18-project-scaffolding-design.md) (scaffolding decisions), consistent with [ARCHITECTURE.md](../../../ARCHITECTURE.md) (product architecture).

## Global Constraints

- Repo layout: monorepo, `backend/`, `frontend/`, and `docker-compose.yml` as siblings at the repo root.
- Backend: Spring Boot 4.0.x GA line (not a 4.1 milestone/RC), Java 21, Maven, groupId `com.jacksonfalgoust`, artifactId `receipt-splitter`, base package `com.jacksonfalgoust.receiptsplitter`.
- Backend dependencies: Spring Web, Spring WebSocket, Spring Data JPA, PostgreSQL driver, Validation, Spring Boot DevTools — no others.
- Frontend: Vite 7 + React + TypeScript template, npm as package manager.
- Postgres: Docker Compose running `postgres:16`, local dev only — not a production hosting decision.
- No domain/business logic, no implemented REST or WebSocket handlers, no Google Vision integration — stub classes only in this pass.

---

## File Structure

```
ReceiptParser/
├─ docker-compose.yml
├─ backend/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/
│     │  ├─ java/com/jacksonfalgoust/receiptsplitter/
│     │  │  ├─ ReceiptSplitterApplication.java
│     │  │  ├─ bill/Bill.java, BillController.java
│     │  │  ├─ item/Item.java
│     │  │  ├─ participant/Participant.java
│     │  │  ├─ claim/ItemClaim.java
│     │  │  ├─ receipt/ReceiptController.java, ReceiptParser.java
│     │  │  ├─ websocket/BillWebSocketConfig.java
│     │  │  └─ config/WebConfig.java
│     │  └─ resources/application.yml
│     └─ test/java/com/jacksonfalgoust/receiptsplitter/
│        ├─ ReceiptSplitterApplicationTests.java   (generated)
│        └─ ScaffoldStubsTests.java
└─ frontend/
   ├─ .env.example
   ├─ package.json / vite.config.ts / vitest.config.ts
   └─ src/
      ├─ main.tsx, App.tsx, App.test.tsx
      ├─ test/setup.ts
      └─ routes/UploadReceipt.tsx, ReviewItems.tsx, BillRoom.tsx, Summary.tsx
```

---

### Task 1: Docker Compose Postgres for local dev

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Produces: a Postgres 16 instance reachable at `localhost:5432`, database `receipt_splitter`, user/password `receipt_splitter`/`receipt_splitter` — Task 2's `application.yml` connects to this.

- [ ] **Step 1: Write `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:16
    container_name: receipt-splitter-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: receipt_splitter
      POSTGRES_USER: receipt_splitter
      POSTGRES_PASSWORD: receipt_splitter
    ports:
      - "5432:5432"
    volumes:
      - receipt-splitter-postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U receipt_splitter -d receipt_splitter"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  receipt-splitter-postgres-data:
```

- [ ] **Step 2: Validate the compose file and bring Postgres up**

Run: `docker compose config` — expect it to print the parsed config with no errors.

Then run: `docker compose up -d` followed by `docker compose ps` — expect the `postgres` service listed as `healthy` (may take a few seconds after `up`; re-run `docker compose ps` if it still shows `starting`).

Leave the container running — Task 2 and Task 3's backend tests connect to it.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "chore: add docker-compose Postgres for local dev"
```

---

### Task 2: Backend Maven skeleton, boots against Postgres

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/ReceiptSplitterApplication.java` (generated)
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/ReceiptSplitterApplicationTests.java` (generated)

**Interfaces:**
- Consumes: Postgres from Task 1 at `localhost:5432` / db `receipt_splitter` / user+password `receipt_splitter`.
- Produces: a booting Spring Boot app on port 8080, base package `com.jacksonfalgoust.receiptsplitter` — Task 3's stub classes live under this package.

This task validates that the scaffold boots against a real database rather than following red-green TDD — there's no behavior yet to drive with a failing test.

- [ ] **Step 1: Generate the Maven project from Spring Initializr**

Run from the repo root (no `bootVersion` param, so Initializr picks its current recommended GA release rather than a milestone/RC build):

```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d javaVersion=21 \
  -d groupId=com.jacksonfalgoust \
  -d artifactId=receipt-splitter \
  -d name=ReceiptSplitter \
  -d packageName=com.jacksonfalgoust.receiptsplitter \
  -d dependencies=web,websocket,data-jpa,postgresql,validation,devtools \
  -o backend.zip
unzip backend.zip -d backend
rm backend.zip
```

Expected: a `backend/` directory containing `pom.xml`, `src/main/java/com/jacksonfalgoust/receiptsplitter/ReceiptSplitterApplication.java`, `src/main/resources/application.properties`, and `src/test/java/com/jacksonfalgoust/receiptsplitter/ReceiptSplitterApplicationTests.java`.

Open the generated `pom.xml` and confirm the `spring-boot-starter-parent` version starts with `4.0.` — if it doesn't (Initializr's default has moved on), stop and flag it rather than proceeding on an unexpected major version.

- [ ] **Step 2: Replace `application.properties` with `application.yml`**

Delete `backend/src/main/resources/application.properties`, then create `backend/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: receipt-splitter
  datasource:
    url: jdbc:postgresql://localhost:5432/receipt_splitter
    username: receipt_splitter
    password: receipt_splitter
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true

server:
  port: 8080

---
spring:
  config:
    activate:
      on-profile: test
```

- [ ] **Step 3: Run the generated context-loads test against real Postgres**

Confirm Task 1's Postgres container is still running (`docker compose ps`), then run: `cd backend && mvn test`

Expected: `BUILD SUCCESS`, with `ReceiptSplitterApplicationTests > contextLoads()` passing.

- [ ] **Step 4: Commit**

```bash
git add backend
git commit -m "chore: scaffold Spring Boot backend, boots against local Postgres"
```

---

### Task 3: Backend domain/component stubs

**Files:**
- Test: `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/ScaffoldStubsTests.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillController.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/Item.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/Participant.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaim.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/receipt/ReceiptController.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/receipt/ReceiptParser.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/websocket/BillWebSocketConfig.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/config/WebConfig.java`

**Interfaces:**
- Consumes: the booting app from Task 2.
- Produces: four `@Entity` classes (`Bill`, `Item`, `Participant`, `ItemClaim`) and empty `@RestController`/`@Configuration` stubs, matching the component names in the main spec's architecture diagram — later feature plans add fields and logic to these same classes rather than creating new ones.

- [ ] **Step 1: Write the failing test**

```java
package com.jacksonfalgoust.receiptsplitter;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ScaffoldStubsTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void jpaRecognizesAllFourDomainEntities() {
        Set<String> entityNames = entityManager.getMetamodel().getEntities().stream()
                .map(type -> type.getJavaType().getSimpleName())
                .collect(Collectors.toSet());

        assertThat(entityNames).containsExactlyInAnyOrder("Bill", "Item", "Participant", "ItemClaim");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=ScaffoldStubsTests`
Expected: FAIL — `entityNames` is empty, doesn't contain `Bill`, `Item`, `Participant`, `ItemClaim`.

- [ ] **Step 3: Create the four entity stubs**

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`:

```java
package com.jacksonfalgoust.receiptsplitter.bill;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields (roomCode, payerName, subtotal, tax, tip, total, status,
    // createdAt, expiresAt) and accessors are added when the bill-creation
    // implementation work begins. See ARCHITECTURE.md.
}
```

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/Item.java`:

```java
package com.jacksonfalgoust.receiptsplitter.item;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields (billId, name, price, quantity) and accessors are added when
    // the receipt-parsing implementation work begins.
}
```

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/Participant.java`:

```java
package com.jacksonfalgoust.receiptsplitter.participant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Participant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields (billId, name, sessionToken) and accessors are added when the
    // join-a-bill implementation work begins.
}
```

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaim.java`:

```java
package com.jacksonfalgoust.receiptsplitter.claim;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class ItemClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields (itemId, participantId) and accessors are added when the
    // claim/unclaim implementation work begins. A shared item has multiple
    // rows; an exclusively-claimed item has one — no special-casing.
}
```

- [ ] **Step 4: Run test to verify the entity assertion passes**

Run: `cd backend && mvn test -Dtest=ScaffoldStubsTests`
Expected: PASS.

- [ ] **Step 5: Create the controller, parser, websocket, and CORS config stubs**

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillController.java`:

```java
package com.jacksonfalgoust.receiptsplitter.bill;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class BillController {
    // Endpoints (create bill, get bill, list/post claims) are added when
    // the REST API implementation work begins.
}
```

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/receipt/ReceiptController.java`:

```java
package com.jacksonfalgoust.receiptsplitter.receipt;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReceiptController {
    // The receipt upload endpoint (POST /api/bills, multipart) is added
    // when Google Cloud Vision integration work begins.
}
```

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/receipt/ReceiptParser.java`:

```java
package com.jacksonfalgoust.receiptsplitter.receipt;

import org.springframework.stereotype.Component;

@Component
public class ReceiptParser {
    // Row-grouping by y-coordinate and trailing-price regex parsing are
    // added when OCR integration work begins. See ARCHITECTURE.md
    // ("Receipt OCR & Parsing Pipeline") for the algorithm this implements.
}
```

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/websocket/BillWebSocketConfig.java`:

```java
package com.jacksonfalgoust.receiptsplitter.websocket;

import org.springframework.context.annotation.Configuration;

@Configuration
public class BillWebSocketConfig {
    // STOMP broker registration (topic: /topic/bills/{roomCode}) is added
    // when realtime sync implementation work begins.
}
```

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/config/WebConfig.java` (CORS for the Vite dev server, so the frontend can call the backend once real endpoints exist):

```java
package com.jacksonfalgoust.receiptsplitter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
```

- [ ] **Step 6: Run the full backend test suite to confirm the new beans don't break the context**

Run: `cd backend && mvn test`
Expected: `BUILD SUCCESS`, both `ReceiptSplitterApplicationTests` and `ScaffoldStubsTests` passing.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add backend domain entity and component stubs"
```

---

### Task 4: Frontend Vite scaffold

**Files:**
- Create: `frontend/` (generated Vite React+TS project)
- Create: `frontend/.env.example`

**Interfaces:**
- Produces: a buildable Vite React+TS app at `frontend/` — Task 5 adds dependencies and route stubs inside it.

This task validates that the scaffold builds rather than following red-green TDD — there's no behavior yet to drive with a failing test.

- [ ] **Step 1: Generate the Vite project**

Run from the repo root:

```bash
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
```

Expected: a `frontend/` directory with `package.json`, `vite.config.ts`, `tsconfig.json`, and `src/` (containing the default `App.tsx`, `main.tsx`, etc.).

- [ ] **Step 2: Verify the default scaffold builds**

Run: `cd frontend && npm run build`
Expected: exit code 0, `frontend/dist/` produced.

- [ ] **Step 3: Add `.env.example`**

`frontend/.env.example`:

```
VITE_API_BASE_URL=http://localhost:8080
```

- [ ] **Step 4: Commit**

```bash
git add frontend
git commit -m "chore: scaffold Vite React+TS frontend"
```

---

### Task 5: Frontend dependencies, route stubs, and router wiring

**Files:**
- Create: `frontend/vitest.config.ts`
- Create: `frontend/src/test/setup.ts`
- Create: `frontend/src/routes/UploadReceipt.tsx`
- Create: `frontend/src/routes/ReviewItems.tsx`
- Create: `frontend/src/routes/BillRoom.tsx`
- Create: `frontend/src/routes/Summary.tsx`
- Test: `frontend/src/App.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/main.tsx`
- Modify: `frontend/package.json` (add `test` script)

**Interfaces:**
- Consumes: the Vite scaffold from Task 4.
- Produces: a `App` component whose route tree (`/`, `/review`, `/bill/:roomCode`, `/bill/:roomCode/summary`) later feature plans replace stub content in, without changing route paths or file locations.

- [ ] **Step 1: Install runtime and dev dependencies**

```bash
cd frontend
npm install @tanstack/react-query react-router zustand @stomp/stompjs sockjs-client
npm install -D @types/sockjs-client vitest @testing-library/react @testing-library/jest-dom jsdom
```

- [ ] **Step 2: Add the Vitest config and jest-dom setup file**

`frontend/vitest.config.ts`:

```ts
import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
    },
  }),
)
```

`frontend/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest'
```

Add a `test` script to `frontend/package.json`'s `"scripts"` block:

```json
"test": "vitest run"
```

- [ ] **Step 3: Write the failing smoke test**

`frontend/src/App.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import App from './App'

describe('App routing', () => {
  it('renders the upload screen at the root route', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: /upload receipt/i })).toBeInTheDocument()
  })

  it('renders the bill room screen for a room code route', () => {
    render(
      <MemoryRouter initialEntries={['/bill/ABC123']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: /bill room/i })).toBeInTheDocument()
  })
})
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd frontend && npm test`
Expected: FAIL — `App.tsx` still renders the default Vite scaffold content, no heading matching `/upload receipt/i` or `/bill room/i` exists.

- [ ] **Step 5: Create the four route stub components**

`frontend/src/routes/UploadReceipt.tsx`:

```tsx
function UploadReceipt() {
  return <h1>Upload Receipt</h1>
}

export default UploadReceipt
```

`frontend/src/routes/ReviewItems.tsx`:

```tsx
function ReviewItems() {
  return <h1>Review Items</h1>
}

export default ReviewItems
```

`frontend/src/routes/BillRoom.tsx`:

```tsx
function BillRoom() {
  return <h1>Bill Room</h1>
}

export default BillRoom
```

`frontend/src/routes/Summary.tsx`:

```tsx
function Summary() {
  return <h1>Summary</h1>
}

export default Summary
```

- [ ] **Step 6: Wire the router into `App.tsx`**

Replace the contents of `frontend/src/App.tsx`:

```tsx
import { Routes, Route } from 'react-router'
import UploadReceipt from './routes/UploadReceipt'
import ReviewItems from './routes/ReviewItems'
import BillRoom from './routes/BillRoom'
import Summary from './routes/Summary'

function App() {
  return (
    <Routes>
      <Route path="/" element={<UploadReceipt />} />
      <Route path="/review" element={<ReviewItems />} />
      <Route path="/bill/:roomCode" element={<BillRoom />} />
      <Route path="/bill/:roomCode/summary" element={<Summary />} />
    </Routes>
  )
}

export default App
```

- [ ] **Step 7: Wrap the app with `BrowserRouter` and `QueryClientProvider` in `main.tsx`**

Replace the contents of `frontend/src/main.tsx`:

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App.tsx'
import './index.css'

const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd frontend && npm test`
Expected: PASS, both assertions in `App.test.tsx`.

- [ ] **Step 9: Verify the full app still builds**

Run: `cd frontend && npm run build`
Expected: exit code 0.

- [ ] **Step 10: Commit**

```bash
git add frontend
git commit -m "feat: wire frontend dependencies and route stubs"
```

---

## Self-Review Notes

- **Spec coverage:** Repo layout (Task 1/2/4 file placement), versions/tooling (Global Constraints + Task 2/4 steps), backend dependencies and package structure (Task 2/3), frontend dependencies and route stubs (Task 4/5), Postgres via Docker Compose (Task 1) — all covered. Out-of-scope items (room-code scheme, expiry cleanup, Vision integration, real handlers, deployment hosting) are deliberately untouched, per the scaffolding spec.
- **Placeholder scan:** No TBD/TODO markers; every stub class has a concrete, compilable body and a comment naming the specific future task that fills it in (not a vague "add logic here").
- **Type consistency:** Entity/package names used in `ScaffoldStubsTests` (`Bill`, `Item`, `Participant`, `ItemClaim`) match the classes created in Task 3 Step 3. Route component names/paths used in `App.test.tsx` and `App.tsx` match the files created in Task 5 Step 5.
