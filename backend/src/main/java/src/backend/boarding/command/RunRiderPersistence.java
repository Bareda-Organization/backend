package src.backend.boarding.command;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.run.roster.ProjectedRoster;

/**
 * {@code run_rider} 쓰기의 유일한 진입점(BR-095, `ARCHITECTURE §3.3` — "다른 모듈 소유 테이블은 그
 * 모듈의 진입점을 거쳐서만 쓴다") — 확정 배치(v1, {@code run} 소유)의 최초 탑승자 생성과 ②구간 승인
 * 결정({@code request} 소유)의 상태 반영을 이 클래스로 모았다(2026-09-25 검사 W09-11). 두 호출부가
 * {@code RunRiderRepository} 를 직접 {@code save}/{@code saveAll} 하던 것을 없앤다.
 */
@Component
@RequiredArgsConstructor
public class RunRiderPersistence {

    private final RunRiderRepository runRiderRepository;

    /**
     * 확정 배치가 처음 만드는 탑승자 명단({@code run} 소유 {@code RunConfirmationPersistence#persist}
     * 전용) — 좌표를 얻지 못해 계산에서 분리된 학생({@code unresolvedStudentIds})은 명단에서도 뺀다.
     * OFF 학생은 {@code absent}, 출발 이동 학생은 {@code absent · removed}, 당일 추가 학생은
     * {@code added} 로 남긴다(목표 2와 같은 근거).
     */
    @Transactional
    public List<RunRider> confirmRiders(Long runId, ProjectedRoster roster, List<Long> unresolvedStudentIds,
            OffsetDateTime confirmedAt) {
        return runRiderRepository.saveAll(ridersOf(runId, roster, unresolvedStudentIds, confirmedAt));
    }

    /**
     * ②구간 승인 결정을 이미 로드한 탑승자 행에 반영한다({@code request} 소유
     * {@code ChangeRequestDecisionService#approve} 전용) — 취소는 결석 표시, 이동은 승하차지 변경.
     * 재조회하지 않고 호출부가 지문 재검증에 이미 쓴 인스턴스를 그대로 받는다(중복 조회 방지).
     */
    @Transactional
    public RunRider applyApprovalDecision(RunRider target, boolean cancel, Long newStopId, OffsetDateTime decidedAt) {
        if (cancel) {
            target.markAbsent(decidedAt);
        } else {
            target.relocateTo(newStopId, decidedAt);
        }
        return runRiderRepository.save(target);
    }

    private static List<RunRider> ridersOf(Long runId, ProjectedRoster roster, List<Long> unresolvedStudentIds,
            OffsetDateTime confirmedAt) {
        List<RunRider> riders = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : roster.studentStops().entrySet()) {
            Long studentId = entry.getKey();
            if (unresolvedStudentIds.contains(studentId)) {
                continue;
            }
            RunRider rider = RunRider.uponConfirmation(runId, studentId, entry.getValue());
            if (roster.addedStudentIds().contains(studentId)) {
                rider.markAdded();
            }
            riders.add(rider);
        }
        for (Map.Entry<Long, Long> entry : roster.absentStops().entrySet()) {
            RunRider rider = RunRider.uponConfirmation(runId, entry.getKey(), entry.getValue());
            rider.markAbsent(confirmedAt);
            riders.add(rider);
        }
        for (Map.Entry<Long, Long> entry : roster.removedStops().entrySet()) {
            RunRider rider = RunRider.uponConfirmation(runId, entry.getKey(), entry.getValue());
            rider.markRemoved(confirmedAt);
            riders.add(rider);
        }
        return riders;
    }
}
