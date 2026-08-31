package src.backend.boarding.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 매니저 앱의 승하차지별 명단(API_SPEC §4.2 {@code GET /runs/{runId}/roster}, RST-01·02·04·M-03,
 * Phase 9 목표 6·15) — 관계자 웹({@code StaffRosterItemResponse})과 <b>같은 원본 데이터를 다른
 * 모양으로</b> 담는다.
 *
 * <p>{@code no_show_case}(§4.2, EXC-01 3분 카운트다운)는 <b>싣지 않는다</b> — 근거가 될
 * {@code no_show_case} 저장소·서비스가 이 저장소에 존재하지 않고, 이번 다섯 목표(6·8·15·16·17)
 * 어디에도 걸리지 않는다. 사양 표에서도 선택(○) 필드라 생략이 계약 위반이 아니다.
 */
public record ManagerRosterResponse(Long runId, String busNo, String direction, Counts counts,
        List<StopGroup> stops) {

    /** {@code absent} 는 개인 행이 아니라 이 집계에만 존재한다(RST-02·04). */
    public record Counts(long boarded, long waiting, long noShow, long absentN) {
    }

    public record StopGroup(Long stopId, int seq, String name, String address, String change, String skipNotice,
            OffsetDateTime arrivedAt, List<RosterStudent> students) {
    }

    /** {@code guardianPhone} 은 이미 마스킹된 값이다({@code GuardianPhoneMasker}) — 여기서 다시 가리지 않는다. */
    public record RosterStudent(Long riderId, Long studentId, String name, String photoUrl, String className,
            String guardianPhone, String note, boolean canGoAlone, String status, String change) {
    }
}
