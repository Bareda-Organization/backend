package src.backend.request.repository;

/** 회차별 대기 중 변경 요청 수 한 행 — {@link ChangeRequestRepository#countPendingByRun} 이 돌려준다(§6.18). */
public interface RunPendingCount {

    Long getRunId();

    long getTotal();
}
