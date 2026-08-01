# API_SPEC.md ↔ 백엔드 코드 drift 검증

- 대상 문서: `docs/API_SPEC.md`(2,056행, 미커밋 신규 파일)
- 검증 순서: §2 인증 → §3 위치 → §5 승하차 → §6 배차 → §4 운행세션 → §7~§18(나머지) → §1 공통규약 → §17 부록A 매트릭스
- 검증한 주장: 약 220개 이상 — 엔드포인트 경로·메서드·권한 69개(REST 68 + STOMP 1) 전수, `@PreAuthorize`/`TenantGuard`/서비스단 인가 로직 18개 장 전수, 요청·응답 DTO 필드, enum 11종(Role/RideType/RideSource/NotificationType/ApprovalStatus/AttendanceType/DriveSessionStatus/RouteDirection/RoutePlanStatus/SosStatus/LocationOrigin) 상수 전수, 처리순서·실패코드·예외 메시지 문자열, 설정값 8개(heartbeat-ms/loss-grace-seconds/loss-check-ms/tick-ms/approach-check-ms/escalation-check-ms/max-waypoints/provider), 시드 데이터 8개 테이블 행수, 스케줄러 주기 4개, 부록A 집계(REST 68·컨트롤러 14·프론트사용 22·미사용 46·구현✅60/⚠8) 포함
- 대조한 코드 파일: 75개 이상(controller/command/query/entity/dto/event/scheduler/config 전 계층 + `application.yml` + `V2__seed_data.sql` + `build.gradle`) — 전부 현재 디스크 기준(`git status` 확인 결과 해당 코드·설정 파일은 모두 커밋된 상태, 미커밋분 없음)

| 파일:라인 | 담당 | 유형 | 심각도 | 근거·제안 |
|---|---|---|---|---|
| docs/API_SPEC.md:1741 | docs-drift-auditor | 부수효과 누락 | high | "⚠⚠ 승인해도 실제 운행 스케줄·노선에 반영되는 로직이 없다. 하는 일은 status를 APPROVED로 바꾸고 ScheduleResultEvent(알림)를 쏘는 것뿐이다"는 서술은 틀렸다. `ScheduleCommandService.approve()`(backend/src/main/java/src/backend/schedule/command/ScheduleCommandService.java:58-64)가 발행하는 `ScheduleResultEvent`를 `RoutingReplanEventConsumer.onScheduleResult`(backend/src/main/java/src/backend/routing/infrastructure/impl/RoutingReplanEventConsumer.java:36-42)가 `schedule-result` 토픽에서 구독해, `status==APPROVED`이면 `routingCommandService.replanForStudent(studentId, requestedDate)`를 호출한다. 그 버스·방향에 그날 최신 계획이 이미 있으면 그 학생을 포함해 다시 계산한 새 `RECOMMENDED` `RoutePlan`이 실제로 생성된다(14장 attendance와 동일한 replan 경로, `RoutingCommandService.java:210-262`). 코드 자체 javadoc(`schedule/event/ScheduleResultEvent.java:14`: "status가 APPROVED면 routing 모듈이 requestedDate로 국소 replan 대상을 판단한다(Phase 6e)")도 이 동작을 명시적으로 문서화하고 있다. "17:30 같은 특정 요청 시각 자체가 stop 시간으로 전파되지 않는다"는 좁은 의미로는 맞지만(`replanForStudent`는 `requestedTime`을 쓰지 않고 정류장 순서만 재계산한다), "반영되는 로직이 없다"·"하는 일은 상태변경+알림뿐"이라는 넓은 문장은 사실과 다르다 — 승인 시 정말로 새 RoutePlan(RECOMMENDED)이 자동 생성되는 부수효과를 문서가 누락했다. |

**일치 확인**: 위 1건을 제외한 §1(공통 응답·에러코드·날짜직렬화·페이징·enum 사전)~§18(부록 B·C) 전 구간 — 엔드포인트 경로/메서드/권한/TenantGuard/DTO 필드/처리순서/예외메시지/enum 상수/설정 기본값/시드 데이터 건수/스케줄러 주기/부록A 집계 수치 — 코드와 정확히 일치한다.

## 참고(코드 결함, drift 아님 — 담당 외)
- `attendance/query/AttendanceQueryService.java:59`의 `getActiveRoster` javadoc이 "다른 모듈은 아직 이 메서드를 호출하지 않는다"고 돼 있으나, 실제로는 `DriveSessionQueryService.java:67`·`RoutingCommandService.java:265`가 이미 호출 중인 stale 주석이다. API_SPEC.md §14.x의 "`GET /api/drive-sessions/{id}/roster`가 사용한다" 서술은 이 실제 호출 관계와 일치하므로 문서 오류가 아니다.
