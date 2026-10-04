package src.backend.audit.query;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.dto.AuditLogItemResponse;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.PageParams;
import src.backend.global.response.PageResponse;

/**
 * 메인 관리자 콘솔의 개인정보 조회·수정 이력 조회(SYS-01, API_SPEC §6.13 {@code GET /admin/audit-logs},
 * Phase 14 T1 목표 3).
 *
 * <p>{@code sort} 는 클라이언트가 고르지 않는다 — §6.13 이 쿼리 파라미터로 나열한 것은
 * {@code academy_id}·{@code account_id}·{@code from}·{@code to}·페이징뿐이다. 최신순 고정에
 * {@code id} 결정적 타이브레이커를 붙인다({@code StudentQueryService}·{@code AdminBlockedAccountQueryService}
 * 와 같은 근거 — 같은 {@code occurred_at} 이 여럿이면 페이지를 넘길 때 행이 겹치거나 빠진다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditLogQueryService {

    /** 조회·수정 이력 화면이 고를 수 있는 동작 — 로그인·차단 동작은 접속 이력(§6.13 login-history)의 몫이다. */
    private static final List<AuditAction> DATA_ACCESS_ACTIONS =
            List.of(AuditAction.READ, AuditAction.UPDATE, AuditAction.DELETE);

    private static final Sort ORDER = Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final AuditLogRepository auditLogRepository;

    private final AcademyRepository academyRepository;

    private final AccountRepository accountRepository;

    private final Clock clock;

    /**
     * 개인정보 조회·수정 이력을 최신순(동률은 id 오름차순)으로 페이징해 돌려준다(§6.13).
     *
     * @param action {@code read}·{@code update}·{@code delete} 중 하나만 보고 싶을 때(R46 감사 화면, Ruling 446).
     *               {@code null} 이면 셋 다다. 그 밖의 값은 {@code 422 VALIDATION_FAILED}
     */
    public PageResponse<AuditLogItemResponse> list(AuditQueryFilter filter, String action) {
        validateFilters(filter.academyId(), filter.accountId());
        AuditQueryRange range = AuditQueryRange.of(filter.from(), filter.to(), OffsetDateTime.now(clock));
        Page<AuditLog> result = auditLogRepository.search(AuditCategory.DATA_ACCESS, actionsOf(action),
                filter.academyId(), filter.accountId(), range.from(), range.to(),
                PageParams.of(filter.page(), filter.size()).toPageable(ORDER));

        Map<Long, String> academyNames = academyNamesOf(result.getContent());
        ActorNames actorNames = actorNamesOf(result.getContent());
        return PageResponse.of(result, result.getContent().stream()
                .map(log -> toItem(log, academyNames.get(log.getAcademyId()), actorNames.of(log))).toList());
    }

    private static List<AuditAction> actionsOf(String action) {
        if (action == null) {
            return AuditLogRepository.ALL_ACTIONS;
        }
        return DATA_ACCESS_ACTIONS.stream().filter(candidate -> lower(candidate.name()).equals(action.trim()))
                .findFirst().map(List::of)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "action 은 read · update · delete 중 하나여야 합니다: " + action));
    }

    /** {@code academy_id}·{@code account_id} 필터가 미등록 대상을 가리키면 404 다(§6.13 에러 표). */
    private void validateFilters(Long academyId, Long accountId) {
        if (academyId != null && !academyRepository.existsById(academyId)) {
            throw new BusinessException(ErrorCode.ACADEMY_NOT_FOUND);
        }
        if (accountId != null && !accountRepository.existsById(accountId)) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
    }

    private AuditLogItemResponse toItem(AuditLog log, String academyName, String actorName) {
        return new AuditLogItemResponse(log.getActorLoginId(), actorName, lower(log.getAction().name()),
                detailActionOf(log), log.getTargetType(), log.getTargetId(), academyName, log.getIp(),
                log.getOccurredAt());
    }

    /** 감사 행 {@code detail.action} 원문(Ruling 260 — 구별 문자열은 detail 에 둔다). 없거나 문자열이 아니면 {@code null}. */
    private static String detailActionOf(AuditLog log) {
        Object action = log.getDetail() == null ? null : log.getDetail().get("action");
        return action instanceof String text ? text : null;
    }

    /**
     * 행위자 계정의 <b>현재</b> 이름을 한 페이지분 한 번에 모은다 — 행마다 찾으면 질의가 페이지 크기만큼 늘어난다. 감사 행에 계정 id 가
     * 있으면 그것으로, 없으면 로그인 아이디 스냅샷으로 찾는다(Ruling 809).
     */
    private ActorNames actorNamesOf(List<AuditLog> logs) {
        List<Long> ids = logs.stream().map(AuditLog::getActorAccountId).filter(Objects::nonNull).distinct().toList();
        List<String> loginIds = logs.stream().filter(log -> log.getActorAccountId() == null)
                .map(AuditLog::getActorLoginId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> byId = ids.isEmpty() ? Map.of() : accountRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Account::getId, Account::getName));
        Map<String, String> byLoginId = loginIds.isEmpty() ? Map.of() : accountRepository.findAllByLoginIdIn(loginIds)
                .stream().collect(Collectors.toMap(Account::getLoginId, Account::getName));
        return new ActorNames(byId, byLoginId);
    }

    /** 한 페이지의 행위자 이름 조회 결과 — 계정 id 로 찾은 것과 로그인 아이디로 찾은 것. */
    private record ActorNames(Map<Long, String> byId, Map<String, String> byLoginId) {

        /** 그 행의 행위자 현재 이름 — 계정 id 가 있으면 id 로만 보고(계정이 없으면 {@code null}), 없을 때만 로그인 아이디로 본다. */
        String of(AuditLog log) {
            if (log.getActorAccountId() != null) {
                return byId.get(log.getActorAccountId());
            }
            // Map.of() 는 null 키 조회가 NPE 라 로그인 아이디 스냅샷이 없는 행을 먼저 거른다
            return log.getActorLoginId() == null ? null : byLoginId.get(log.getActorLoginId());
        }
    }

    /**
     * 한 페이지분 학원명을 한 번에 모은다 — 행마다 조회하면 페이지 크기만큼 질의가 늘어난다
     * ({@code AdminBlockedAccountQueryService} 와 같은 근거).
     *
     * <p>{@code Map.of()} 대신 빈 {@link HashMap} 을 돌려준다 — R1 재판정, 프로덕션 도달 불가,
     * 방어 목적. {@code academy_id} 가 {@code null} 인 행이 페이지에 섞이면 호출부가 그 키로
     * {@code get(null)} 을 하는데, {@code Map.of()} 는 {@code null} 키 조회 자체를 거부해 NPE 를
     * 던진다({@link java.util.HashMap} 은 {@code null} 키를 그대로 받아 {@code null} 을 돌려준다).
     * 지금의 {@code data_access} 적재 지점(L3 조회 · 강제 확정 · 비밀번호 초기화)은 전부 {@code academyId} 를
     * 채우므로 실제로 닿지는 않지만(강제 확정은 BR-129 전까지 비워 실제로 닿았다), 그 전제가 깨지는 순간을 대비해 남겨 둔다.
     */
    private Map<Long, String> academyNamesOf(List<AuditLog> logs) {
        List<Long> academyIds = logs.stream().map(AuditLog::getAcademyId).filter(id -> id != null).distinct().toList();
        if (academyIds.isEmpty()) {
            return new HashMap<>();
        }
        return academyRepository.findAllById(academyIds).stream()
                .collect(Collectors.toMap(Academy::getId, Academy::getName, (first, second) -> first));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
