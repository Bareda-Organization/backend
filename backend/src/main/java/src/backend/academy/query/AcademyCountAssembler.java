package src.backend.academy.query;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyListResponse;
import src.backend.academy.dto.AcademySummaryResponse;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStatus;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequestStatus;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.persistence.AcademyCount;

/** 메인 관리자 학원 조회가 쓰는 집계 — 관계자 수·사용자 수·대기 가입 요청 수를 학원 단위로 모아 목록 행과 {@code summary} 를 만든다. */
@Component
@RequiredArgsConstructor
class AcademyCountAssembler {

    /**
     * {@code user_count} 가 세는 역할(API_SPEC §6.1 "학부모·학생·매니저 합계") — 매니저는 기사·동승자
     * 두 역할로 나뉘어 있어 행 4개가 된다. 관계자와 메인 관리자는 여기 없다 — 관계자는 {@code staff_count}
     * 가 따로 세고, 메인 관리자는 학원 소속이 부재하다.
     */
    private static final Collection<Role> MEMBER_ROLES = List.of(Role.PARENT, Role.STUDENT, Role.DRIVER, Role.ESCORT);

    /**
     * {@code user_count} 가 세는 계정 상태(Ruling 142) — <b>소속이 확정됐고 아직 종료되지 않은 것</b>.
     *
     * <p>{@code staff_count}(재직자만 셈)와 같은 원칙으로 묶은 결과다. 사양이 상태 필터를 명시하지
     * 않아 판정 기준을 세웠고, 두 필드가 서로 다른 테이블의 서로 다른 상태값을 쓰므로 값 이름이 아니라
     * 원칙으로 맞춘다.
     *
     * <p>제외 — {@code pending}(승인 전이라 소속 미확정) · {@code rejected}(소속된 적 부재).
     * <b>{@code blocked} 는 포함</b>한다 — 로그인 차단은 소속 상태에서의 일시 정지이지 소속 종료가
     * 아니다. 빼면 계정을 하나 차단할 때마다 학원 규모가 줄어드는 것으로 보여, 그 값을 보고 판단하는
     * 메인 관리자가 오도된다.
     */
    private static final Collection<AccountStatus> MEMBER_STATUSES =
            List.of(AccountStatus.ACTIVE, AccountStatus.BLOCKED);

    private final AcademyRepository academyRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final AccountRepository accountRepository;

    private final SignupRequestRepository signupRequestRepository;

    /** 한 페이지의 학원 전부에 관계자 수·사용자 수·대기 가입 요청 수를 한 번에 채운다 — 학원마다 세면 질의가 페이지 크기만큼 늘어난다. */
    List<AcademySummaryResponse> summaries(List<Academy> academies) {
        List<Long> academyIds = academies.stream().map(Academy::getId).toList();
        if (academyIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> staffCounts = countByAcademy(academyStaffRepository
                .countByAcademyIdInGroupedByAcademyId(academyIds, StaffStatus.ACTIVE));
        Map<Long, Long> userCounts = countByAcademy(accountRepository
                .countByAcademyIdInGroupedByAcademyId(academyIds, MEMBER_ROLES, MEMBER_STATUSES));
        Map<Long, Long> pendingCounts = countByAcademy(signupRequestRepository
                .countByAcademyIdInGroupedByAcademyId(academyIds, ApproverType.SYSTEM_ADMIN,
                        SignupRequestStatus.PENDING));
        return academies.stream()
                .map(academy -> AcademySummaryResponse.from(academy,
                        staffCounts.getOrDefault(academy.getId(), 0L),
                        userCounts.getOrDefault(academy.getId(), 0L),
                        pendingCounts.getOrDefault(academy.getId(), 0L)))
                .toList();
    }

    /** 목록 {@code summary} — 필터·쪽과 무관한 전 학원 값이다(Ruling 806). 사용자 수는 {@code user_count} 와 같은 역할·상태 기준이다. */
    AcademyListResponse.Summary summary() {
        long active = academyRepository.countByStatus(AcademyStatus.ACTIVE);
        long inactive = academyRepository.countByStatus(AcademyStatus.INACTIVE);
        return new AcademyListResponse.Summary(active + inactive, active, inactive,
                accountRepository.countByRoleInAndStatusIn(MEMBER_ROLES, MEMBER_STATUSES));
    }

    /** 한 학원의 {@code user_count} — {@link #MEMBER_ROLES} · {@link #MEMBER_STATUSES} 기준이다. */
    long userCount(Long academyId) {
        return accountRepository
                .countByAcademyIdInGroupedByAcademyId(List.of(academyId), MEMBER_ROLES, MEMBER_STATUSES).stream()
                .mapToLong(AcademyCount::getTotal)
                .sum();
    }

    /** 한 학원의 대기 중 가입 요청 수 — 승인 주체가 메인 관리자인 건만 센다. */
    long pendingSignupCount(Long academyId) {
        return countByAcademy(signupRequestRepository.countByAcademyIdInGroupedByAcademyId(
                List.of(academyId), ApproverType.SYSTEM_ADMIN, SignupRequestStatus.PENDING))
                .getOrDefault(academyId, 0L);
    }

    private Map<Long, Long> countByAcademy(List<AcademyCount> rows) {
        return rows.stream().collect(Collectors.toMap(AcademyCount::getAcademyId, AcademyCount::getTotal));
    }
}
