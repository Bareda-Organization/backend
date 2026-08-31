package src.backend.boarding.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.boarding.entity.RiderStatusHistory;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link RiderStatusHistory} 영속성 접근 — {@code rider_status_history} 는 {@code run_rider} 를
 * 부모로 두는 부모 경유 자원이다(ERD §6.1). DB FK 가 없어(엔티티 javadoc 참고) 조인으로 학원 조건을
 * 붙일 자리 자체가 없다 — 두 조회 모두 호출부가 이미 학원 범위로 좁혀 확인한 {@code run_rider} 를
 * 전제로 삼는다.
 */
public interface RiderStatusHistoryRepository extends JpaRepository<RiderStatusHistory, Long> {

    /**
     * 오프라인 큐 재전송 멱등 판정의 근거(API_SPEC §1.7, 목표 12) — {@code client_key} 는
     * {@code uk_rider_status_history_client_key} UNIQUE 라 한 건 이하다. 이 값으로 이미 처리된
     * 요청인지 먼저 확인하고, 있으면 새 이력을 쌓지 않고 저장된 현재 상태로만 응답을 재구성한다.
     */
    @AcademyScopeExempt(reason = "client_key 는 클라이언트가 생성한 UUID 라 그 자체로 전 학원에서 유일하고 "
            + "추측 불가능하다. 호출부가 그 값으로 찾은 이력 행의 run_rider_id 를 이미 학원 범위로 확인한 "
            + "runId 와 대조하지 않으면 타 학원 행을 잘못 재사용할 수 있어, 그 대조는 서비스 계층의 책임이다")
    Optional<RiderStatusHistory> findByClientKey(UUID clientKey);

    /**
     * 되돌리기(BRD-05, API_SPEC §4.7, 목표 13)가 되돌아갈 직전 상태의 근거 — 그 탑승자의 가장 최근
     * 이력 행 1건. {@code fromStatus} 가 되돌린 뒤의 새 상태가 된다.
     *
     * <p>{@code runRiderId} 는 호출부가 {@link RunRiderRepository#findByIdAndRunIdAndStatusNot} 으로
     * 이미 학원 범위로 확인한 탑승자의 식별자라는 전제다.
     */
    @AcademyScopeExempt(reason = "runRiderId 는 호출부가 RunRiderRepository.findByIdAndRunIdAndStatusNot 으로 "
            + "이미 학원 범위로 확인한 탑승자의 식별자라는 전제다")
    Optional<RiderStatusHistory> findFirstByRunRiderIdOrderByChangedAtDescIdDesc(Long runRiderId);
}
