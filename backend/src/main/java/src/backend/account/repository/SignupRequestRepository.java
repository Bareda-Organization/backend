package src.backend.account.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.account.entity.SignupRequest;
import src.backend.global.security.access.AcademyScopeExempt;

/** {@link SignupRequest} 영속성 접근. */
public interface SignupRequestRepository extends JpaRepository<SignupRequest, Long> {

    /**
     * 계정의 최신 가입 요청(API_SPEC §2.3 승인 대기 화면) — 재신청은 새 행을 쌓으므로(엔티티 javadoc),
     * 화면에 보여줄 "지금" 상태는 항상 가장 최근 요청 1건이다.
     */
    @AcademyScopeExempt(reason = "§2.3 본인 가입 상태 조회 — 계정 경유라 학원이 이미 결정, 학원 조건을 더해도 좁혀지는 것이 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<SignupRequest> findTopByAccountIdOrderByRequestedAtDesc(Long accountId);
}
