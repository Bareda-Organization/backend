package src.backend.bus.repository;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.bus.entity.Bus;

/** {@link Bus} 영속성 접근 — 조회는 전부 학원으로 좁혀져 호출부가 조건을 빼먹을 자리가 부재하다. */
public interface BusRepository extends JpaRepository<Bus, Long> {

    /**
     * 한 학원의 차량 목록(BUS-01, §5.12) — 학원 조건이 <b>쿼리에 고정</b>돼 있다
     * (ARCHITECTURE §6.1 이 지목한 목록 조회 사고 지점).
     *
     * <p>{@code operable=false} 차량도 함께 싣는다 — 목록은 운행 가능 여부를 <b>보여주고 고치는</b>
     * 화면이라, 여기서 거르면 고장 난 차량을 되살릴 경로가 사라진다.
     */
    Page<Bus> findAllByAcademyId(Long academyId, Pageable pageable);

    /**
     * 수정 대상 차량 1건(BUS-03, §5.12) — 학원이 어긋나면 빈 결과이고 호출부가 그것을
     * {@code 404 BUS_NOT_FOUND} 로 답한다.
     *
     * <p>꺼낸 뒤에 학원을 대조하지 않는 이유는 그 형태로는 "없음" 과 "남의 학원" 이 갈려 존재 여부가
     * 응답에서 드러나기 때문이다({@code StudentRepository#findByIdAndAcademyIdAndDeletedAtIsNull}
     * 과 같은 형태).
     */
    Optional<Bus> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 여러 차량을 한 번에 읽는다 — 목록 응답이 행마다 호차를 붙일 때 쓴다(SCH-01 · SCH-02).
     *
     * <p>{@code findAllById} 를 쓰지 않는 이유는 그것이 <b>학원 조건을 붙일 자리가 부재한</b> 전건
     * 조회이기 때문이다(횡단 규칙 7 · {@code AcademyScopeRepositoryConventionTest}) — id 목록이 어디서
     * 왔든 남의 학원 차량이 섞이면 그 호차가 목록에 실린다.
     */
    List<Bus> findAllByAcademyIdAndIdIn(Long academyId, Collection<Long> ids);

    /** 학원 여러 곳 안에서 id 로 일괄 조회한다 — 메인 관리자 대시보드(§6.18)가 학원을 가로질러 읽되 선택한 학원 안으로만 좁힌다. */
    List<Bus> findAllByAcademyIdInAndIdIn(Collection<Long> academyIds, Collection<Long> ids);

    /**
     * 같은 학원에 같은 호차가 이미 있는지 본다 — {@code uk_bus_academy_bus_no} 위반을 저장 전에 막는다.
     *
     * <p>선검사가 필요한 이유는 제약 위반이 예외 번역을 거치지 않아 {@code 500} 으로 새기 때문이다.
     * 경합으로 선검사를 지나쳐도 UNIQUE 가 뒤에서 막으므로 중복 행은 생기지 않는다.
     */
    boolean existsByAcademyIdAndBusNo(Long academyId, String busNo);

    /**
     * 차량별 활성 편성 수(§5.12 {@code route_count}, Ruling 816) — 쉬는 편성({@code active=false})은 세지 않는다. 편성이 없는
     * 차량은 행이 없으니 호출부가 0 으로 읽는다. 학원 조건을 쿼리에 고정한다.
     *
     * <p>편성·스케줄·회차 테이블을 차량 모듈에서 네이티브 질의로 읽는 이유 — 그 모듈의 저장소를 import 하면 차량↔노선 ·
     * 차량↔스케줄 · 차량↔회차 양방향 참조가 생긴다(그 모듈들이 이미 차량을 참조한다, {@code ModuleMutualDependencyTest} · R48 병합).
     */
    @Query(value = "SELECT bus_id AS busId, COUNT(*) AS total FROM route "
            + "WHERE academy_id = :academyId AND bus_id IN (:busIds) AND active GROUP BY bus_id", nativeQuery = true)
    List<BusCount> countActiveRoutesByBusIds(@Param("academyId") Long academyId, @Param("busIds") Collection<Long> busIds);

    /** 차량별 활성 스케줄 수(§5.12 {@code schedule_count}, Ruling 816) — 쉬는 스케줄은 세지 않는다. 학원 조건을 쿼리에 고정한다. */
    @Query(value = "SELECT bus_id AS busId, COUNT(*) AS total FROM schedule "
            + "WHERE academy_id = :academyId AND bus_id IN (:busIds) AND active GROUP BY bus_id", nativeQuery = true)
    List<BusCount> countActiveSchedulesByBusIds(@Param("academyId") Long academyId,
            @Param("busIds") Collection<Long> busIds);

    /**
     * 그 차량들의 그날 미취소 회차를 출발 순으로(§5.12 {@code today_runs[]}, Ruling 816) — 차량 목록이 차량마다 따로 묻지 않게
     * 한 번에 모은다. 방향·상태는 DB 저장값(소문자 — {@code ck_run_direction} · {@code ck_run_status})을 그대로 싣는다.
     */
    @Query(value = "SELECT id AS runId, bus_id AS busId, direction, depart_time AS departTime, status FROM run "
            + "WHERE academy_id = :academyId AND bus_id IN (:busIds) AND service_date = :serviceDate "
            + "AND canceled_at IS NULL ORDER BY depart_time, id", nativeQuery = true)
    List<BusTodayRun> findTodayRunsByBusIds(@Param("academyId") Long academyId, @Param("busIds") Collection<Long> busIds,
            @Param("serviceDate") LocalDate serviceDate);

    /** 차량 id 와 그 차량에 걸린 행 수 — {@link #countActiveRoutesByBusIds} · {@link #countActiveSchedulesByBusIds} 의 한 행. */
    interface BusCount {

        Long getBusId();

        long getTotal();
    }

    /** {@link #findTodayRunsByBusIds} 의 한 행 — 차량 목록의 오늘 운행 칸. */
    interface BusTodayRun {

        Long getRunId();

        Long getBusId();

        String getDirection();

        /** 네이티브 질의는 {@code timestamptz} 를 {@link Instant} 로 돌려준다 — 호출부가 서비스 시간대로 바꾼다. */
        Instant getDepartTime();

        String getStatus();
    }
}
