package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import src.backend.global.common.enums.Direction;

/** 끝나지 않은 이동 중 회차 한 행 — {@link RunRepository#findStaleMoving} 이 학원 이름·호차·남은 탑승자 수와 함께 돌려준다. */
public interface StaleMovingRunRow {

    Long getRunId();

    Long getAcademyId();

    String getAcademyName();

    /** 학원 대표 연락처 — 미등록이면 {@code null}(Ruling 808). */
    String getAcademyContact();

    LocalDate getServiceDate();

    Direction getDirection();

    String getBusNo();

    OffsetDateTime getStartedAt();

    boolean getFinishPending();

    long getBoardedCount();
}
