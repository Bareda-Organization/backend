# CLAUDE.md — backend repo

The **backend** (Spring Boot) repo of the Baraeda platform. It is used **cloned inside the workspace folder (`baraeda/`)** together with `web` · `mobile` of the same org (`Bareda-Organization`) — that folder's `CLAUDE.md` holds the overall rules, and **all documents are in `../docs/`**.

- The Spring application is this repo's `backend/` subfolder (the repo root holds `infra/` · `docker-compose*.yml` · `scripts/`)
- Read the spec and design first — `../docs/README.md` → `../docs/planning/FEATURE_SPEC.md` · `../docs/planning/API_SPEC.md` · `../docs/backend/ARCHITECTURE.md` · `../docs/backend/ERD.md`
- Code rules: `../docs/backend/CODE_CONVENTIONS.md` (§19 one-line description comments · §20 SRP/size limits are graded)

## Build & run (backend)

Run every command from the `backend/` directory, using the Gradle wrapper.

```bash
cd backend
./gradlew bootRun          # run the app (includes devtools auto-restart)

# ⚠ Test tasks require -PtestDbUrl (since 2026-09-14). Without it, configuration fails immediately —
# this stops multiple agents from silently falling back to the same shared DB (schoolbus) and trampling each other's rows.
# Redis needs no argument: every test automatically gets an isolated container (ContextCustomizerFactory).
# bootRun·compileJava don't use this argument — they run unaffected (keep using them for post-merge compile checks).
./gradlew build -PtestDbUrl=jdbc:postgresql://localhost:15432/<dedicated-db>            # full build + tests
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<dedicated-db>            # all tests
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<dedicated-db> --tests 'src.backend.BackendApplicationTests'   # one test class
./gradlew test  -PtestDbUrl=jdbc:postgresql://localhost:15432/<dedicated-db> --tests '*.methodName'                         # one test method

# Real Naver API tests (@Tag("live"), 3 classes) are excluded from the default test task and run separately. They need the credentials in backend/.env.
# If the Directions 15 daily quota is exhausted (Ruling 361), that response is a skip, not a failure; other errors still fail.
./gradlew liveTest -PtestDbUrl=jdbc:postgresql://localhost:15432/<dedicated-db>
# To dodge the quota (switch to Directions 5), set path and limit as a pair: NAVER_DIRECTIONS_PATH=/map-direction/v1/driving NAVER_DIRECTIONS_MAX_POINTS=7 ./gradlew liveTest …
# When missing credentials must fail instead of skip (e.g. right before a merge), pass -PrequireLive to test and the live tests run too.
```

**One-command check.** The repo-root `scripts/verify.sh` runs the backend tests (auto-creates and drops a dedicated DB) with the same commands as CI (`.github/workflows/ci.yml`). It excludes the real-API tests. The staff web and apps use their own repo's `scripts/verify.sh`. CI details and log policy: `../docs/backend/infra/DEPLOYMENT.md §5.1`.

## Docker — dev and deploy are split by file (2026-09-18)

| File | What | Command |
|---|---|---|
| `docker-compose.yml` | **Dev infra only** — postgres (15432) · redis (16379) | `docker compose up -d` |
| `+ docker-compose.app.yml` | **App overlay** — backend · **staff web** · proxy (:3000) · observability | `docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build` |
| `docker-compose.prod.yml` | **Deploy** — ECR images · persistent volumes · TLS | `../docs/backend/infra/DEPLOYMENT.md` |
| `docker-compose.staging.yml` | **Staging (for teammates to try · home PC)** — standalone file · `local,staging` profiles · in-memory DB · Cloudflare Tunnel | `../docs/backend/infra/STAGING.md` |

**Pick one dev mode.**
1. **Infra in containers + backend in the IDE** — `docker compose up -d`, then `./gradlew bootRun`. Swagger at `http://localhost:8080/...`
2. **Everything in containers** — the overlay command above. **All HTTP goes through the proxy (:3000)** — staff web `http://localhost:3000` · API `.../api/v1/...` · Swagger `.../swagger-ui/index.html` · Grafana `:3001` (admin/admin) · Prometheus `:9090`

- ⚠⚠ **The proxy being on `3000` is not a coincidence.** The Naver Maps key's **service URL** and the backend **CORS allow-list** are both registered as `http://localhost:3000`. On any other port the map SDK's `/v3/auth` is rejected with **401** and the screen only shows *"지도를 불러오지 못했습니다"* (actually hit on 2026-09-18 when opened on `:80`; switching to 3000 turned the same request into 200). To change the port, **change the service URL in the NCP console first**

- ⚠ **If you used the overlay, pass both `-f` flags to `down` as well.** Without them compose reads only the infra file and the backend/proxy/observability containers **survive.** If that's tedious: `export COMPOSE_FILE=docker-compose.yml:docker-compose.app.yml`
- ⚠ **The proxy doesn't route `/actuator` — 404 is expected.** Health: `docker compose ... exec backend curl -s localhost:8080/actuator/health`
- ⚠ **In container mode the Redis port must be overridden** (`SPRING_DATA_REDIS_PORT: 6379`). The `application.yml` default 16379 is the **host-published port**, so overriding only the host leaves the app **starting but with health stuck at DOWN** (actually happened 2026-09-18)

After startup, test APIs with Swagger UI — put the login response's `access_token` (inside the response envelope's `data`) into Authorize at the top right and later requests carry it automatically. For login accounts see the QA Mock seed (`db/qa-seed/` · account table in the workspace repo's `docs/qa/QA_SCENARIOS.md`) — **locally** every password is `password` (different in deployed environments, see the Flyway item below).

**Local postgres intentionally has no persistent volume** (since 2026-07-22, so repeated Swagger testing always returns to the seed state) — after `docker compose down` (removes the containers), `docker compose up -d postgres redis` makes Flyway rebuild the schema (V1~) + QA Mock seed (`db/qa-seed` V900·V901) every time (no manual `DROP SCHEMA`/`volume rm`; `DataInitializer` was deleted 2026-07-20). `stop`/`start` (doesn't remove the containers) keeps the data — to reset you must go through `down`.

**Deployment** follows `../docs/backend/infra/DEPLOYMENT.md`. Design rationale (managed-service review · blocking defects · cost): `../docs/archive/specs/2026-08-10-mvp-배포-design.md`. Production is one EC2 + `docker-compose.prod.yml`, with 1 backend instance by default and 2 with `docker-compose.scale.yml` laid over it (`BACKEND_INSTANCES=2` in SSM; the user decides the count at deploy — two 3GB instances do not fit a 4GB host). What makes 2 safe (ShedLock for `@Scheduled`, Redis relay for WS messages, Redis-held preview caches): `docs/backend/ARCHITECTURE.md §9.5`; procedure: `DEPLOYMENT.md §13`.

## Stack / notable specifics

- **Spring Boot 4.1.1**, **Java 25** (toolchain pinned), Gradle. The web starter uses the new artifact name `spring-boot-starter-webmvc` (tests: `spring-boot-starter-webmvc-test`) — not the old `spring-boot-starter-web`.
- **Flyway manages the schema** (`spring-boot-starter-flyway` + `flyway-database-postgresql`, since 2026-07-20) — Hibernate only validates (`ddl-auto: validate`).
- **Table definitions live in a single file, `db/migration/V1__init_schema.sql`** (2026-10-03 — the V3~V11·V15 added in between were merged; `pg_dump --schema-only` is identical before and after). **Don't create new version files before the first deployment** (2026-08-23 policy · 2026-10-03 standing user instruction *"개발 중에는 데이터 다 초기화돼도 상관없다"* — with no DB to preserve, don't stack versions for preservation's sake; local/test DBs and containers can be dropped and rebuilt any time). To change the schema, **edit V1 directly and rebuild the local DB entirely** — `docker compose down`, then `docker compose up -d postgres redis`. Local has no persistent volume so nothing is lost, and this beats scattering the schema's final shape across many version files. Editing an existing file changes its checksum, so **an already-migrated DB fails to start with `FlywayValidateException`** — just restarting the app without rebuilding fails (a missing-rebuild signal, not a code defect). But `local`-profile `bootRun` runs `clean()` and reloads on every start (`LocalFlywayCleanStrategy`), so it doesn't hit this — the failures happen in the `-PtestDbUrl` test DBs and the container-mode backend (`--app.flyway-clean.suppressed=true`), which disable `clean()`.
- **This exception applies only to "migrations not yet applied to any persistent environment."** Once applied to demo/prod even once, the rule flips: **no editing existing files · only adding `V{n}`**. The production DB has a volume and can't be rolled back by rebuilding; a checksum mismatch means it can't start. Update this item at the first deployment. There are two seeds and they don't mix (split 2026-10-03). **QA Mock** `db/qa-seed/` (Bucheon, Gyeonggi · 3 academies · 152 students · the last 7 days of run history · today's runs relative to the reset time) loads in **`local`·`staging`·`demo`** and reloads on every restart and `POST /dev/reset` — accounts and scenarios are in the workspace repo's `docs/qa/QA_SCENARIOS.md`. **The test seed** `db/fixture/` (moved as-is from the old `migration-local`) loads only in the test JVM (`build.gradle`) and the `fixture` profile (`local,fixture` — web/app live-server contract tests · load measurement). Changing QA data doesn't affect tests, and running contract tests against a server started with QA data breaks them. Seed-account password hashes are injected via the Flyway placeholder `seedPasswordHash` — local uses the `application.yml` default (plain `password`), demo uses the value from SSM, so **deployed passwords are not `password`.**
- The base package is `src.backend` and the Gradle `group = 'src'` (unconventional). Put new classes under `src.backend` to stay within `@SpringBootApplication`'s component scan.
- **Read `../docs/backend/CODE_CONVENTIONS.md` (Markdown, for Claude) first for convention details.** It covers the spec/impl criteria, package structure, CQRS, event rules and all other principles. The human browser render of the same content is `../docs/render/CODE_CONVENTIONS.html` (with visuals) — same principles, different medium; Claude refers to `CODE_CONVENTIONS.md` every session.
- **Key summary**: split service/repository into `spec` (interface) / `impl` (implementation) subpackages only by the criterion "might the implementation change?" (plain CRUD isn't split) — e.g. `routing/map/spec/MapRouteClient.java` + `routing/map/impl/NaverDirectionsClient.java` (plain-CRUD modules like `bus` have only `command/` · `query/`, with no `spec`/`impl`). Controllers and the like depend only on `spec`. No `package-info.java` (except when a package-level annotation is needed).
