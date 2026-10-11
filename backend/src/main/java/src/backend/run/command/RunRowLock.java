package src.backend.run.command;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 이동 중 회차의 쓰기 경로가 <b>가장 먼저</b> 쥐는 회차 행 잠금 — 잠금 순서 규칙의 단일 진입점이다(R53, 게이트 I1).
 *
 * <p><b>순서는 회차({@code run}) → 탑승자({@code run_rider}) → 승하차지({@code run_stop}) 하나다.</b> 승하차 기록 · 되돌리기 · 도착 처리 ·
 * 출발 선점(근접 판정 · 다음 승하차지 도착 폴백 · 운행 종료 강제 출발) · ③구간 미등원이 모두 이 순서로 쥔다. 전에는 승하차 기록이
 * {@code 탑승자 → 승하차지 → 회차}, 도착 처리가 {@code 회차 → 승하차지} 로 반대여서, 하원 마지막 승하차지에서 둘이 겹치면 서로를 기다리는
 * 교착({@code deadlock detected})으로 한쪽이 500 이었다. 한쪽만 바꾸면 다시 교착이므로 이 클래스를 거치지 않고 이동 중 회차의
 * 탑승자 · 승하차지 행을 잠그는 새 경로를 만들지 않는다.
 *
 * <p>회차 행 하나로 직렬화하는 비용은 같은 회차의 쓰기 요청이 줄을 서는 것뿐이다 — 한 회차의 쓰기는 동승자 1명 · 기사 1명 · 위치 판정 스케줄러가
 * 보내고 요청 하나가 수십 ms 로 끝나, 다른 회차의 요청에는 영향이 없다.
 */
@Component
@RequiredArgsConstructor
public class RunRowLock {

    private final RunRepository runRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 이미 읽은 회차를 잠그며 <b>상태를 새로 읽는다</b> — 잠금 조회는 영속성 컨텍스트에 이미 있는 엔티티의 상태를 다시 읽지 않아, 낡은
     * 상태로 판정하면 그 사이 끝난 회차에 쓰게 된다. 같은 트랜잭션에서 다시 불러도 이미 쥔 잠금이라 기다리지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(Run run) {
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * 엔티티를 쓰지 않고 행만 잠근다 — 호출 뒤에 영속성 컨텍스트를 비우는 갱신({@code clearAutomatically})이 따라와서 회차를 나중에 읽어야 하는
     * 경로(도착 처리의 앞 승하차지 강제 출발 · 출발 선점)가 쓴다. 없는 회차는 잠글 행이 없으므로 그대로 지나간다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(Long runId, Long academyId) {
        runRepository.findLockedByIdAndAcademyId(runId, academyId);
    }
}
