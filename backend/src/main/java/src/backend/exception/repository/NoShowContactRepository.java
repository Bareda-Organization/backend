package src.backend.exception.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;

import src.backend.exception.entity.NoShowContact;

/**
 * {@link NoShowContact} 영속성 접근(Phase 11 목표 3) — {@code no_show_contact} 는 {@code no_show_case}
 * 를 부모로 두는 부모 경유 자원이다(ERD §6.1). 쓰기({@code save})만 쓴다 — 저장할 케이스는 호출부
 * ({@code NoShowContactCommandService})가 이미 학원 범위로 확인한 것이다.
 */
public interface NoShowContactRepository extends JpaRepository<NoShowContact, Long> {

    /**
     * 케이스들의 연락 시도를 <b>시각순</b>(같은 시각은 기록 순)으로 읽는다(§4.2 {@code no_show_case.contacts[]}, Ruling 823) — 저장 순서가
     * 아니라 시도 시각 기준이다.
     *
     * <p>{@code caseIds} 는 호출부가 이미 학원 범위로 확인한 케이스({@code NoShowCaseRepository#findOpenByRunIdAndAcademyId} ·
     * {@code NoShowCaseAccess})의 식별자라는 전제다 — {@code NoShowCaseRepository#findByRunRiderId} 와 같은 근거.
     */
    @AcademyScopeExempt(reason = "caseIds 는 호출부가 NoShowCaseRepository#findOpenByRunIdAndAcademyId · NoShowCaseAccess 로 이미 "
            + "학원 범위에 좁혀 확인한 미승차 케이스의 식별자라는 전제다 — NoShowCaseRepository#findByRunRiderId 와 같은 근거")
    List<NoShowContact> findAllByNoShowCaseIdInOrderByAttemptedAtAscIdAsc(Collection<Long> caseIds);
}
