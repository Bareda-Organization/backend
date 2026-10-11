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
 * <p><b>규칙은 하나다 — 회차 행을 먼저 쥔다.</b> 그 뒤에 쥐는 행의 순서는 경로마다 다르다 — 승하차 기록 · 되돌리기는 회차 → 탑승자 → 승하차지,
 * 도착 처리는 회차 → 승하차지(앞 승하차지 강제 출발) → 탑승자(등원 최종 도착의 자동 하차), 운행 시작 · 변경 결정(승인 · 거절)은 회차 → 변경 요청
 * ({@code change_request}) → 탑승 의사다. 이 클래스를 거치는 호출부는 승하차 기록 · 되돌리기 · 도착 처리 · 출발 선점의 호출부(다음 승하차지 도착
 * 폴백 · 운행 종료 강제 출발) · ③구간 미등원 · 변경 결정(승인 · 거절)이다(운행 시작은 같은 잠금을 {@code refresh} 로 직접 쥔다). 근접 판정 스케줄러의 출발 선점 · 근접
 * 알림 선점은 승하차지 행 하나만 갱신하고 다른 잠금을 기다리지 않는 말단이고, 자동 거절 스케줄러는 변경 요청 → 탑승 의사 두 행만 쥐며 회차 행을 기다리는
 * 경로가 아니라서, 회차 행을 쥐지 않아도 순환이 되지 않는다. 전에는 승하차 기록이 {@code 탑승자 → 승하차지 → 회차}, 도착 처리가 {@code 회차 →
 * 승하차지} 로 반대여서 하원 마지막 승하차지에서 둘이 겹치면, 변경 승인이 {@code 변경 요청 → 회차}, 운행 시작이 {@code 회차 → 변경 요청} 이어서 출발
 * ±10분에 둘이 겹치면 서로를 기다리는 교착({@code deadlock detected})으로 한쪽이 500 이었다. 한쪽만 바꾸면 다시 교착이므로 이 클래스를 거치지 않고 이동
 * 중 회차의 탑승자 · 승하차지 · 변경 요청 행을 잠그는 새 경로를 만들지 않는다.
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
     * 경로(도착 처리의 앞 승하차지 강제 출발 · ③구간 미등원)가 쓴다. 없는 회차는 잠글 행이 없으므로 그대로 지나간다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(Long runId, Long academyId) {
        runRepository.findLockedByIdAndAcademyId(runId, academyId);
    }
}
