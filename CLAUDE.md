# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

학생 통원(등하원) 경로 관리 서비스의 백엔드. Spring Boot 골격 위에 MVP 15개 모듈(auth·bus·route·rideevent·location·user·tenant·student·attendance·notification·sos·schedule·routing·drivesession·global 인프라) 전체가 엔티티~컨트롤러까지 구현 완료됐다(§11.4 Phase 0~7). 남은 건 Phase 8(Mock→실 GPS 전환)뿐 — 상세는 `PROJECT_MASTER_PLAN.md` §12.1 참조.

- **작업 전 반드시 `backend/docs/PROJECT_MASTER_PLAN.md`(단일 소스)를 먼저 읽는다.** 여기에 기획서(docx) 재정리 요구사항 + 현재 코드 기준 MVP 구현계획 + **모듈별 진행 추적/백로그**가 있다. 진행 상황·큰 변경은 이 문서에 반영한다.
- 기획 원본은 `backend/docs/학원 통학버스 통합관리 시스템.docx`(불변), 프론트 데모·시나리오 관점의 초기 지시서는 루트 `projectInfo.md`.

- 서비스: 학원 통원버스 운행·학생 등하원 관리 **멀티 테넌트 플랫폼** (모바일 앱 + 관리자 웹)
- 사용자 5계층: 학생 / 학부모 / 운전기사 / 학원 관리자 / 플랫폼 관리자 — 학원 관리자와 플랫폼 관리자는 권한 범위가 완전히 다른 별개 역할
- 멀티 테넌시: `tenant_id`(학원) 컬럼 기반 데이터 격리 우선 검토

## Build & run (backend)

모든 명령은 `backend/` 디렉토리에서 실행한다. Gradle wrapper 사용.

```bash
cd backend
./gradlew bootRun          # 앱 실행 (devtools 자동 재시작 포함)
./gradlew build            # 전체 빌드 + 테스트
./gradlew test             # 전체 테스트
./gradlew test --tests 'src.backend.BackendApplicationTests'   # 단일 테스트 클래스
./gradlew test --tests '*.메서드명'                             # 단일 테스트 메서드
```

앱 기동 후 API 테스트는 Swagger UI(`http://localhost:8080/swagger-ui/index.html`)를 쓴다 — `/api/auth/login` 응답의 `accessToken`을 우측 상단 Authorize에 넣으면 이후 요청에 자동으로 붙는다. 로그인 계정은 Flyway 시드(`db/migration-local/V2__seed_data.sql`) 참조 — 비밀번호는 모두 `password`.

**로컬 DB를 완전히 초기화**하려면(스키마 꼬임 등): `docker compose rm -f postgres && docker volume rm school-bus_pgdata && docker compose up -d postgres` 후 `bootRun` — Flyway가 스키마(V1)+데모 시드(V2)를 자동으로 다시 구성한다(수동 `DROP SCHEMA` 불필요, `DataInitializer`는 2026-07-20 삭제됨).

## Stack / 주요 특이사항

- **Spring Boot 4.1.0**, **Java 25**(toolchain 고정), Gradle. 웹 스타터는 신형 아티팩트명 `spring-boot-starter-webmvc`(테스트는 `spring-boot-starter-webmvc-test`)를 사용한다 — 구버전 `spring-boot-starter-web`이 아님.
- **스키마는 Flyway가 관리**(`spring-boot-starter-flyway`+`flyway-database-postgresql`, 2026-07-20부터)한다 — `ddl-auto: validate`로 Hibernate는 검증만. 새 컬럼/테이블이 필요하면 `db/migration/V{n}__설명.sql`을 새로 추가한다(기존 `V1__init_schema.sql` 수정 금지). 로컬 전용 데모 시드는 별도 위치 `db/migration-local/`(local 프로파일에서만 `spring.flyway.locations`에 추가돼 prod엔 안 들어감).
- 기본 패키지가 `src.backend`이고 Gradle `group = 'src'`이다(비관례적). 새 클래스는 이 `src.backend` 하위에 두어 `@SpringBootApplication` 컴포넌트 스캔 범위를 유지한다.
- **코드 컨벤션 상세는 `backend/docs/reference.md`(Claude 참조용, Markdown)를 먼저 읽는다.** spec/impl 판단기준·패키지 구조·CQRS·Event 규칙 등 전체 원칙이 정리돼 있다. 사람이 브라우저로 보는 동일 내용의 렌더링 버전은 `backend/docs/CODE_CONVENTIONS.html`(시각화 포함) — 둘은 원칙은 같고 매체만 다르며, Claude는 세션마다 `reference.md`를 참조한다.
- **핵심 요약**: service·repository는 "구현이 바뀔 가능성이 있는가"를 기준으로만 `spec`(인터페이스) / `impl`(구현체) 하위 패키지로 분리한다(단순 CRUD는 분리하지 않음) — 예 `bus/service/spec/BusService.java` + `bus/service/impl/BusServiceImpl.java`. 컨트롤러 등은 `spec`만 의존한다. `package-info.java`는 두지 않는다(패키지 레벨 애너테이션이 필요할 때만 예외).
- 기획서상 목표 스택: 백엔드 Spring, DB **PostgreSQL**, 프론트 NestJS, Docker Compose 오케스트레이션, Nginx 리버스 프록시, 역할 기반 JWT 인증. 이들은 아직 코드에 없으며 구현 시 추가한다.

## 구현 시 핵심 설계 제약 (projectInfo.md 요약)

- **위치 추적은 학생 스마트폰 GPS로 확정**. 단 MVP 단계에서는 **Mock 좌표 스트림**으로 구현하되, 위치 수신 API 인터페이스를 추상화해 실제 GPS 연동 시 데이터 소스만 교체 가능하도록 설계한다.
- 승하차 체크(QR/NFC/기사 수동)는 Mock 여부와 무관하게 실제 로직으로 구현한다.
- 하나의 승하차 기록(`RideEvent`)을 4개 사용자 계층이 각자 권한 범위에서 조회 — 계층별 조회 권한 분리가 핵심.
- 권장 MVP 범위: 시나리오 1·2·5·6·8 (위치추적 Mock, 승하차기록, 버스관리, 알림, 기사 운행관리).
- 데이터 모델 초안(User/Tenant/Student/Bus/Route/Stop/RideEvent/AttendanceException/ScheduleChangeRequest/SosEvent/NotificationLog)과 작업 순서는 `projectInfo.md` 6·10장 참조.

## 응답/문서 규칙

이 사용자는 Spring 입문 단계의 백엔드 개발자다. 전역 `~/.claude/CLAUDE.md` 규칙(한글 응답, 개념별 1줄 요약 → 번호 흐름 → request→처리→response, Controller/Service/Repository 연계 설명, 흔한 오해 1개 포함)을 따른다.

**문서 포맷 규칙**: 사람이 브라우저로 보는 기술문서(아키텍처·규칙 등)는 전역 정책대로 HTML+인라인 SVG로 작성한다. 단 **Claude가 매 세션 재참조하는 기획·진행추적·컨벤션 문서(`PROJECT_MASTER_PLAN.md`, `reference.md` 등)는 토큰 효율을 위해 Markdown으로 유지**한다(HTML의 SVG·CSS 골격은 재로딩 비용만 크다). 같은 내용의 인간용 HTML 렌더(예: `CODE_CONVENTIONS.html`)가 별도로 존재할 수 있으며, 둘은 원칙만 동기화하고 서로 대체하지 않는다.

**HTML 문서 수정 규칙 (2026-07-18 확정)**: `CODE_CONVENTIONS.html` 등 사람용 HTML 문서는 **사용자가 명시적으로 요청한 경우에만** 생성·수정한다. Markdown 원본(`reference.md`, `PROJECT_MASTER_PLAN.md` 등)을 바꿨다고 해서 대응하는 HTML을 자동으로 동기화하지 않는다 — 필요하면 사용자가 별도로 요청한다.
