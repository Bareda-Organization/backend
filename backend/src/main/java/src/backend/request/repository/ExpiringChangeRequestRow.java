package src.backend.request.repository;

import java.time.OffsetDateTime;

/** 마감이 임박한 대기 중 변경 요청의 회차별 묶음 한 행 — {@link ChangeRequestRepository#findExpiringByRun} 이 돌려준다(§6.18). */
public interface ExpiringChangeRequestRow {

    Long getRunId();

    /** 그 회차의 대기 요청 중 가장 이른 마감. */
    OffsetDateTime getDeadlineAt();

    long getTotal();
}
