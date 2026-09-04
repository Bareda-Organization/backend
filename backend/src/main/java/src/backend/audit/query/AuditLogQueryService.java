package src.backend.audit.query;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.dto.AuditLogItemResponse;
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

    private static final Sort ORDER = Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final AuditLogRepository auditLogRepository;

    private final AcademyRepository academyRepository;

    private final AccountRepository accountRepository;

    public PageResponse<AuditLogItemResponse> list(Long academyId, Long accountId, String from, String to,
            Integer page, Integer size) {
        validateFilters(academyId, accountId);
        Page<AuditLog> result = auditLogRepository.search(AuditCategory.DATA_ACCESS, academyId, accountId,
                AuditQueryRange.from(from), AuditQueryRange.to(to), PageParams.of(page, size).toPageable(ORDER));

        Map<Long, String> academyNames = academyNamesOf(result.getContent());
        return PageResponse.of(result,
                result.getContent().stream().map(log -> toItem(log, academyNames.get(log.getAcademyId()))).toList());
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

    private AuditLogItemResponse toItem(AuditLog log, String academyName) {
        return new AuditLogItemResponse(log.getActorLoginId(), lower(log.getAction().name()), log.getTargetType(),
                log.getTargetId(), academyName, log.getOccurredAt());
    }

    /**
     * 한 페이지분 학원명을 한 번에 모은다 — 행마다 조회하면 페이지 크기만큼 질의가 늘어난다
     * ({@code AdminBlockedAccountQueryService} 와 같은 근거).
     */
    private Map<Long, String> academyNamesOf(List<AuditLog> logs) {
        List<Long> academyIds = logs.stream().map(AuditLog::getAcademyId).filter(id -> id != null).distinct().toList();
        if (academyIds.isEmpty()) {
            return Map.of();
        }
        return academyRepository.findAllById(academyIds).stream()
                .collect(Collectors.toMap(Academy::getId, Academy::getName, (first, second) -> first));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
