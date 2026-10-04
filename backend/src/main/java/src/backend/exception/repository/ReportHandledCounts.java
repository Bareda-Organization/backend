package src.backend.exception.repository;

/** 예외 보고 처리·미처리 건수 한 쌍(§5.20 {@code counts}) — {@link ExceptionReportRepository#countByHandled} 의 결과 모양. */
public interface ReportHandledCounts {

    /** 처리된 보고 수. */
    long getHandled();

    /** 아직 처리되지 않은 보고 수. */
    long getUnhandled();
}
