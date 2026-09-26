package src.backend.boarding.rider;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.ChangeType;

/**
 * 한 학생의 명단 소속 읽기 전용 조회 — {@code student} 모듈의 회차 판정({@code StudentRunResolver} ·
 * {@code StudentRouteQueryService} · {@code StudentRunsQueryService})이 {@code run_rider} 저장소를
 * 직접 참조하지 않도록 두는 진입점이다(BR-094 · BR-162, ARCHITECTURE §3.3 읽기 방향). {@code query} 가
 * 아니라 이 이름의 패키지에 두는 이유는 {@code StudentRunResolver} 가 명령 경로
 * ({@code TargetRunLookup})에서도 호출돼, {@code query} 에 두면 §7 "Command는 Query를 호출하지
 * 않는다" 를 새로 어기기 때문이다(CODE_CONVENTIONS §3 "공유 읽기 계층").
 */
@Component
@RequiredArgsConstructor
public class RunRiderReader {

    private final RunRiderRepository runRiderRepository;

    /** 그 회차 그 학생의 명단 행(있으면) — 확정 이후 회차의 소속·정차지·탑승 상태 판정에 쓰인다. */
    public Optional<Entry> findRider(Long runId, Long studentId) {
        return runRiderRepository.findByRunIdAndStudentId(runId, studentId)
                .map(rider -> new Entry(rider.getStopId(), rider.getStatus(), rider.getChange()));
    }

    /**
     * 그 회차들 중 이 학생이 명단에 있는(버스 간 이동으로 빠지지 않은) 회차 id — 학생 회차 판정이
     * 회차마다 명단을 따로 묻지 않게 한 번에 모은다(BR-058).
     */
    public Set<Long> runIdsExcludingRemoved(Long academyId, Long studentId, Collection<Long> runIds) {
        return Set.copyOf(runRiderRepository.findRunIdsByAcademyIdAndStudentIdAndRunIdIn(academyId, studentId,
                runIds, ChangeType.REMOVED));
    }

    /** 정차지 · 탑승 상태 · 버스 간 이동으로 인한 변경 구분(있으면). */
    public record Entry(Long stopId, RiderStatus status, ChangeType change) {
    }
}
