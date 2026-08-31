package src.backend.exception.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.exception.entity.NoShowCase;
import src.backend.global.security.access.AcademyScopeExempt;

/**
 * {@link NoShowCase} 영속성 접근 — {@code no_show_case} 는 {@code run_rider} 를 부모로 두는 부모 경유
 * 자원이다(ERD §6.1). Phase 9 목표 7 이 처음으로 이 저장소를 만든다: 승하차 처리가 {@code no_show} 로
 * 전이할 때 케이스를 만들고, 그 응답 조립이 이미 있는 케이스를 되읽는다.
 */
public interface NoShowCaseRepository extends JpaRepository<NoShowCase, Long> {

    /**
     * 그 탑승자의 미승차 케이스 — {@code run_rider_id} 가 UNIQUE 라 한 건 이하다(목표 7·12, 응답의
     * {@code no_show_case} 조립 근거).
     *
     * <p>{@code runRiderId} 는 호출부가 {@code RunRiderRepository.findByIdAndRunIdAndStatusNot} 으로
     * 이미 학원 범위로 확인한 탑승자의 식별자라는 전제다.
     */
    @AcademyScopeExempt(reason = "runRiderId 는 호출부가 RunRiderRepository.findByIdAndRunIdAndStatusNot 으로 "
            + "이미 학원 범위로 확인한 탑승자의 식별자라는 전제다")
    Optional<NoShowCase> findByRunRiderId(Long runRiderId);
}
