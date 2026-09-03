package src.backend.monitoring.dto;

import src.backend.boarding.entity.RiderStatus;
import src.backend.global.common.enums.ChangeType;

/**
 * 회차 1건의 탑승자 상태·변경 구분 집계 한 줄(§5.3 대시보드, MON-01~06) — {@code run_rider} 를
 * {@code runId, status, change} 로 묶어 센 결과다.
 *
 * <p>{@code status}·{@code change} 는 서로 다른 축이다({@code RunRider} 자바독) — 이 뷰가 둘을 함께
 * 싣는 이유는 대시보드가 같은 회차에서 상태 집계(탑승 현재/전체 · 미승차 · 미등원)와 변경분 집계
 * (추가·제외 건수, MON-05)를 모두 필요로 해서다. 한 쿼리로 GROUP BY 하면 두 축의 조합마다 한 행이
 * 나오므로, 소비 측(서비스 계층)이 필요한 축으로 다시 접어 합산한다.
 *
 * @param status {@code null} 이 될 수 없다(컬럼이 {@code nullable = false})
 * @param change {@code null} 이면 변경 없음(원래 명단) — {@code added}·{@code removed} 만 집계 대상
 */
public record StaffRunRiderAggregateView(Long runId, RiderStatus status, ChangeType change, long count) {
}
