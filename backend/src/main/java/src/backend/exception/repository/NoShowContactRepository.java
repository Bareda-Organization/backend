package src.backend.exception.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.exception.entity.NoShowContact;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link NoShowContact} 영속성 접근(Phase 11 목표 3) — {@code no_show_contact} 는 {@code no_show_case}
 * 를 부모로 두는 부모 경유 자원이다(ERD §6.1). {@code no_show_case} 자체도 부모 경유라 학원까지 2단
 * 조인이 필요한데, 실제로는 그럴 필요가 없다 — 아래 조회의 {@code noShowCaseId} 가 전부 호출부
 * ({@code NoShowContactCommandService})가 이미 학원 범위로 확인한 케이스의 식별자이기 때문이다.
 */
public interface NoShowContactRepository extends JpaRepository<NoShowContact, Long> {

    /**
     * 한 케이스의 연락 시도 이력 전부(§4.8 응답 조립) — 시도 순으로 정렬한다.
     *
     * <p>{@code noShowCaseId} 는 호출부가 {@code NoShowCaseRepository.findByRunRiderId} 로 이미 학원
     * 범위(그 위의 {@code RunRiderRepository.findByIdAndRunIdAndStatusNot} 경유)로 확인한 케이스의
     * 식별자라는 전제다.
     */
    @AcademyScopeExempt(reason = "noShowCaseId 는 호출부가 NoShowCaseRepository.findByRunRiderId 로 이미 학원 "
            + "범위(그 위의 RunRiderRepository.findByIdAndRunIdAndStatusNot 경유)로 확인한 케이스의 식별자다")
    List<NoShowContact> findAllByNoShowCaseIdOrderByAttemptedAtAsc(Long noShowCaseId);
}
