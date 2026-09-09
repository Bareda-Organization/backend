package src.backend.audit.query;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * {@code GET /admin/audit-logs}·{@code /admin/login-history} 가 공유하는 {@code from}·{@code to}
 * 파싱(API_SPEC §6.13, Phase 14 T1 목표 3·4).
 *
 * <p>{@code global/request/ApiValues}(전역 공용 변환기)에 두지 않는다 — 그 클래스는 이 태스크
 * (p14-task-t1.md)의 소유 파일 목록 밖이라, 공용 클래스를 고치면 다른 좌석의 소유 경계를 넘는다.
 * 또한 {@code ApiValues} 를 직접 확인한 결과 {@code OffsetDateTime} 파싱 메서드가 이미 없다(
 * {@code date()}·{@code time()} 뿐) — 이 태스크가 처음 필요로 하는 값이다.
 *
 * <p>포맷은 API_SPEC 전역 규약(문서 44행 "ISO-8601 + 오프셋")을 그대로 따른다 — 예:
 * {@code 2026-08-24T08:30:00+09:00}. {@link OffsetDateTime#parse(CharSequence)} 의 기본
 * 포매터({@code ISO_OFFSET_DATE_TIME})가 이 표기와 정확히 일치해 별도 포매터가 필요 없다.
 *
 * <p>미지정 시 대체값을 연도 1·9999 로 둔 이유 — {@code AuditLogRepository.search} 가
 * {@code (:from IS NULL OR ...)} 형태를 쓰지 않는다(그 자바독의 PostgreSQL 타입 추론 실패 근거와
 * 같다). null 을 걸러내는 책임을 SQL 이 아니라 이 클래스(서비스 계층)로 옮긴 것이다.
 */
final class AuditQueryRange {

    /** 하한 미지정 시 대체값 — 이 시각보다 이른 감사 기록은 존재할 수 없다. */
    private static final OffsetDateTime MIN = OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    /** 상한 미지정 시 대체값. */
    private static final OffsetDateTime MAX = OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);

    private AuditQueryRange() {
    }

    static OffsetDateTime from(String raw) {
        return raw == null ? MIN : parse(raw, "from");
    }

    static OffsetDateTime to(String raw) {
        return raw == null ? MAX : parse(raw, "to");
    }

    private static OffsetDateTime parse(String raw, String paramName) {
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    paramName + " 형식이 ISO-8601 이 아닙니다(예: 2026-08-24T08:30:00+09:00): " + raw);
        }
    }
}
