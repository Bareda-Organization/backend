package src.backend.monitoring.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 운행 대시보드 응답(§5.3 {@code GET /staff/dashboard}, MON-01·02·03·04·06).
 *
 * @param metrics 금일 요약 지표 5종
 * @param runs 금일 회차 표 — 취소된 회차도 싣는다({@code RunRepository
 *             #findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc} 와 같은 근거, §5.10 목록과
 *             정합)
 */
public record StaffDashboardResponse(Metrics metrics, List<Run> runs) {

    /**
     * @param movingBuses 운행 중({@code status=moving}) 회차 수 — 차량이 아니라 회차 기준이다.
     *                    한 차량이 하루에 등원·하원 두 회차를 뛰어도 동시에 {@code moving} 인 회차는
     *                    하나뿐이라 이중 집계가 나지 않는다
     * @param boarded 금일 전 회차의 승차 완료({@code status=boarded}) 인원 합
     * @param noShow 금일 전 회차의 미승차({@code status=no_show}) 인원 합(MON-04)
     * @param absent 금일 전 회차의 미등원({@code status=absent}) 인원 합(MON-06)
     * @param unassignedManagers 오늘 어느 회차에도 배치되지 않은 재직 매니저 수(Ruling 233 — 정본이
     *                           침묵해 조율자 권장값을 따름, 근거는 서비스 자바독)
     */
    public record Metrics(int movingBuses, int boarded, int noShow, int absent, int unassignedManagers) {
    }

    /**
     * @param boardedCount 이 회차의 승차 완료 인원(집합은 {@link #totalCount} 와 같은 명단)
     * @param totalCount 이 회차 명단 전체 인원 — {@code change=removed} 로 표시된 행도 포함한다
     *                   (§5.4 {@code guardian_phone} 자바독과 같은 원칙: 제외는 표시이지 삭제가
     *                   아니다). Ruling 233 이 정본 침묵 필드로 지목한 값 중 하나 — 근거는 서비스
     *                   자바독
     * @param runStatus {@code idle} · {@code confirmed} · {@code moving} · {@code finished}
     * @param addedCount·{@code removedCount} MON-05 변경분 — {@code run_rider.change}
     * @param ackDriver·{@code ackEscort} 기사·동승자 노선 확인 응답 여부(RUN-07) — 노선 미확정이면
     *        항상 거짓
     * @param noShowCases 진행 중 에스컬레이션만(해소된 것은 제외)
     */
    public record Run(Long runId, String busNo, String direction, OffsetDateTime departTime, String driverName,
            String escortName, int boardedCount, int totalCount, String runStatus, int addedCount, int removedCount,
            boolean ackDriver, boolean ackEscort, List<NoShowCase> noShowCases) {
    }

    public record NoShowCase(String studentName, String stopName, OffsetDateTime expiresAt) {
    }
}
