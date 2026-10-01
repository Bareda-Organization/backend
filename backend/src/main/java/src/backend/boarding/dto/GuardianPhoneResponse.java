package src.backend.boarding.dto;

/**
 * 매니저 앱이 [전화] 를 눌렀을 때 받는 보호자 원번호 1건(API_SPEC §4.2.1 {@code GET /runs/{runId}/riders/{riderId}/guardian-phone},
 * Ruling 482·521) — 명단 응답({@code guardian_phone})은 마스킹이라 이 값과 갈린다. 연결된 보호자가 없으면 {@code null}.
 */
public record GuardianPhoneResponse(String guardianPhone) {
}
