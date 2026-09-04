package src.backend.run.dto;

/**
 * 지연 알림 신고 응답(NTF-06, API_SPEC §4.9) — 각 수신 갈래에 실제로 발신했는지(대상이 1명 이상이었는지).
 */
public record DelayResponse(boolean notifiedGuardians, boolean notifiedStudents, boolean notifiedStaff) {
}
