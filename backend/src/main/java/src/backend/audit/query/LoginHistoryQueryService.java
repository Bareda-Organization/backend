package src.backend.audit.query;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * 시도한 계정(행위자)이다. 해제 행의 행위자(해제한 관리자)는 {@code AuditLog.actorAccountId} 에 남아 있고, 이 응답은 그 계정의
 * 현재 <b>이름</b>만 {@code unblocked_by_name} 으로 싣는다(Ruling 846 — 계정 식별자·로그인 아이디는 싣지 않는다).
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

    private final Clock clock;

    /** 로그인 성공·실패·차단 이력을 최신순(동률은 id 오름차순)으로 페이징해 돌려준다(§6.13). */
    public PageResponse<LoginHistoryItemResponse> list(AuditQueryFilter filter) {
        validateFilters(filter.academyId(), filter.accountId());
        AuditQueryRange range = AuditQueryRange.of(filter.from(), filter.to(), OffsetDateTime.now(clock));
        Page<AuditLog> result = auditLogRepository.searchLoginHistory(filter.academyId(), filter.accountId(),
                range.from(), range.to(),
                PageParams.of(filter.page(), filter.size()).toPageable(ORDER));
        Map<Long, Account> unblockAccounts = accountsOfUnblockRows(result.getContent());

        return PageResponse.of(result,
                result.getContent().stream().map(log -> toItem(log, unblockAccounts)).toList());
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

    /**
     * 해제 행이 가리키는 계정(해제된 계정 — 로그인 아이디, 해제한 관리자 — 이름)을 한 번에 읽는다 — 행마다 읽으면 페이지
     * 크기만큼 질의가 는다.
     */
    private Map<Long, Account> accountsOfUnblockRows(List<AuditLog> logs) {
        List<Long> accountIds = logs.stream().filter(log -> log.getAction() == AuditAction.UNBLOCK)
                .flatMap(log -> Stream.of(log.getTargetId(), log.getActorAccountId())).filter(Objects::nonNull)
                .distinct().toList();
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return accountRepository.findAllByIdIn(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
    }

    private LoginHistoryItemResponse toItem(AuditLog log, Map<Long, Account> unblockAccounts) {
        String result = switch (log.getAction()) {
            case LOGIN_SUCCESS -> "success";
            case LOGIN_FAIL -> "fail";
            default -> null;
        };
        String blockAction = switch (log.getAction()) {
            case BLOCK -> "block";
            case UNBLOCK -> "unblock";
            default -> null;
        };
        boolean blockEvent = blockAction != null;
        if (log.getAction() == AuditAction.UNBLOCK) {
            // 행의 IP 는 해제한 관리자의 것이다(Ruling 595) — 이 행은 해제된 계정을 가리키므로 그 계정의 접속 IP 로 읽히지 않게 비운다.
            Account unblocked = accountOf(unblockAccounts, log.getTargetId());
            Account unblockedBy = accountOf(unblockAccounts, log.getActorAccountId());
            return new LoginHistoryItemResponse(log.getTargetId(), unblocked == null ? null : unblocked.getLoginId(),
                    result, null, log.getOccurredAt(), blockEvent, blockAction,
                    unblockedBy == null ? null : unblockedBy.getName());
        }
        return new LoginHistoryItemResponse(log.getActorAccountId(), log.getActorLoginId(), result, log.getIp(),
                log.getOccurredAt(), blockEvent, blockAction, null);
    }

    /** 식별자가 없거나 계정을 못 찾으면 {@code null} 이다 — {@code Map.of()} 는 {@code null} 키 조회에 예외를 던져 따로 거른다. */
    private Account accountOf(Map<Long, Account> accounts, Long accountId) {
        return accountId == null ? null : accounts.get(accountId);
    }
}
