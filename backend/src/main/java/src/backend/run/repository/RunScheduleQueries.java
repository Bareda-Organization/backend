package src.backend.run.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import src.backend.global.common.enums.Direction;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunCancelSource;
import src.backend.run.entity.RunStatus;

/**
 * {@link RunRepository} 의 스케줄 연동 조회 묶음 — 스케줄 수정 · 삭제를 미리 만든 회차에 반영할 때 고르는 회차와 중복 확인.
 * 원래 저장소가 이 인터페이스를 상속하므로 메서드 이름 · 시그니처 · 애너테이션은 그대로이고 호출부는 {@link RunRepository} 를 쓴다.
 */
@NoRepositoryBean
public interface RunScheduleQueries extends Repository<Run, Long> {

    /**
     * 스케줄이 만든 <b>오늘 이후 · 주어진 상태 · 미취소</b> 회차 — 스케줄 수정·삭제를 미리 만든 회차에 반영할 때
     * 고른다(Ruling 366 ②). 오늘({@code today})은 포함하지 않는다 — 오늘 회차는 확정 배치가 이미 걸려 있을 수 있다.
     */
    List<Run> findAllByAcademyIdAndScheduleIdAndServiceDateAfterAndStatusAndCanceledAtIsNull(Long academyId,
            Long scheduleId, LocalDate today, RunStatus status);

    /**
     * 스케줄이 <b>취소한</b> 오늘 이후 · 주어진 상태 회차 — 스케줄 재활성·요일/방향 복귀 때 되살릴 후보다(Ruling 367 ②).
     * 관계자가 직접 취소한 회차({@code cancel_source = staff})와 출처를 모르는 옛 취소({@code NULL})는 고르지 않는다.
     */
    List<Run> findAllByAcademyIdAndScheduleIdAndServiceDateAfterAndStatusAndCancelSource(Long academyId,
            Long scheduleId, LocalDate today, RunStatus status, RunCancelSource cancelSource);

    /**
     * 그 차량·날짜·방향·출발 시각을 <b>다른</b> 회차가 이미 잡고 있는가 — 스케줄 수정이 회차를 옮기기 전에 본다.
     * 취소된 회차도 센다({@code uk_run_bus_date_direction_depart} 가 취소 여부를 가리지 않는다).
     */
    boolean existsByAcademyIdAndBusIdAndServiceDateAndDirectionAndDepartTimeAndIdNot(Long academyId, Long busId,
            LocalDate serviceDate, Direction direction, OffsetDateTime departTime, Long id);

    /**
     * 그 스케줄이 그날 그 방향으로 <b>살아 있는</b>(미취소) 회차를 이미 가졌는가 — 스케줄이 취소했던 회차를 되살리기
     * 전에 본다(관계자 취소까지 막는 생성 쪽 판정은 {@link #existsLiveOrNonScheduleCanceled}). 확정·시작된 회차는 수정
     * 반영 대상이 아니라 옛 출발 시각으로 남으므로, 유일성 조합만 보면 같은 스케줄의 회차가 하나 더 살아난다.
     */
    boolean existsByAcademyIdAndScheduleIdAndServiceDateAndDirectionAndCanceledAtIsNull(Long academyId,
            Long scheduleId, LocalDate serviceDate, Direction direction);

    /**
     * 그 스케줄이 그날 그 방향으로 <b>스케줄이 새로 만들면 안 되는</b> 회차를 이미 가졌는가(BR-316) — 살아 있는(미취소) 회차이거나
     * {@code scheduleSource} 가 아닌 출처로 취소된 회차다. 관계자가 취소한 날은 스케줄이 어떻게 바뀌어도 되살리지 않으므로(§5.10),
     * 출발 시각·차량이 바뀌어 유일성 조합이 달라져도 그 자리에 새 회차를 만들지 않는다. 스케줄이 취소한 회차는 세지 않는다 —
     * 그 회차는 되살림 경로가 맡는다. 출처가 비어 있는 취소(출처를 모르는 옛 행)는 관계자 취소와 같이 취급한다.
     */
    @Query("SELECT COUNT(r) > 0 FROM Run r WHERE r.academyId = :academyId AND r.scheduleId = :scheduleId "
            + "AND r.serviceDate = :serviceDate AND r.direction = :direction "
            + "AND (r.canceledAt IS NULL OR r.cancelSource IS NULL OR r.cancelSource <> :scheduleSource)")
    boolean existsLiveOrNonScheduleCanceled(@Param("academyId") Long academyId, @Param("scheduleId") Long scheduleId,
            @Param("serviceDate") LocalDate serviceDate, @Param("direction") Direction direction,
            @Param("scheduleSource") RunCancelSource scheduleSource);
}
