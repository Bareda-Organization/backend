package src.backend.request.repository;

import src.backend.request.entity.ChangeRequestStatus;

/** 변경 요청 결정별 건수 한 행 — {@link ChangeRequestRepository#countDecidedByStatus} 가 돌려준다(§6.18). */
public interface ChangeRequestStatusCount {

    ChangeRequestStatus getStatus();

    long getTotal();
}
