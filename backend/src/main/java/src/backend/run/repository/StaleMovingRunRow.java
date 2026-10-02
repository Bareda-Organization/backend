package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import src.backend.global.common.enums.Direction;

/** 끝나지 않은 이동 중 회차 한 행 — {@link RunRepository#findStaleMoving} 이 학원 이름·호차·남은 탑승자 수와 함께 돌려준다. */
public interface StaleMovingRunRow {

    Long getRunId();

    Long getAcademyId();

    String getAcademyName();

    LocalDate getServiceDate();

    Direction getDirection();

    String getBusNo();

    OffsetDateTime getStartedAt();

    boolean getFinishPending();

    long getBoardedCount();
}
