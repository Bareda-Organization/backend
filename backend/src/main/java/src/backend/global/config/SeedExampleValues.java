package src.backend.global.config;

import java.util.Map;

import src.backend.global.common.SeedFixtures;

/**
 * Swagger 문서의 식별자 성격 필드·파라미터가 쓸 예시값 사전(BR-113, Ruling 348).
 * {@link SeedExampleSchemaCustomizer}(요청·응답 DTO 필드) 와 {@link SeedExampleOperationCustomizer}
 * (경로·쿼리 파라미터) 가 이 사전을 함께 참조해 값이 갈라지지 않게 하고, {@code SwaggerExampleSeedContractTest}
 * 가 문서에 실린 값이 여전히 {@link SeedFixtures} 사전에 속하는지 검증한다.
 *
 * <p><b>"id" 처럼 여러 자원이 같은 이름을 쓰는 자리는 스키마·컨트롤러 이름으로 가른다</b> —
 * {@code RunResponse.id} 와 {@code BusResponse.id} 는 필드명이 둘 다 "id" 라 이름만으로는
 * 회차인지 차량인지 구분할 수 없다. 이 사전에 없는 필드·파라미터는 커스터마이저가 대표값(문자열
 * "example"·숫자 1 등)으로 채운다 — {@code IMPLEMENTATION_PLAN §3.3} 이 요구하는 "예시 존재"만
 * 만족시키고, 실제 엔티티와의 일치는 보장하지 않는다.
 *
 * <p>ponytail: 표에 없는 자원(스케줄·알림·매니저·노선·차단 계정 등)은 대표값으로 채워진다 —
 * Swagger UI 에서 그 식별자로 "Try it out" 이 정확히 성공하길 원하면 이 표에 실제 시드 PK 를
 * 추가한다(우선순위가 낮아 이번 라운드에서는 생략, {@code FIX-T.md} §2).
 */
final class SeedExampleValues {

    private SeedExampleValues() {
    }

    /**
     * {@code <스키마 클래스명>.<필드명>} → 시드 상수. 필드명은 {@code SNAKE_CASE}(Ruling 104)
     * 변환 뒤의 JSON 키다.
     */
    static final Map<String, String> SCHEMA_PROPERTY_EXAMPLES = Map.ofEntries(
            Map.entry("LoginRequestPayload.login_id", SeedFixtures.STAFF_A_LOGIN_ID),
            Map.entry("LoginRequestPayload.password", SeedFixtures.LOCAL_DEFAULT_PASSWORD),
            Map.entry("RunResponse.id", SeedFixtures.RUN_CONFIRMED_ID),
            Map.entry("ManagerRunResponse.run_id", SeedFixtures.RUN_CONFIRMED_ID),
            Map.entry("BusResponse.id", SeedFixtures.BUS_NEAR_FULL_ID),
            Map.entry("AcademyDetailResponse.id", SeedFixtures.ACADEMY_A_ID),
            Map.entry("AcademyDetailResponse.code", SeedFixtures.ACADEMY_A_CODE));

    /**
     * 경로·쿼리 파라미터 예시. 자원마다 이름이 다른 것({@code runId} · {@code academy_id})은 이름만으로
     * 충분하고, 여러 컨트롤러가 같은 이름("id")을 다른 자원에 쓰는 것은
     * {@code <컨트롤러 클래스명>#<파라미터명>} 으로 가른다.
     */
    static final Map<String, String> PARAMETER_EXAMPLES = Map.ofEntries(
            Map.entry("runId", SeedFixtures.RUN_CONFIRMED_ID),
            Map.entry("academyId", SeedFixtures.ACADEMY_A_ID),
            Map.entry("academy_id", SeedFixtures.ACADEMY_A_ID),
            Map.entry("StaffStudentController#id", SeedFixtures.STUDENT_SIBLING_1_ID),
            Map.entry("StaffStudentTransferController#id", SeedFixtures.STUDENT_SIBLING_1_ID),
            Map.entry("StudentRunsController#id", SeedFixtures.STUDENT_SIBLING_1_ID),
            Map.entry("StudentRouteController#id", SeedFixtures.STUDENT_SIBLING_1_ID),
            Map.entry("StudentBusPositionController#id", SeedFixtures.STUDENT_SIBLING_1_ID),
            Map.entry("WeeklyAddressController#id", SeedFixtures.STUDENT_SIBLING_1_ID),
            Map.entry("ChangeRequestController#id", SeedFixtures.STUDENT_SIBLING_1_ID),
            Map.entry("BoardingIntentController#id", SeedFixtures.STUDENT_SIBLING_1_ID),
            Map.entry("StaffBusController#id", SeedFixtures.BUS_NEAR_FULL_ID),
            Map.entry("AdminAcademyController#id", SeedFixtures.ACADEMY_A_ID),
            Map.entry("AdminAcademyLiveController#id", SeedFixtures.ACADEMY_A_ID),
            Map.entry("StaffApprovalController#id", SeedFixtures.CHANGE_REQUEST_PENDING_ID),
            Map.entry("StaffRunController#id", SeedFixtures.RUN_IDLE_ID));
}
