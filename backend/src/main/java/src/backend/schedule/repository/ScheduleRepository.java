package src.backend.schedule.repository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.schedule.entity.Schedule;

/** {@link Schedule} 영속성 접근 — 관계자 경로의 조회는 전부 학원으로 좁혀져 있다. */
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    /**
     * 한 학원의 스케줄 목록(SCH-01, §5.10) — 학원 조건이 <b>쿼리에 고정</b>돼 있다
     * (ARCHITECTURE §6.1 이 지목한 목록 조회 사고 지점).
     *
     * <p>{@code active=false} 스케줄도 함께 싣는다 — 목록은 활성 여부를 <b>보여주고 고치는</b>
     * 화면이라, 여기서 거르면 쉬는 스케줄을 되살릴 경로가 사라진다({@code BusRepository} 의
     * {@code operable} 과 같은 형태).
     */
    Page<Schedule> findAllByAcademyId(Long academyId, Pageable pageable);

    /**
     * 수정·삭제 대상 스케줄 1건(SCH-01, §5.10) — 학원이 어긋나면 빈 결과이고 호출부가 그것을
     * {@code 404 SCHEDULE_NOT_FOUND} 로 답한다(Ruling 163: {@code {id}} 지목은 404).
     */
    Optional<Schedule> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 같은 유일성 조합의 스케줄이 이미 있는지 본다 — {@code uk_schedule_bus_weekday_direction_depart}
     * 위반을 저장 전에 막는다.
     *
     * <p>제약 자체에는 {@code academy_id} 가 없는데 여기에 학원 조건을 더한 것은 <b>좁히기 위함이
     * 아니라 격리 규칙(횡단 규칙 7)을 지키기 위함</b>이다. 호출부가 차량이 자기 학원 것임을 먼저
     * 확인하므로(§5.10 {@code 404 BUS_NOT_FOUND}) 그 차량의 스케줄은 전부 같은 학원 것이고, 조건이
     * 하나 더 붙어도 결과가 달라지지 않는다. 어긋난 데이터로 이 선검사를 지나치더라도 UNIQUE 가
     * 뒤에서 막아 중복 행은 생기지 않는다.
     */
    boolean existsByAcademyIdAndBusIdAndWeekdayAndDirectionAndDepartTime(Long academyId, Long busId,
            Weekday weekday, Direction direction, LocalTime departTime);

    /**
     * 그 요일의 <b>활성</b> 스케줄 전건 — 일일 회차 생성 배치(SCH-02)의 입력이다.
     *
     * <p>{@code active} 조건이 여기 있는 것이 요점이다 — 조건이 빠지면 쉬는 스케줄까지 회차를 만들고,
     * 그 회차가 확정 배치(Phase 7)의 대상이 되어 운행하지 않는 노선이 계산된다.
     */
    @AcademyScopeExempt(reason = "일일 회차 생성 배치(SCH-02)는 요청이 아니라 시각이 일으키는 전 학원 대상 작업이라 "
            + "좁힐 학원이 부재하다 — 학원을 하나 골라 좁히면 나머지 학원의 회차가 생기지 않는다. "
            + "호출부가 배치(RunGenerationService)뿐이라는 전제 — 요청 경로에서 부르면 이 예외가 우회로가 된다")
    List<Schedule> findAllByWeekdayAndActiveIsTrue(Weekday weekday);
}
