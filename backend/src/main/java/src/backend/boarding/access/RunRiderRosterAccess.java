package src.backend.boarding.access;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;

/**
 * {@code run_rider} 공유 읽기(BR-094, `CODE_CONVENTIONS §7` — "Command 가 조회가 필요하면 Query
 * 서비스를 부르는 대신 공유 읽기 계층으로 내린다") — {@code routing} 소유
 * {@code RouteContextAssembler}(재최적화 문맥 조립)가 이 명단을 읽어야 하는데, {@code query} 패키지의
 * 조회 서비스를 직접 부르면 CQRS 위반(Command→Query)이 새로 생긴다. {@code access} 로 두면 그 위반이
 * 성립하지 않는다({@code student/access} 와 같은 근거).
 */
@Component
@RequiredArgsConstructor
public class RunRiderRosterAccess {

    private final RunRiderRepository runRiderRepository;

    /** 회차의 탑승자 전체 — 상태별 분류는 호출부가 한다(재최적화 명단 조립·확정 배치 등 쓰임새가 다르다). */
    @Transactional(readOnly = true)
    public List<RunRider> ridersOf(Long runId, Long academyId) {
        return runRiderRepository.findAllByRunIdAndAcademyId(runId, academyId);
    }
}
