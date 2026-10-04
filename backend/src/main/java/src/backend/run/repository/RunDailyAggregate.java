package src.backend.run.repository;

import java.time.LocalDate;

/** 학원·운행일별 회차 집계 한 행 — {@link RunRepository#aggregateDaily} 가 돌려준다(§6.18). */
public interface RunDailyAggregate {

    LocalDate getServiceDate();

    Long getAcademyId();

    /** 미취소 회차 수. */
    long getRunCount();

    /** 임시 취소된 회차 수. */
    long getCanceledCount();

    /** 미취소 회차 중 실제로 시작한 수(분모). */
    long getStartedCount();

    /** 시작한 회차 중 실제 시작이 출발 예정 + 5분 이내인 수(Ruling 802). */
    long getOnTimeCount();

    /** 미취소 회차 중 지연 알림이 1건 이상 나간 수. */
    long getDelayedCount();
}
