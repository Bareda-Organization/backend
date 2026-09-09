package src.backend.academy.query;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.StaffAccountSummaryResponse;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.dto.AdminAccountListRequest;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
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

    private final AccountRepository accountRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final AcademyRepository academyRepository;

    /**
     * 관계자 계정 목록(§6.6) — {@code academy_staff} 행을 가진 계정만 실린다.
     *
     * <p>계정 → 관계자 행 → 학원 순으로 <b>세 번</b> 조회한다. 한 페이지 전부를 한 번에 묶어 찾으므로
     * 행 수와 무관하게 질의는 3건이다 — 계정마다 찾으면 한 페이지(최대 100건)가 201건이 된다.
     */
    public PageResponse<StaffAccountSummaryResponse> list(AdminAccountListRequest request) {
        PageParams pageParams = PageParams.of(request.page(), request.size());
        Sort sort = SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT).and(TIE_BREAKER);
        Page<Account> page = accountRepository.findStaffAccountsForConsole(pageParams.toPageable(sort));

        List<Long> accountIds = page.getContent().stream().map(Account::getId).toList();
        if (accountIds.isEmpty()) {
            return PageResponse.of(page, List.of());
        }
        Map<Long, AcademyStaff> staffRows = academyStaffRepository.findAllByAccountIdIn(accountIds).stream()
                .collect(Collectors.toMap(AcademyStaff::getAccountId, staff -> staff, (first, second) -> first));
        Map<Long, String> academyNames = academyNamesOf(staffRows.values());

        return PageResponse.of(page, page.getContent().stream()
                .filter(account -> staffRows.containsKey(account.getId()))
                .map(account -> toSummary(account, staffRows.get(account.getId()), academyNames))
                .toList());
    }

    private StaffAccountSummaryResponse toSummary(Account account, AcademyStaff staff, Map<Long, String> names) {
        return StaffAccountSummaryResponse.from(account, names.get(staff.getAcademyId()), staff.getStatus());
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
