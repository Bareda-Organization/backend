package src.backend.run.roster;

import java.util.List;
import java.util.Map;
import java.util.Set;

import src.backend.run.entity.RunTransfer;

/**
 * 확정 전 회차의 예정 명단({@link ProjectedRosterReader} 결과).
 *
 * @param studentIds        노선 계산에 넣을 학생 — 이 수가 정원 판정의 현재 인원이다(BUS-04)
 * @param stopOverrides     요일별 주소를 이기는 그날의 승하차지(P-06 이동·강제 추가·도착 이동)
 * @param studentStops      학생 → 그날의 승하차지(요일별 주소에 {@code stopOverrides} 를 덮은 것)
 * @param absentStops       ①구간 OFF 학생 → 승하차지. 노선 계산에서 빠지고 {@code absent} 행으로만 남는다
 * @param addedStudentIds   요일별 주소에 없다가 강제 추가·도착 이동으로 올라온 학생 — {@code change=added}
 * @param removedStops      출발 이동으로 빠진 학생 → 승하차지. {@code absent · change=removed} 행으로 남는다(RTE-04)
 * @param incomingTransfers 이 회차가 도착인 이동 대기 건 — 확정이 명단에 더하며 {@code applied} 로 표시한다
 * @param stagedRowCount    읽은 강제 추가·이동 행 수 — 확정 저장이 다시 세어 그 사이 들어온 행을 알아챈다(BR-044)
 */
public record ProjectedRoster(List<Long> studentIds, Map<Long, Long> stopOverrides, Map<Long, Long> studentStops,
        Map<Long, Long> absentStops, Set<Long> addedStudentIds, Map<Long, Long> removedStops,
        List<RunTransfer> incomingTransfers, int stagedRowCount) {

    public ProjectedRoster {
        studentIds = List.copyOf(studentIds);
        stopOverrides = Map.copyOf(stopOverrides);
        studentStops = Map.copyOf(studentStops);
        absentStops = Map.copyOf(absentStops);
        addedStudentIds = Set.copyOf(addedStudentIds);
        removedStops = Map.copyOf(removedStops);
        incomingTransfers = List.copyOf(incomingTransfers);
    }

    /** 정원 판정의 현재 인원. */
    public int size() {
        return studentIds.size();
    }

    /** 그 학생이 이 회차 예정 명단에 있는가(§5.8 {@code STUDENT_NOT_IN_RUN} 판정). */
    public boolean contains(Long studentId) {
        return studentIds.contains(studentId);
    }
}
