package src.backend.run.command;

import java.time.OffsetDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.Direction;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/**
 * 하원 회차의 보류 중인 종료를 실제로 매듭짓는다(API_SPEC §4.5·§4.6, C-15) — 최종 지점 도착 처리
 * 시점엔 잔류(미하차) 탑승자가 있어 {@link Run#deferFinish()} 로 미뤄 둔 종료가, 그 뒤 잔류가 0명이
 * 되는 순간(개별 하차 처리) 이 서비스를 통해 완성된다.
 *
 * <p>호출부는 이 태스크(T2)가 아니다 — 동승자의 개별 하차 처리(§4.6 {@code PATCH})가 그 호출부이고,
 * 이 서비스는 그 화면이 아직 없어도 먼저 준비돼야 한다(T2 ↔ T3 의존). 그래서 이 클래스는 <b>이미
 * 로드된 {@link Run} 엔티티</b>를 받는다 — 호출부가 학원·회차 조회(404 판정)를 이미 마쳤다는 전제이고,
 * 이 서비스가 다시 조회하면 그 판정을 중복하게 된다.
 *
 * <p>{@code @Transactional(propagation = MANDATORY)} 다 — 호출부가 이미 연 트랜잭션 안에서
 * {@code run_rider} UPDATE 와 {@code run.status} 전이가 <b>한 트랜잭션으로</b> 묶인다. ⚠ 그것만으로는
 * 경합을 막지 못한다(BR-041) — READ COMMITTED 에서 남은 두 명의 하차가 동시에 오면 각자 상대의 미커밋
 * 하차를 못 본 채 "1명 남음" 으로 세고 둘 다 종료를 건너뛴다. 그래서 세기 전에 회차 행을 잠그고
 * 다시 읽는다 — 늦게 온 쪽은 앞쪽 커밋 뒤에 세므로 마지막 커밋이 종료를 판정한다.
 */
@Component
@RequiredArgsConstructor
public class RunCompletionService {

    private final RunRiderRepository runRiderRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 보류 중인 종료를 지금 매듭지을 수 있으면 짓는다 — 실제로 종료했으면 {@code true}.
     *
     * <p>보류 중이 아니면(등원 회차, 또는 하원이라도 최종 지점에서 즉시 종료된 경우) 아무 일도
     * 하지 않고 {@code false} 다 — 이 메서드를 걸 때마다 호출해도 안전하다는 뜻이다.
     *
     * <p>등원 회차는 잠그지 않고 바로 돌아간다 — 보류가 하원에만 있고, 등원 학원 도착 처리는 회차 행을
     * 잡은 채 탑승자 행을 고치므로 여기서 회차 행을 기다리면 서로를 기다리는 교착이 된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeIfAllAlighted(Run run, OffsetDateTime decidedAt) {
        if (run.getDirection() != Direction.FROM_ACADEMY) {
            return false;
        }
        // 다시 읽기 전에 이 트랜잭션의 변경(방금 한 하차 등)을 먼저 내보낸다 — 안 그러면 다시 읽기가 그 변경을 지운다.
        entityManager.flush();
        entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        if (!run.isFinishPending() || run.getStatus() != RunStatus.MOVING) {
            return false;
        }
        long stillBoarded = runRiderRepository.countByRunIdAndStatus(run.getId(), RiderStatus.BOARDED);
        if (stillBoarded > 0) {
            return false;
        }
        run.finish(decidedAt);
        return true;
    }
}
