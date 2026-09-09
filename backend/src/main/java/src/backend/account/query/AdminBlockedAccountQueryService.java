package src.backend.account.query;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.dto.AdminAccountListRequest;
import src.backend.account.dto.BlockedAccountResponse;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;

/**
 * 차단 계정 목록 조회(AUTH-06 · O-03, API_SPEC §6.10).
 *
 * <p>학원 격리의 예외 구역이다(§1.5) — 차단은 계정 단위 조치라 전 학원 범위로 본다. 예외를 여는
 * 판정은 컨트롤러의 {@code @CanUnblockAccount} 한 곳이고, 그래서 이 서비스에는 호출자의 소속 학원을
 * 보는 자리가 부재하다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminBlockedAccountQueryService {

    /**
     * {@code sort} 가 받는 필드(API_SPEC §1.8) — 왼쪽이 API 이름, 오른쪽이 엔티티 속성이다.
     *
     * <p>목록을 손으로 적는 이유는 요청 문자열을 그대로 정렬 속성으로 넘기면 없는 이름 하나가
     * {@code 500} 이 되고, 그 예외 문구가 엔티티 필드 목록(그중에 {@code password_hash} 가 있다)을
     * 밖으로 실어 나르기 때문이다.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "blocked_at", "blockedAt", "login_id", "loginId", "name", "name",
            "failed_attempts", "failedAttempts");

    /** 정렬 기본값 — 최근 차단이 위로 온다. 운영자가 이 화면에서 먼저 처리할 것이 방금 막힌 계정이다. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "blockedAt");

    /**
     * 어떤 정렬에도 마지막으로 붙는 결정적 순서 — 값이 겹치는 컬럼({@code blocked_at} 은 배치 차단이면
     * 같은 시각이 여럿이다)으로만 정렬하면 동점 행의 순서를 DB 가 정해, 페이지를 넘길 때 같은 행이
     * 두 번 나오거나 한 번도 안 나온다.
     */
    private static final Sort TIE_BREAKER = Sort.by(Sort.Direction.ASC, "id");

    private final AccountRepository accountRepository;

    private final AcademyRepository academyRepository;

    /** 차단 계정 목록(§6.10) — 차단 계정이 0건이면 빈 {@code items[]} 다. */
    public PageResponse<BlockedAccountResponse> list(AdminAccountListRequest request) {
        PageParams pageParams = PageParams.of(request.page(), request.size());
        Sort sort = SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT).and(TIE_BREAKER);
        Page<Account> page = accountRepository.findAllByStatus(AccountStatus.BLOCKED, pageParams.toPageable(sort));

        Map<Long, String> academyNames = academyNamesOf(page.getContent());
        return PageResponse.of(page, page.getContent().stream()
                .map(account -> BlockedAccountResponse.from(account, academyNames.get(account.getAcademyId())))
                .toList());
    }

    /**
     * 한 페이지에 등장하는 학원 이름을 한 번에 찾는다 — 계정마다 찾으면 한 페이지(최대 100건)가 질의 100건이 된다.
     *
     * <p>{@code academyId} 가 {@code null} 인 계정({@code system_admin})은 조회 대상에서 빠지고, 그 계정의
     * {@code academy_name} 은 {@code null} 로 남는다 — 학원 소속이 부재한 것이 정상 상태다.
     */
    private Map<Long, String> academyNamesOf(List<Account> accounts) {
        List<Long> academyIds = accounts.stream()
                .map(Account::getAcademyId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (academyIds.isEmpty()) {
            return Map.of();
        }
        return academyRepository.findAllById(academyIds).stream()
                .collect(Collectors.toMap(Academy::getId, Academy::getName, (first, second) -> first));
    }
}
