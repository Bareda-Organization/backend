package src.backend.account.query;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.dto.SignupRequestListRequest;
import src.backend.account.dto.SignupRequestListResponse;
import src.backend.account.dto.SignupRequestSummaryResponse;
import src.backend.account.dto.StaffSignupRequestSummaryResponse;
import src.backend.account.entity.ApproverType;
import src.backend.account.entity.SignupRequest;
import src.backend.account.entity.SignupRequestStatus;
import src.backend.account.repository.SignupRequestRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;

/**
 * 가입 요청 승인 큐 조회(API_SPEC §5.1 관계자 · §6.4 메인 관리자).
 *
 * <p>두 축을 한 서비스에 둔 것은 <b>같은 큐를 다른 창으로 보기 때문</b>이다 — 정렬 축·기본값·미처리
 * 배지의 의미가 같고, 바뀌는 계기도 "승인 큐 조회 계약이 바뀔 때" 하나다. 갈리는 것(무엇을 싣는가 ·
 * 어디까지 보는가)은 두 메서드가 각자 들고 있고 서로 부르지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignupRequestQueryService {

    /**
     * {@code sort} 가 받는 필드(§1.8) — 신청 일시 하나다.
     *
     * <p>이름·연락처는 {@code account} 가 보유해 이 조회의 정렬 축이 될 수 없다 — 넣으면 요청 행을
     * 정렬할 수 없어 전건을 읽어 메모리에서 줄 세우게 되고, 그 순간 페이징이 뜻을 잃는다.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of("requested_at", "requestedAt");

    /** 승인 큐라 <b>오래 기다린 건이 먼저</b>다 — 최신순이면 밀린 요청이 목록 뒤로 계속 밀려난다. */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "requestedAt");

    /**
     * 어떤 정렬에도 마지막으로 붙는 결정적 순서 — 같은 초에 들어온 두 요청의 순서를 DB 가 정하면
     * 페이지를 넘길 때 같은 건이 두 번 나오거나 한 번도 안 나온다.
     */
    private static final Sort TIE_BREAKER = Sort.by(Sort.Direction.ASC, "id");

    /** {@code role} 쿼리가 받는 신청 역할(§5.1) — 승인 주체가 관계자인 4종이다. */
    private static final Set<Role> FILTERABLE_ROLES = EnumSet.of(Role.PARENT, Role.STUDENT, Role.DRIVER, Role.ESCORT);

    private final SignupRequestRepository signupRequestRepository;

    private final SignupRequestRowAssembler rows;

    /**
     * 관계자가 보는 가입 요청 목록(§5.1) — 소속 학원 · 승인 주체가 관계자인 건으로 좁힌다.
     *
     * <p>{@code role=staff} 요청을 빼는 것이 이 메서드의 핵심이다. 빼지 않으면 관계자가 자기 후임
     * 요청을 보게 되고, {@code decide} 만 403 이라 화면에 처리할 수 없는 항목이 영영 쌓인다.
     *
     * <p>{@code role} 쿼리는 목록과 {@code total_count} 만 거른다 — {@code pending_count} 는 학원 전체 대기 건수 그대로다
     * (Ruling 846).
     */
    public SignupRequestListResponse forStaff(AuthUser requester, SignupRequestListRequest request) {
        Long academyId = AcademyScope.resolveListScope(requester, null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        SignupRequestStatus status = statusFilter(request.status());
        Set<Role> roles = roleFilter(request.role());
        Page<SignupRequest> page = roles.isEmpty()
                ? signupRequestRepository.findAllByAcademyIdAndApproverTypeAndStatus(academyId, ApproverType.STAFF,
                        status, pageable(request))
                : signupRequestRepository.findAllByAcademyIdAndApproverTypeAndStatusAndRequestedRoleIn(academyId,
                        ApproverType.STAFF, status, roles, pageable(request));

        PageResponse<SignupRequestSummaryResponse> items = rows.staffItems(page, academyId);
        return SignupRequestListResponse.of(items, signupRequestRepository
                .countByAcademyIdAndApproverTypeAndStatus(academyId, ApproverType.STAFF,
                        SignupRequestStatus.PENDING));
    }

    /**
     * 메인 관리자가 보는 관계자 가입 요청 목록(§6.4) — 전 학원 범위다(§1.5 예외).
     *
     * <p>학원과 재직 관계자 수를 <b>한 페이지분 한 번에</b> 모은다 — 요청마다 조회하면 한 페이지(최대
     * 100건)가 질의 200건이 된다.
     */
    public PageResponse<StaffSignupRequestSummaryResponse> forAdmin(SignupRequestListRequest request) {
        Page<SignupRequest> page = signupRequestRepository.findAllByApproverTypeAndStatus(
                ApproverType.SYSTEM_ADMIN, statusFilter(request.status()), pageable(request));
        return rows.adminItems(page);
    }

    private Pageable pageable(SignupRequestListRequest request) {
        return PageParams.of(request.page(), request.size())
                .toPageable(SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT).and(TIE_BREAKER));
    }

    /**
     * {@code role} 쿼리를 신청 역할 집합으로 옮긴다(§5.1) — 안 주면(공백 포함) 빈 집합이고 거르지 않는다.
     * 사양 밖 값은 {@code staff}(이 목록의 대상 밖)까지 포함해 조용히 무시하지 않고 422 로 거부한다.
     */
    private Set<Role> roleFilter(List<String> roles) {
        if (roles == null) {
            return Set.of();
        }
        Set<Role> filter = EnumSet.noneOf(Role.class);
        for (String value : roles) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Role role = parseRole(value);
            if (!FILTERABLE_ROLES.contains(role)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            filter.add(role);
        }
        return filter;
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** 값을 주지 않으면 {@code pending} 이다(§5.1) — 사양에 없는 값은 조용히 무시하지 않고 422 로 거부한다. */
    private SignupRequestStatus statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return SignupRequestStatus.PENDING;
        }
        try {
            return SignupRequestStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
