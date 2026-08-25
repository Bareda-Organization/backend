package src.backend.account.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.account.entity.SignupRequest;

/** {@link SignupRequest} 영속성 접근. */
public interface SignupRequestRepository extends JpaRepository<SignupRequest, Long> {

    /**
     * 계정의 최신 가입 요청(API_SPEC §2.3 승인 대기 화면) — 재신청은 새 행을 쌓으므로(엔티티 javadoc),
     * 화면에 보여줄 "지금" 상태는 항상 가장 최근 요청 1건이다.
     */
    Optional<SignupRequest> findTopByAccountIdOrderByRequestedAtDesc(Long accountId);
}
