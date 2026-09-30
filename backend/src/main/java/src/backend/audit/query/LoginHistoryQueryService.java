package src.backend.audit.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.dto.LoginHistoryItemResponse;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.PageParams;
import src.backend.global.response.PageResponse;

/**
 * 메인 관리자 콘솔의 로그인·차단 이력 조회(SYS-02, API_SPEC §6.13
 * {@code GET /admin/login-history}, Phase 14 T1 목표 4).
 *
 * <p>{@code result}·{@code block_event} 를 저장된 {@code action} 에서 조회 시점에 다시 계산한다
 * (ERD §3.4 · 조율자 Ruling 13) — {@code AuditLog.blockEvent} 컬럼값을 그대로 옮기지 않는 이유는
 * {@link LoginHistoryItemResponse} 자바독에 적었다.
 *
 * <p><b>{@code unblock} 행의 {@code account_id}·{@code login_id} 는 해제된 계정이다</b>(BR-219) — 나머지 행은
 * 시도한 계정(행위자)이다. 해제 행의 행위자(해제한 관리자)는 {@code AuditLog.actorAccountId} 에 남아 있고 이 응답은 싣지 않는다.
 * {@code account_id} 필터도 같은 뜻을 따른다({@link AuditLogRepository#searchLoginHistory}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoginHistoryQueryService {

    private static final Sort ORDER = Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final AuditLogRepository auditLogRepository;

    private final AcademyRepository academyRepository;

    private final AccountRepository accountRepository;

    /** 로그인 성공·실패·차단 이력을 최신순(동률은 id 오름차순)으로 페이징해 돌려준다(§6.13). */
    public PageResponse<LoginHistoryItemResponse> list(AuditQueryFilter filter) {
        validateFilters(filter.academyId(), filter.accountId());
        Page<AuditLog> result = auditLogRepository.searchLoginHistory(filter.academyId(), filter.accountId(),
                AuditQueryRange.from(filter.from()), AuditQueryRange.to(filter.to()),
                PageParams.of(filter.page(), filter.size()).toPageable(ORDER));
        Map<Long, String> unblockedLoginIds = loginIdsOfUnblockedAccounts(result.getContent());

        return PageResponse.of(result,
                result.getContent().stream().map(log -> toItem(log, unblockedLoginIds)).toList());
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

    /** 해제 행이 가리키는 계정들의 로그인 아이디를 한 번에 읽는다 — 행마다 읽으면 페이지 크기만큼 질의가 는다. */
    private Map<Long, String> loginIdsOfUnblockedAccounts(List<AuditLog> logs) {
        List<Long> targetIds = logs.stream().filter(log -> log.getAction() == AuditAction.UNBLOCK)
                .map(AuditLog::getTargetId).filter(Objects::nonNull).distinct().toList();
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        return accountRepository.findAllByIdIn(targetIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getLoginId));
    }

    private LoginHistoryItemResponse toItem(AuditLog log, Map<Long, String> unblockedLoginIds) {
        String result = switch (log.getAction()) {
            case LOGIN_SUCCESS -> "success";
            case LOGIN_FAIL -> "fail";
            default -> null;
        };
        boolean blockEvent = log.getAction() == AuditAction.BLOCK || log.getAction() == AuditAction.UNBLOCK;
        if (log.getAction() == AuditAction.UNBLOCK) {
            return new LoginHistoryItemResponse(log.getTargetId(), unblockedLoginIds.get(log.getTargetId()), result,
                    log.getIp(), log.getOccurredAt(), blockEvent);
        }
        return new LoginHistoryItemResponse(log.getActorAccountId(), log.getActorLoginId(), result, log.getIp(),
                log.getOccurredAt(), blockEvent);
    }
}
