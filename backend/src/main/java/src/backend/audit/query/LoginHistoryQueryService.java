package src.backend.audit.query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.dto.LoginHistoryItemResponse;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
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
 * (ERD §3.6 · 조율자 Ruling 13) — {@code AuditLog.blockEvent} 컬럼값을 그대로 옮기지 않는 이유는
 * {@link LoginHistoryItemResponse} 자바독에 적었다.
 *
 * <p><b>미확인 사항(보고서에 남김)</b> — {@code account_id}·{@code login_id} 를 이 서비스는
 * 모든 행에서 {@code actor}(시도를 한 계정)로 통일해 매핑한다. {@code unblock} 행은
 * {@code AccountUnblockCommandService}(다른 Phase, 이 태스크 소유 밖)가 "해제를 실행한 관리자" 를
 * {@code actorAccountId} 에 채운다 — 즉 그 행의 {@code account_id} 는 <b>해제된 계정이 아니라
 * 해제한 관리자</b>를 가리킨다. §6.13 문면과 ERD §3.6 어느 쪽도 이 경우의 의미를 명시하지 않아,
 * 컬럼 자체의 정의("행위자 계정")를 그대로 따르는 쪽을 택했다 — 반대로 "해제된 계정" 을 기대했다면
 * {@code account_id} 필터로 그 계정의 unblock 이력이 조회되지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoginHistoryQueryService {

    private static final Sort ORDER = Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final AuditLogRepository auditLogRepository;

    private final AcademyRepository academyRepository;

    private final AccountRepository accountRepository;

    public PageResponse<LoginHistoryItemResponse> list(Long academyId, Long accountId, String from, String to,
            Integer page, Integer size) {
        validateFilters(academyId, accountId);
        Page<AuditLog> result = auditLogRepository.search(AuditCategory.LOGIN, academyId, accountId,
                AuditQueryRange.from(from), AuditQueryRange.to(to), PageParams.of(page, size).toPageable(ORDER));

        return PageResponse.of(result, result.getContent().stream().map(this::toItem).toList());
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

    private LoginHistoryItemResponse toItem(AuditLog log) {
        String result = switch (log.getAction()) {
            case LOGIN_SUCCESS -> "success";
            case LOGIN_FAIL -> "fail";
            default -> null;
        };
        boolean blockEvent = log.getAction() == AuditAction.BLOCK || log.getAction() == AuditAction.UNBLOCK;
        return new LoginHistoryItemResponse(log.getActorAccountId(), log.getActorLoginId(), result, log.getIp(),
                log.getOccurredAt(), blockEvent);
    }
}
