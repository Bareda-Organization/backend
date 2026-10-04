package src.backend.global.config;

import static java.util.Map.entry;

import java.util.Map;

/**
 * 엔드포인트 고유 실패 응답 표({@code docs/planning/API_SPEC.md} 각 절의 <b>에러</b> 줄).
 *
 * <p>애너테이션이 아니라 표로 두는 이유는 이름 충돌이다 — 이 저장소의 성공 응답 봉투가
 * {@code src.backend.global.response.ApiResponse} 이고, Swagger 의 애너테이션도 같은 이름이라
 * 컨트롤러가 둘을 함께 import 할 수 없다. 핸들러마다 완전 수식명을 105번 적는 대신 한 곳에 모은다.
 *
 * <p>표는 정본에서 옮긴 파생본이라 낡을 수 있다. 그래서 {@code OpenApiCoverageTest} 가
 * ①표의 모든 키가 실재하는 엔드포인트인지 ②적힌 코드가 {@code ErrorCode} 에 있는지를 함께 검사한다 —
 * 경로를 바꾸거나 코드 이름을 고치면 그 시험이 먼저 깨진다.
 *
 * <p>§3.5 {@code GET /students/{id}/runs} 와 §4.15 {@code GET /runs/{runId}/emergencies} 는 사양에만
 * 있고 핸들러가 부재하던 자리였다 — 이 표의 키 대조 시험이 그것을 드러냈고, 2026-09-09 둘 다
 * 구현해 아래 표에 등재했다.
 *
 * <p>공통 항목(401·403·422)은 여기 없다 — 정의상 전 경로에 해당하므로
 * {@link CommonErrorResponsesCustomizer} 가 한 번에 붙인다(§1.11).
 */
public final class EndpointErrorResponses {

    /** {@code "METHOD /bare/path"} → (HTTP 상태 → 그 상태로 나가는 에러 코드들). */
    public static final Map<String, Map<String, String>> BY_ENDPOINT = Map.ofEntries(
            entry("DELETE /runs/{runId}/emergency/{id}", Map.of("403", "FORBIDDEN", "404", "EMERGENCY_NOT_FOUND", "409", "EMERGENCY_CANCEL_WINDOW_CLOSED")),
            entry("DELETE /staff/managers/{id}", Map.of("404", "MANAGER_NOT_FOUND", "409", "MANAGER_ASSIGNED")),
            entry("DELETE /staff/routes/{id}", Map.of("404", "ROUTE_NOT_FOUND")),
            entry("DELETE /staff/runs/{id}", Map.of("404", "RUN_NOT_FOUND")),
            entry("DELETE /staff/runs/{runId}/waypoints/{waypointId}", Map.of("403", "CHANGE_WINDOW_CLOSED", "404", "RUN_NOT_FOUND · WAYPOINT_NOT_FOUND", "409", "RUN_NOT_CONFIRMED")),
            entry("DELETE /staff/schedules/{id}", Map.of("404", "SCHEDULE_NOT_FOUND")),
            entry("DELETE /staff/students/{id}", Map.of("404", "STUDENT_NOT_FOUND")),
            entry("GET /academies/search", Map.of("422", "VALIDATION_FAILED")),
            entry("GET /admin/academies/{id}", Map.of("404", "ACADEMY_NOT_FOUND")),
            entry("GET /admin/academies/{id}/runs/live", Map.of("404", "ACADEMY_NOT_FOUND")),
            entry("GET /admin/audit-logs", Map.of("404", "ACADEMY_NOT_FOUND · ACCOUNT_NOT_FOUND",
                    "422", "VALIDATION_FAILED")),
            entry("GET /admin/login-history", Map.of("404", "ACADEMY_NOT_FOUND · ACCOUNT_NOT_FOUND")),
            entry("GET /admin/runs/{runId}/roster", Map.of("404", "RUN_NOT_FOUND")),
            entry("GET /admin/staff-accounts", Map.of("404", "ACADEMY_NOT_FOUND", "422", "VALIDATION_FAILED")),
            entry("GET /runs/{runId}/emergencies", Map.of("403", "FORBIDDEN")),
            entry("GET /runs/{runId}/navigation", Map.of("403", "FORBIDDEN", "404", "RUN_NOT_FOUND", "409", "RUN_NOT_CONFIRMED · NAV_NO_REMAINING_STOP")),
            entry("GET /runs/{runId}/roster", Map.of("403", "FORBIDDEN", "404", "RUN_NOT_FOUND", "409", "RUN_NOT_CONFIRMED")),
            entry("GET /runs/{runId}/route", Map.of("403", "FORBIDDEN", "404", "RUN_NOT_FOUND", "409", "RUN_NOT_CONFIRMED")),
            entry("GET /staff/approvals/{id}", Map.of("404", "APPROVAL_NOT_FOUND", "409", "RUN_NOT_CONFIRMED", "422", "ROUTE_NOT_CONFIGURED_FOR_RUN · ACADEMY_COORDINATES_MISSING")),
            entry("GET /staff/routes/{id}", Map.of("404", "ROUTE_NOT_FOUND")),
            entry("GET /staff/routes/{id}/path", Map.of("404", "ROUTE_NOT_FOUND", "503", "MAP_ROUTE_UNAVAILABLE")),
            entry("GET /staff/runs/{runId}/roster", Map.of("403", "ACADEMY_SCOPE_VIOLATION", "404", "RUN_NOT_FOUND")),
            entry("GET /staff/runs/{runId}/route", Map.of("404", "RUN_NOT_FOUND", "409", "RUN_NOT_CONFIRMED")),
            entry("GET /staff/stops/suggest", Map.of("503", "ADDRESS_VERIFICATION_UNAVAILABLE")),
            entry("GET /staff/students/{id}", Map.of("404", "STUDENT_NOT_FOUND")),
            entry("GET /staff/students/{id}/withdrawal-preview", Map.of("404", "STUDENT_NOT_FOUND")),
            entry("GET /students/{id}/bus-position", Map.of("403", "FORBIDDEN", "404", "STUDENT_NOT_FOUND")),
            entry("GET /students/{id}/change-requests", Map.of("403", "FORBIDDEN", "404", "STUDENT_NOT_FOUND")),
            entry("GET /students/{id}/route", Map.of("403", "FORBIDDEN", "404", "STUDENT_NOT_FOUND · RUN_NOT_FOUND")),
            entry("GET /students/{id}/runs", Map.of("403", "FORBIDDEN", "404", "STUDENT_NOT_FOUND")),
            entry("GET /students/{id}/weekly-address", Map.of("403", "FORBIDDEN", "404", "STUDENT_NOT_FOUND")),
            entry("PATCH /admin/academies/{id}", Map.of("404", "ACADEMY_NOT_FOUND")),
            entry("PATCH /admin/staff-accounts/{id}", Map.of("404", "ACCOUNT_NOT_FOUND", "409", "STAFF_QUOTA_EXCEEDED")),
            entry("PATCH /notifications/{id}/read", Map.of("403", "FORBIDDEN", "404", "NOTIFICATION_NOT_FOUND")),
            entry("PATCH /runs/{runId}/riders/{riderId}", Map.of("403", "ESCORT_ONLY", "404", "RIDER_NOT_FOUND · RUN_NOT_FOUND", "409", "RUN_NOT_MOVING", "422", "VALIDATION_FAILED")),
            entry("PATCH /staff/buses/{id}", Map.of("404", "BUS_NOT_FOUND", "409", "CAPACITY_EXCEEDED · DUPLICATE_BUS_NO")),
            entry("PATCH /staff/managers/{id}", Map.of("404", "MANAGER_NOT_FOUND")),
            entry("PATCH /staff/routes/{id}", Map.of("404", "ROUTE_NOT_FOUND · BUS_NOT_FOUND", "409", "DUPLICATE_ROUTE", "422", "VALIDATION_FAILED")),
            entry("PATCH /staff/runs/{runId}/assignment", Map.of("404", "RUN_NOT_FOUND · MANAGER_NOT_FOUND", "409", "DUPLICATE_ASSIGNMENT", "422", "VALIDATION_FAILED")),
            entry("PATCH /staff/schedules/{id}", Map.of("404", "SCHEDULE_NOT_FOUND · BUS_NOT_FOUND", "409", "DUPLICATE_SCHEDULE")),
            entry("PATCH /staff/students/{id}", Map.of("404", "STUDENT_NOT_FOUND")),
            entry("PATCH /students/{id}/runs/{runId}/intent", Map.of("403", "CHANGE_LIMIT_REACHED · CHANGE_WINDOW_CLOSED · FORBIDDEN", "404", "RUN_NOT_FOUND · STUDENT_NOT_FOUND")),
            entry("PATCH /students/{id}/weekly-address", Map.of("403", "FORBIDDEN", "404", "STUDENT_NOT_FOUND", "422", "ADDRESS_VERIFICATION_FAILED")),
            entry("POST /admin/blocked-accounts/{id}/unblock", Map.of("404", "ACCOUNT_NOT_FOUND", "409", "ACCOUNT_NOT_BLOCKED")),
            entry("POST /admin/runs/{runId}/force-confirm", Map.of("404", "RUN_NOT_FOUND", "409", "RUN_NOT_IDLE · RUN_NOT_DUE", "422", "VALIDATION_FAILED")),
            entry("POST /admin/runs/{runId}/force-finish", Map.of("404", "RUN_NOT_FOUND", "409", "RUN_CANCELED · RUN_NOT_MOVING · RUN_NOT_STALE", "422", "VALIDATION_FAILED")),
            entry("POST /admin/staff-signup-requests/{id}/decide", Map.of("404", "SIGNUP_REQUEST_NOT_FOUND", "409", "STAFF_QUOTA_EXCEEDED · APPROVAL_ALREADY_DECIDED · SIGNUP_TARGET_BLOCKED", "422", "VALIDATION_FAILED")),
            entry("POST /auth/login", Map.of("401", "INVALID_CREDENTIALS", "403", "AUTH_ACCOUNT_BLOCKED · AUTH_STAFF_INACTIVE")),
            entry("POST /auth/logout", Map.of("401", "TOKEN_EXPIRED")),
            entry("POST /auth/password", Map.of("401", "INVALID_CREDENTIALS", "422", "VALIDATION_FAILED")),
            entry("POST /auth/recover", Map.of("403", "VERIFICATION_CODE_INVALID", "422", "VALIDATION_FAILED", "429", "RECOVERY_RATE_LIMITED", "503", "RECOVERY_UNAVAILABLE")),
            entry("POST /auth/refresh", Map.of("401", "TOKEN_EXPIRED")),
            entry("POST /auth/signup", Map.of("404", "ACADEMY_NOT_FOUND", "409", "DUPLICATE_LOGIN_ID", "422", "VALIDATION_FAILED")),
            entry("POST /auth/signup/reapply", Map.of("404", "ACADEMY_NOT_FOUND", "409", "REAPPLY_NOT_ALLOWED")),
            entry("POST /me/students/link", Map.of("403", "LINK_CODE_INVALID", "409", "ALREADY_LINKED")),
            entry("POST /runs/{runId}/ack-changes", Map.of("403", "FORBIDDEN", "404", "RUN_NOT_FOUND", "409", "RUN_NOT_CONFIRMED")),
            entry("POST /runs/{runId}/delay", Map.of("403", "ESCORT_ONLY · FORBIDDEN", "409", "RUN_NOT_MOVING · DELAY_DUPLICATE", "422", "VALIDATION_FAILED")),
            entry("POST /runs/{runId}/emergency", Map.of("403", "FORBIDDEN", "409", "RUN_NOT_CONFIRMED", "422", "VALIDATION_FAILED")),
            entry("POST /runs/{runId}/position", Map.of("403", "DRIVER_ONLY", "404", "RUN_NOT_FOUND", "409", "RUN_NOT_MOVING")),
            entry("POST /runs/{runId}/reports", Map.of("404", "RIDER_NOT_FOUND · RUN_NOT_FOUND", "422", "VALIDATION_FAILED")),
            entry("POST /runs/{runId}/riders/{riderId}/no-show-contacts", Map.of("403", "ESCORT_ONLY", "404", "NO_SHOW_CASE_NOT_FOUND · RIDER_NOT_FOUND", "409", "RUN_NOT_MOVING")),
            entry("POST /runs/{runId}/riders/{riderId}/revert", Map.of("403", "ESCORT_ONLY", "404", "RIDER_NOT_FOUND · RUN_NOT_FOUND", "409", "RUN_NOT_MOVING · STOP_ALREADY_DEPARTED")),
            entry("POST /runs/{runId}/start", Map.of("403", "DRIVER_ONLY · START_WINDOW_CLOSED · FORBIDDEN", "404", "RUN_NOT_FOUND", "409", "RUN_ALREADY_STARTED · RUN_NOT_CONFIRMED")),
            entry("POST /runs/{runId}/stops/{stopId}/arrive", Map.of("403", "DRIVER_ONLY · DUPLICATE_ARRIVE", "404", "STOP_NOT_FOUND · RUN_NOT_FOUND", "409", "RUN_NOT_MOVING")),
            entry("POST /staff/approvals/{id}/decide", Map.of("403", "CHANGE_WINDOW_CLOSED", "404", "APPROVAL_NOT_FOUND", "409", "APPROVAL_ALREADY_DECIDED · PREVIEW_STALE", "422", "VALIDATION_FAILED")),
            entry("POST /staff/buses", Map.of("409", "CAPACITY_EXCEEDED · DUPLICATE_BUS_NO")),
            entry("POST /staff/emergencies/{id}/ack", Map.of("404", "EMERGENCY_NOT_FOUND", "409", "ALREADY_ACKED")),
            entry("POST /staff/reports/{id}/handle", Map.of("404", "REPORT_NOT_FOUND")),
            entry("POST /staff/routes", Map.of("404", "BUS_NOT_FOUND", "409", "DUPLICATE_ROUTE", "422", "VALIDATION_FAILED")),
            entry("POST /staff/routes/{id}/optimize", Map.of("404", "ROUTE_NOT_FOUND", "422", "ACADEMY_COORDINATES_MISSING · VALIDATION_FAILED", "503", "MAP_ROUTE_UNAVAILABLE")),
            entry("POST /staff/runs", Map.of("404", "BUS_NOT_FOUND", "409", "DUPLICATE_RUN")),
            entry("POST /staff/runs/{runId}/forced-add", Map.of("403", "CHANGE_WINDOW_CLOSED", "404", "RUN_NOT_FOUND", "409", "CAPACITY_EXCEEDED", "422", "ADDRESS_VERIFICATION_FAILED · VALIDATION_FAILED")),
            entry("POST /staff/runs/{runId}/waypoints", Map.of("403", "CHANGE_WINDOW_CLOSED", "404", "RUN_NOT_FOUND", "409", "RUN_NOT_CONFIRMED", "422", "ADDRESS_VERIFICATION_FAILED · VALIDATION_FAILED · ROUTE_NOT_CONFIGURED_FOR_RUN")),
            entry("POST /staff/schedules", Map.of("404", "BUS_NOT_FOUND", "409", "DUPLICATE_SCHEDULE")),
            entry("POST /staff/signup-requests/{id}/decide", Map.of("403", "FORBIDDEN", "404", "SIGNUP_REQUEST_NOT_FOUND · STUDENT_NOT_FOUND · MANAGER_NOT_FOUND", "409", "APPROVAL_ALREADY_DECIDED · SIGNUP_TARGET_BLOCKED", "422", "LINK_REQUIRED · VALIDATION_FAILED")),
            entry("POST /staff/accounts/{accountId}/password-reset", Map.of("404", "ACCOUNT_NOT_FOUND")),
            entry("POST /staff/students/{id}/transfer", Map.of("403", "CHANGE_WINDOW_CLOSED · ACADEMY_SCOPE_VIOLATION", "404", "RUN_NOT_FOUND", "409", "CAPACITY_EXCEEDED · STUDENT_ALREADY_IN_RUN · STUDENT_NOT_IN_RUN · TRANSFER_ALREADY_STAGED", "422", "ADDRESS_VERIFICATION_FAILED · VALIDATION_FAILED")),
            entry("POST /students/{id}/change-requests", Map.of("403", "CHANGE_WINDOW_CLOSED · CHANGE_LIMIT_REACHED · FORBIDDEN", "404", "RUN_NOT_FOUND · STUDENT_NOT_FOUND", "422", "ADDRESS_VERIFICATION_FAILED")),
            entry("PUT /staff/routes/{id}/stops", Map.of("404", "ROUTE_NOT_FOUND", "422", "VALIDATION_FAILED")));

    private EndpointErrorResponses() {
    }
}
