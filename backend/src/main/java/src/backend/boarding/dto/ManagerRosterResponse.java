package src.backend.boarding.dto;

import java.time.OffsetDateTime;
import java.util.List;

import src.backend.exception.dto.NoShowContactView;

/**
 * 매니저 앱의 승하차지별 명단(API_SPEC §4.2 {@code GET /runs/{runId}/roster}, RST-01·02·04·M-03,
 * Phase 9 목표 6·15) — 관계자 웹({@code StaffRosterItemResponse})과 <b>같은 원본 데이터를 다른
 * 모양으로</b> 담는다.
 *
 * <p>{@code no_show_case}(§4.2, EXC-01 카운트다운)는 열린 미승차 케이스가 있는 학생에게만 싣는다 —
 * 앱이 재실행·재진입해도 PATCH 응답에만 있던 대기 만료 시각을 되찾는 경로다(BR-081).
 */
public record ManagerRosterResponse(String runId, String busNo, String direction, Counts counts,
        List<StopGroup> stops) {

    /** {@code absent} 는 개인 행이 아니라 이 집계에만 존재한다(RST-02·04). */
    public record Counts(long boarded, long waiting, long noShow, long absentN) {
    }

    /**
     * {@code stopId} 는 정차 항목 id({@code run_stop.id})다 — 매니저 앱이 이 값을 도착 처리(§4.5)에 그대로
     * 넘긴다. 등원 회차의 마지막 항목은 학원({@code isDestination=true}, {@code students} 빈 배열)이고,
     * 이 항목이 없으면 앱에 등원 운행을 끝낼 도착 버튼이 생기지 않는다(Ruling 327).
     */
    public record StopGroup(String stopId, int seq, String name, String address, String change, String skipNotice,
            OffsetDateTime arrivedAt, boolean isDestination, List<RosterStudent> students) {
    }

    /** {@code guardianPhone} 은 이미 마스킹된 값이다({@code GuardianPhoneMasker}) — 여기서 다시 가리지 않는다. */
    public record RosterStudent(String riderId, String studentId, String name, String photoUrl, String className,
            String guardianPhone, String note, boolean canGoAlone, String status, String change,
            NoShowCountdown noShowCase) {
    }

    /**
     * 미승차 대기 카운트다운(§4.2 {@code no_show_case}) — 열린 케이스가 없으면 {@code null}. {@code contacts} 는 그 케이스의 연락
     * 시도 이력으로 시각순이며 없으면 빈 배열이다(Ruling 823). {@code caseId} 는
     * 문자열이다 — 매니저 앱이 §4.6 응답과 같은 모델({@code NoShowCase.fromJson}, {@code case_id} 필수 문자열)로
     * 읽는다(§1.1 · Ruling 332).
     */
    public record NoShowCountdown(String caseId, OffsetDateTime startedAt, OffsetDateTime expiresAt,
            List<NoShowContactView> contacts) {
    }
}
