package src.backend.academy.query;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.StaffAccountListRequest;
import src.backend.academy.dto.StaffAccountListResponse;
import src.backend.academy.dto.StaffAccountSummaryResponse;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.academy.repository.StaffStatusCount;
import src.backend.account.entity.Account;
import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequestStatus;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.AcademyCount;
import src.backend.global.persistence.LikeEscape;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;

/**
 * 관계자 계정 목록 조회(ACAD-06 · O-02, API_SPEC §6.6).
 *
 * <p>학원 격리의 예외 구역이다(§1.5) — {@code /admin/**} 는 전 학원 범위이고, 응답이 어느 학원
 * 관계자인지({@code academy_name})를 드러내는 것이 이 화면의 요건이다. 예외를 여는 판정은 컨트롤러의
 * {@code @CanManageStaffAccount} 한 곳이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminStaffAccountQueryService {

    /**
     * {@code sort} 가 받는 필드(API_SPEC §1.8) — 왼쪽이 API 이름, 오른쪽이 {@code Account} 속성이다.
     *
     * <p>재직 상태({@code status})와 학원 이름({@code academy_name})은 여기 없다. 조회의 뿌리가
     * {@code Account} 라 그 둘은 다른 테이블의 값이고, 이름만 목록에 넣으면 요청은 받아 놓고 정렬은
     * 되지 않는 파라미터가 생긴다.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "name", "name", "login_id", "loginId", "last_login_at", "lastLoginAt", "created_at", "createdAt");

    /** 정렬 기본값 — 사람이 목록에서 관계자를 찾는 화면이라 이름 오름차순이다. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    /**
     * 어떤 정렬에도 마지막으로 붙는 결정적 순서 — 동명이인이나 {@code last_login_at} 이 비어 있는
     * 계정들처럼 값이 겹치면 동점 행 순서를 DB 가 정해, 페이지를 넘길 때 같은 행이 두 번 나오거나
     * 한 번도 안 나온다.
     */
    private static final Sort TIE_BREAKER = Sort.by(Sort.Direction.ASC, "id");

    private final SignupRequestRepository signupRequestRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final AcademyRepository academyRepository;

    /**
     * 관계자 계정 목록(§6.6) — {@code academy_staff} 행을 가진 계정만 실린다. {@code academy_id}·{@code q}·{@code status} 로 거르고
     * (Ruling 807), 응답 {@code counts} 는 <b>{@code status} 만 뺀</b> 같은 조건의 재직·퇴사 건수다.
     *
     * <p>계정 → 관계자 행 → 학원 이름 → 학원별 대기 가입 요청 수 순으로 한 페이지 전부를 한 번에 묶어 찾으므로 행 수와 무관하게
     * 질의 수가 일정하다 — 계정마다 찾으면 한 페이지(최대 100건)가 질의 수백 건이 된다.
     */
    public StaffAccountListResponse list(StaffAccountListRequest request) {
        requireRegistered(request.academyId());
        Collection<StaffStatus> statuses = statusFilter(request.status());
        String q = LikeEscape.escape(request.q() == null ? "" : request.q());
        Sort sort = SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT).and(TIE_BREAKER);
        Page<Account> page = academyStaffRepository.findStaffAccountsForConsole(request.academyId(), q, statuses,
                PageParams.of(request.page(), request.size()).toPageable(sort));

        return StaffAccountListResponse.of(PageResponse.of(page, summaries(page.getContent())),
                counts(request.academyId(), q));
    }

    /** {@code academy_id} 필터가 미등록 학원을 가리키면 404 다(§6.6 에러 표) — 필터를 안 주면 전 학원이라 검사할 것이 없다. */
    private void requireRegistered(Long academyId) {
        if (academyId != null && !academyRepository.existsById(academyId)) {
            throw new BusinessException(ErrorCode.ACADEMY_NOT_FOUND);
        }
    }

    private List<StaffAccountSummaryResponse> summaries(List<Account> accounts) {
        List<Long> accountIds = accounts.stream().map(Account::getId).toList();
        if (accountIds.isEmpty()) {
            return List.of();
        }
        Map<Long, AcademyStaff> staffRows = academyStaffRepository.findAllByAccountIdIn(accountIds).stream()
                .collect(Collectors.toMap(AcademyStaff::getAccountId, staff -> staff, (first, second) -> first));
        Map<Long, String> academyNames = academyNamesOf(staffRows.values());
        Map<Long, Long> pendingCounts = signupRequestRepository
                .countByAcademyIdInGroupedByAcademyId(academyNames.keySet(), ApproverType.SYSTEM_ADMIN,
                        SignupRequestStatus.PENDING).stream()
                .collect(Collectors.toMap(AcademyCount::getAcademyId, AcademyCount::getTotal));

        return accounts.stream()
                .filter(account -> staffRows.containsKey(account.getId()))
                .map(account -> toSummary(account, staffRows.get(account.getId()), academyNames, pendingCounts))
                .toList();
    }

    private StaffAccountSummaryResponse toSummary(Account account, AcademyStaff staff, Map<Long, String> names,
            Map<Long, Long> pendingCounts) {
        return StaffAccountSummaryResponse.from(account, staff.getAcademyId(), names.get(staff.getAcademyId()),
                staff.getStatus(), pendingCounts.getOrDefault(staff.getAcademyId(), 0L));
    }

    /** 탭 건수 — 상태 필터는 빼고 학원·검색어 조건만 건다(Ruling 807). 건수가 없는 상태는 0 이다. */
    private StaffAccountListResponse.Counts counts(Long academyId, String q) {
        Map<StaffStatus, Long> byStatus = academyStaffRepository.countStaffAccountsByStatusForConsole(academyId, q)
                .stream().collect(Collectors.toMap(StaffStatusCount::getStatus, StaffStatusCount::getTotal));
        return new StaffAccountListResponse.Counts(byStatus.getOrDefault(StaffStatus.ACTIVE, 0L),
                byStatus.getOrDefault(StaffStatus.INACTIVE, 0L));
    }

    /**
     * 상태 필터를 상태 <b>집합</b>으로 바꾼다 — 없으면 전체 상태를 넘겨 "필터 없음" 을 인자로 표현한다. 두 값 밖은 조용히 무시하지
     * 않고 {@code 422} 로 거부한다.
     */
    private Collection<StaffStatus> statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return List.of(StaffStatus.values());
        }
        try {
            return List.of(StaffStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** 한 페이지에 등장하는 학원 이름을 한 번에 찾는다 — 관계자마다 찾으면 한 페이지가 질의 100건이 된다. */
    private Map<Long, String> academyNamesOf(Collection<AcademyStaff> staffRows) {
        List<Long> academyIds = staffRows.stream().map(AcademyStaff::getAcademyId).distinct().toList();
        if (academyIds.isEmpty()) {
            return Map.of();
        }
        return academyRepository.findAllById(academyIds).stream()
                .collect(Collectors.toMap(Academy::getId, Academy::getName, (first, second) -> first));
    }
}
