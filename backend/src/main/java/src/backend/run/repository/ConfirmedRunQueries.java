package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.run.entity.Run;

/**
 * 확정된 회차를 전 학원 대상으로 세는 조회 — {@link RunRepository} 가 이어 받는다. 확정 뒤 시작하지 않은 회차를 관측 게이지에 드러내는 용도뿐이다.
 */
public interface ConfirmedRunQueries {

    /**
     * 확정됐는데 운행 시작 가능 시간창이 닫히도록(출발 시각 + 10분이 {@code threshold} 보다 이르다) 시작하지 않은 회차 수 — 취소 회차와 운행일이
     * {@code today} 보다 이른 회차는 세지 않는다. 지난 날짜의 확정 회차는 자동으로 닫히지 않아 세면 경보가 다시 꺼지지 않는다.
     * {@code status} 는 부분 인덱스가 쓰이도록 리터럴이다.
     */
    @AcademyScopeExempt(reason = "확정 뒤 미시작 회차 게이지는 시각이 촉발하는 전 학원 대상 집계라 좁힐 학원이 부재하다 — "
            + "countOverdueUnconfirmed 와 같은 근거. 호출부는 관측 스케줄러(RunConfirmedNotStartedGaugeScheduler)뿐이라는 전제")
    @Query("SELECT COUNT(r) FROM Run r WHERE r.status = src.backend.run.entity.RunStatus.CONFIRMED "
            + "AND r.departTime < :threshold AND r.serviceDate >= :today AND r.canceledAt IS NULL")
    long countConfirmedNotStarted(@Param("threshold") OffsetDateTime threshold, @Param("today") LocalDate today);
}
