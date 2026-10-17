package src.backend.exception.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;

import src.backend.exception.entity.NoShowContact;

/**
 * {@link NoShowContact} 영속성 접근 — {@code no_show_contact} 는 {@code no_show_case}
 * 를 부모로 두는 부모 경유 자원이다(ERD §6.1). 쓰기({@code save})만 쓴다 — 저장할 케이스는 호출부
 * ({@code NoShowContactCommandService})가 이미 학원 범위로 확인한 것이다.
 */
public interface NoShowContactRepository extends JpaRepository<NoShowContact, Long> {

    /**
     * {@code client_key} 로 재전송을 가려낸다({@code uk_no_show_contact_client_key}, API_SPEC §1.7) —
     * {@link src.backend.boarding.command.BoardingCommandService} 와 같은 멱등성 재생 형태다.
     */
    @AcademyScopeExempt(reason = "client_key 는 클라이언트가 생성한 UUID 라 그 자체로 전 학원에서 유일하고 추측 불가능하다"
            + "(EmergencyAlertRepository#findByClientKey 와 같은 근거). NoShowContactCommandService#recordAttempt 가 배치·학원 범위 "
            + "회차 확인 뒤에 이 조회를 하고, 찾은 행의 탑승자·시도 종류·결과·판단을 요청과 대조한다")
    Optional<NoShowContact> findByClientKey(UUID clientKey);

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
