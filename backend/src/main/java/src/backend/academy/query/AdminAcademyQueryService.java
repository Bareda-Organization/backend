package src.backend.academy.query;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.dto.AcademyDetailResponse;
import src.backend.academy.dto.AcademyListRequest;
import src.backend.academy.dto.AcademyListResponse;
import src.backend.academy.dto.AcademyStaffAccountResponse;
import src.backend.academy.dto.AcademyStatsResponse;
import src.backend.academy.entity.Academy;
import src.backend.academy.entity.AcademyStaff;
import src.backend.academy.entity.AcademyStatus;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.LikeEscape;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;
import src.backend.run.domain.MovingRunWindowPolicy;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 메인 관리자 콘솔의 학원 목록·상세 조회(ACAD-01·03, API_SPEC §6.1·§6.3).
 *
 * <p>학원 격리의 예외 구역이다(§1.5) — {@code /admin/**} 는 전 학원 범위이고, 어느 학원인지는 토큰이
 * 아니라 경로 파라미터가 정한다. 그래서 이 서비스에는 호출자의 소속 학원을 보는 자리가 부재하며,
 * 전 학원 범위를 허용하는 판정은 컨트롤러의 {@code @Can(Permissions.ACADEMY_MANAGE)} 하나로 끝난다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAcademyQueryService {

    /**
     * {@code sort} 가 받는 필드(API_SPEC §1.8) — 왼쪽이 API 이름, 오른쪽이 엔티티 속성이다.
     *
     * <p>목록을 손으로 적는 이유는 요청 문자열을 그대로 정렬 속성으로 넘기면 없는 이름 하나가
     * {@code 500} 이 되고, 그 예외 문구가 엔티티 필드 목록을 밖으로 실어 나르기 때문이다.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "name", "name", "region", "region", "code", "code",
            "status", "status", "created_at", "createdAt");

    /** 정렬을 지정하지 않았을 때의 순서 — 사람이 목록에서 학원을 찾는 화면이라 이름 오름차순이다. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    /**
     * 어떤 정렬에도 마지막으로 붙는 결정적 순서.
     *
     * <p>같은 값이 여럿인 컬럼(이름·지역·상태)으로 정렬하면 동점 행의 순서를 DB 가 정한다 — 그러면
     * 페이지를 넘길 때 같은 행이 두 번 나오거나 한 번도 안 나오는 일이 생기고, 사용자 눈에는 목록이
     * 호출마다 바뀌는 버그로 보인다.
     */
    private static final Sort TIE_BREAKER = Sort.by(Sort.Direction.ASC, "id");

    private final AcademyRepository academyRepository;

    private final AcademyStaffRepository academyStaffRepository;

    private final AccountRepository accountRepository;

    private final RunRepository runRepository;

    private final AcademyCountAssembler counts;

    /** 운행 중 차량 수가 세는 운행일 범위(K-1) — 근접 판정·유실 집계와 같은 경계를 쓴다. */
    private final MovingRunWindowPolicy movingRunWindowPolicy;

    /**
     * 학원 목록·검색(API_SPEC §6.1) — 검색어는 학원명·코드 부분일치, 상태는 선택 필터다. 응답 최상위 {@code summary} 는
     * 이 필터·쪽과 무관한 전체 값이라(Ruling 806) 결과가 비어도 항상 채운다.
     */
    public AcademyListResponse list(AcademyListRequest request) {
        PageParams pageParams = PageParams.of(request.page(), request.size());
        Sort sort = SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT).and(TIE_BREAKER);
        Page<Academy> page = academyRepository.searchForConsole(
                LikeEscape.escape(request.q() == null ? "" : request.q()),
                statusFilter(request.status()),
                pageParams.toPageable(sort));

        return AcademyListResponse.of(PageResponse.of(page, counts.summaries(page.getContent())), counts.summary());
    }

    /** 학원 상세(API_SPEC §6.3 GET) — 미등록 학원은 {@code 404 ACADEMY_NOT_FOUND} 다. */
    public AcademyDetailResponse detail(Long academyId) {
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        long staffCount = academyStaffRepository.countByAcademyIdAndStatus(academyId, StaffStatus.ACTIVE);
        long userCount = counts.userCount(academyId);
        List<String> movingBusNos = runRepository.findBusNosByStatusFromServiceDate(academyId, RunStatus.MOVING,
                movingRunWindowPolicy.earliestServiceDate());
        long pendingSignupCount = counts.pendingSignupCount(academyId);
        return AcademyDetailResponse.from(academy, staffCount, userCount, pendingSignupCount,
                staffAccounts(academyId), AcademyStatsResponse.of(movingBusNos));
    }

    /**
     * 관계자 목록에 계정 정보를 채운다 — {@code academy_staff} 는 재직 상태만 들고 이름·연락처는
     * {@code account} 가 보유한다(ERD §3.1).
     *
     * <p>계정을 못 찾은 행은 응답에서 빠진다 — {@code academy_staff.account_id} 가 NOT NULL FK 라
     * 정상 상태에서는 생길 수 없는 경우이고, 그 한 행 때문에 상세 화면 전체가 실패하는 것보다 낫다.
     */
    private List<AcademyStaffAccountResponse> staffAccounts(Long academyId) {
        List<AcademyStaff> staffRows = academyStaffRepository.findAllByAcademyIdOrderByIdAsc(academyId);
        if (staffRows.isEmpty()) {
            return List.of();
        }
        Map<Long, Account> accounts = accountRepository
                .findAllByAcademyIdAndIdIn(academyId, staffRows.stream().map(AcademyStaff::getAccountId).toList())
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        return staffRows.stream()
                .filter(staff -> accounts.containsKey(staff.getAccountId()))
                .map(staff -> AcademyStaffAccountResponse.from(staff, accounts.get(staff.getAccountId())))
                .toList();
    }

    /**
     * 상태 필터를 상태 <b>집합</b>으로 바꾼다 — 필터가 없으면 전체 상태를 넘겨 "필터 없음" 을 조건절이
     * 아니라 인자로 표현한다. 사양에 없는 값은 조용히 무시하지 않고 {@code 422} 로 거부한다.
     */
    private Collection<AcademyStatus> statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return List.of(AcademyStatus.values());
        }
        try {
            return List.of(AcademyStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
