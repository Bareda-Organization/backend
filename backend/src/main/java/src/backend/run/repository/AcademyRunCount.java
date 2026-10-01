package src.backend.run.repository;

/** 학원별 회차 개수 집계 한 행 — {@link RunRepository} 의 학원 단위 {@code GROUP BY} 조회가 돌려준다. */
public interface AcademyRunCount {

    Long getAcademyId();

    long getRunCount();
}
