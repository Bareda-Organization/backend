package src.backend.exception.query;

import java.util.Locale;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * {@code status} 쿼리 필터(§5.16 관계자·§6.11 메인관리자 공통, `Ruling 297`) — {@code
 * emergency_alert} 는 상태를 컬럼으로 갖지 않고 {@code ackedAt}·{@code canceledAt} 유무로
 * 파생한다 — 그 계산은 {@code EmergencyAlertRepository#SELECT_BY_STATE} 가 쿼리로 하고 이 enum 은 이름만
 * 넘긴다(BR-087). 원래 {@code AdminEmergencyQueryService} 안에만 있던 로직인데,
 * {@code EmergencyStaffQueryService}(§5.16)에도 같은 필터가 필요해져 양쪽이 같은 값·같은
 * 우선순위(취소 > 확인 > 미확인)를 쓰도록 이곳으로 뽑았다.
 */
public enum EmergencyStatusFilter {

    /** 미확인·미취소 — 쿼리 생략 시 기본값. */
    OPEN,
    /** 확인됐으나 취소는 아님. */
    ACKED,
    /** 취소됨 — 확인 여부와 무관하게 취소가 우선한다. */
    CANCELED;

    /** {@code null} 은 기본값 {@link #OPEN}, 그 외 값은 위 3개 밖이면 {@code 422 VALIDATION_FAILED}. */
    public static EmergencyStatusFilter from(String raw) {
        if (raw == null) {
            return OPEN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "status 값이 open/acked/canceled 가 아닙니다: " + raw);
        }
    }
}
