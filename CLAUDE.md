# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

학생 통원(등하원) 경로 관리 서비스의 백엔드. Spring Boot 골격 위에 **일부 도메인이 이미 구현**된 MVP 진행 단계다(auth·bus·route·rideevent·location(Mock/실GPS 추상화)·global 인프라 완료 / user·tenant·student·attendance는 엔티티·레포만 / notification·sos·schedule·routing은 미구현).

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

## Stack / 주요 특이사항

- **Spring Boot 4.1.0**, **Java 25**(toolchain 고정), Gradle. 웹 스타터는 신형 아티팩트명 `spring-boot-starter-webmvc`(테스트는 `spring-boot-starter-webmvc-test`)를 사용한다 — 구버전 `spring-boot-starter-web`이 아님.
- 기본 패키지가 `src.backend`이고 Gradle `group = 'src'`이다(비관례적). 새 클래스는 이 `src.backend` 하위에 두어 `@SpringBootApplication` 컴포넌트 스캔 범위를 유지한다.
- **코드 컨벤션**: service·repository는 `spec`(인터페이스) / `impl`(구현체) 하위 패키지로 분리한다 — 예 `bus/service/spec/BusService.java` + `bus/service/impl/BusServiceImpl.java`. 컨트롤러 등은 `spec`만 의존한다. `package-info.java`는 두지 않는다(패키지 레벨 애너테이션이 필요할 때만 예외).
- 기획서상 목표 스택: 백엔드 Spring, DB **PostgreSQL**, 프론트 NestJS, Docker Compose 오케스트레이션, Nginx 리버스 프록시, 역할 기반 JWT 인증. 이들은 아직 코드에 없으며 구현 시 추가한다.

## 구현 시 핵심 설계 제약 (projectInfo.md 요약)

- **위치 추적은 학생 스마트폰 GPS로 확정**. 단 MVP 단계에서는 **Mock 좌표 스트림**으로 구현하되, 위치 수신 API 인터페이스를 추상화해 실제 GPS 연동 시 데이터 소스만 교체 가능하도록 설계한다.
- 승하차 체크(QR/NFC/기사 수동)는 Mock 여부와 무관하게 실제 로직으로 구현한다.
- 하나의 승하차 기록(`RideEvent`)을 4개 사용자 계층이 각자 권한 범위에서 조회 — 계층별 조회 권한 분리가 핵심.
- 권장 MVP 범위: 시나리오 1·2·5·6·8 (위치추적 Mock, 승하차기록, 버스관리, 알림, 기사 운행관리).
- 데이터 모델 초안(User/Tenant/Student/Bus/Route/Stop/RideEvent/AttendanceException/ScheduleChangeRequest/SosEvent/NotificationLog)과 작업 순서는 `projectInfo.md` 6·10장 참조.

## 응답/문서 규칙

이 사용자는 Spring 입문 단계의 백엔드 개발자다. 전역 `~/.claude/CLAUDE.md` 규칙(한글 응답, 개념별 1줄 요약 → 번호 흐름 → request→처리→response, Controller/Service/Repository 연계 설명, 흔한 오해 1개 포함)을 따른다.

**문서 포맷 규칙**: 사람이 브라우저로 보는 기술문서(아키텍처·규칙 등)는 전역 정책대로 HTML+인라인 SVG로 작성한다. 단 **Claude가 매 세션 재참조하는 기획·진행추적 문서(`PROJECT_MASTER_PLAN.md` 등)는 토큰 효율을 위해 Markdown으로 유지**한다(HTML의 SVG·CSS 골격은 재로딩 비용만 크다).
