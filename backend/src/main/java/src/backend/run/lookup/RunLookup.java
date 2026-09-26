package src.backend.run.lookup;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 회차 단건·그날 전체 조회 — {@code student} 모듈의 회차 소속 판정({@code StudentRunResolver})이
 * {@code run} 저장소를 직접 참조하지 않도록 두는 진입점이다(BR-094 · BR-162, ARCHITECTURE §3.3 읽기
 * 방향 — student→run 은 명시된 허용 방향이 아니다). {@code query} 가 아니라 이 이름의 패키지에 두는
 * 이유는 {@code StudentRunResolver} 가 명령 경로({@code TargetRunLookup})에서도 호출돼, {@code query}
 * 에 두면 §7 "Command는 Query를 호출하지 않는다" 를 새로 어기기 때문이다(CODE_CONVENTIONS §3 "공유
 * 읽기 계층", {@code run/roster/ProjectedRosterReader} 와 같은 형태).
 */
@Component
@RequiredArgsConstructor
public class RunLookup {

    private final RunRepository runRepository;

    /** 학원 범위로 좁힌 회차 1건 — 없으면 {@link Optional#empty()}. */
    public Optional<Run> findByIdAndAcademyId(Long runId, Long academyId) {
        return runRepository.findByIdAndAcademyId(runId, academyId);
    }

    /** 그 학원의 그날 회차 전부 — 출발 시각 순. */
    public List<Run> findAllByAcademyIdAndServiceDate(Long academyId, LocalDate serviceDate) {
        return runRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(academyId, serviceDate);
    }
}
