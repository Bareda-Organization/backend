package src.backend.routing.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.entity.Route;

/** {@link Route} 영속성 접근 — 조회는 전부 학원으로 좁혀져 호출부가 조건을 빼먹을 자리가 부재하다. */
public interface RouteRepository extends JpaRepository<Route, Long> {

    /**
     * 한 학원의 고정 노선 목록(RTE-01 · A-08, §5.9) — 학원 조건이 <b>쿼리에 고정</b>돼 있다
     * (ARCHITECTURE §6.1 이 지목한 목록 조회 사고 지점).
     *
     * <p>{@code active=false} 편성도 함께 싣는다 — 목록은 활성 여부를 <b>보여주고 고치는</b> 화면이라,
     * 여기서 거르면 쉬는 편성을 되살릴 경로가 사라진다({@code ScheduleRepository} 와 같은 형태).
     */
    Page<Route> findAllByAcademyId(Long academyId, Pageable pageable);

    /**
     * 조회·수정·삭제·최적화 대상 편성 1건(§5.9) — 학원이 어긋나면 빈 결과이고 호출부가 그것을
     * {@code 404 ROUTE_NOT_FOUND} 로 답한다(Ruling 163: {@code {id}} 지목은 404).
     */
    Optional<Route> findByIdAndAcademyId(Long id, Long academyId);

    /**
     * 같은 차량·요일·방향 편성이 이미 있는지 본다 — {@code uk_route_bus_weekday_direction} 위반을
     * 저장 전에 막는다.
     *
     * <p>제약 자체에는 {@code academy_id} 가 없는데 여기에 학원 조건을 더한 것은 <b>좁히기 위함이
     * 아니라 격리 규칙(횡단 규칙 7)을 지키기 위함</b>이다. 호출부가 차량이 자기 학원 것임을 먼저
     * 확인하므로(§5.9 {@code 404 BUS_NOT_FOUND}) 그 차량의 편성은 전부 같은 학원 것이고, 조건이
     * 하나 더 붙어도 결과가 달라지지 않는다({@code ScheduleRepository} 의 같은 자리와 같은 근거).
     *
     * <p>경합으로 선검사를 지나쳐도 UNIQUE 가 뒤에서 막으므로 중복 행은 생기지 않는다 — 다만 그때
     * 나오는 거부를 호출부가 {@code 409} 로 옮기지 않으면 {@code 500} 이 샌다.
     */
    boolean existsByAcademyIdAndBusIdAndWeekdayAndDirection(Long academyId, Long busId, Weekday weekday,
            Direction direction);

    /**
     * 확정 배치(RTE-08)가 회차의 {@code bus_id}·요일·방향으로 편성을 찾는다(Phase 7) — 편성이 없으면
     * {@code Optional.empty()} 이고 호출부가 그 회차만 {@code ROUTE_NOT_CONFIGURED_FOR_RUN} 으로
     * 실패시킨다(목표 4, 다른 회차는 영향받지 않는다).
     */
    Optional<Route> findByAcademyIdAndBusIdAndWeekdayAndDirection(Long academyId, Long busId, Weekday weekday,
            Direction direction);
}
