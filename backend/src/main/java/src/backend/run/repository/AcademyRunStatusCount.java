package src.backend.run.repository;

import src.backend.run.entity.RunStatus;

/** 학원·회차 상태별 회차 개수 집계 한 행 — {@link RunRepository#countByAcademyAndStatus} 가 돌려준다. */
public interface AcademyRunStatusCount {

    Long getAcademyId();

    RunStatus getStatus();

    long getRunCount();
}
