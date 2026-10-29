package src.backend.routing.command;

import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.ConstraintViolations;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.RouteRepository;

/**
 * 편성 유일성 조합(차량·요일·방향) 강제(API_SPEC §5.9 · Ruling 180) — 호출자의 트랜잭션 안에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class RouteSlotGuard {

    /**
     * 유일성 조합을 강제하는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가리는 이유는 {@code route} 에 CHECK 가 둘 더 있기 때문이다
     * ({@code ck_route_weekday}·{@code ck_route_direction}). 제약을 가리지 않고
     * {@code DataIntegrityViolationException} 을 통째로 409 로 옮기면 값 도메인 위반까지 "이미 편성된
     * 차량·요일·방향입니다" 로 답해 원인을 감춘다({@code BusCommandService} 와 같은 형태).
     */
    private static final String ROUTE_SLOT_UNIQUE_CONSTRAINT = "uk_route_bus_weekday_direction";

    private final RouteRepository routeRepository;

    /**
     * 유일성 조합을 강제하며 작업을 실행한다 — 위반은 선검사에서든 DB 거부에서든 같은
     * {@code 409 DUPLICATE_ROUTE} 다(Ruling 180).
     *
     * <p><b>선검사만으로는 부족하다.</b> 동시 요청 2건은 서로의 미커밋 INSERT 를 보지 못한 채 둘 다
     * 선검사를 지나고, 그 뒤 {@code uk_route_bus_weekday_direction} 이 하나를 거부한다. 그 거부를
     * 옮기지 않으면 {@code 500} 이 나가 "서버가 고장났다" 와 "이미 편성돼 있다" 가 구별되지 않는다
     * ({@code BusCommandService}·{@code ScheduleCommandService} 가 같은 자리를 이미 푼다).
     *
     * <p>{@code action} 실행 후 즉시 flush 하는 것이 수정 경로 때문에 필요하다 — 조합 변경은 <b>변경
     * 감지</b>로만 DB 에 닿아서, flush 하지 않으면 {@code UPDATE} 가 커밋 시점까지 미뤄지고 제약
     * 위반이 이 {@code try} 밖에서 터진다. 편성 경로는 {@code IDENTITY} 키를 받으려고 {@code save()}
     * 시점에 이미 나가므로 flush 가 없어도 잡힌다 — <b>두 경로가 다르다.</b>
     *
     * <p>{@link jakarta.persistence.EntityManager} 가 아니라 <b>저장소의</b> flush 를 부르는 것도
     * 같은 이유로 중요하다 — 예외 번역({@code DataIntegrityViolationException})은 {@code @Repository}
     * 빈을 거칠 때만 붙어, {@code EntityManager} 를 직접 부르면 Hibernate 예외가 이 {@code catch} 를
     * 그대로 지나친다.
     */
    public <T> T enforcingUniqueSlot(Long academyId, RoutePlan slot, Supplier<T> action) {
        if (routeRepository.existsByAcademyIdAndBusIdAndWeekdayAndDirection(
                academyId, slot.busId(), slot.weekday(), slot.direction())) {
            throw new BusinessException(ErrorCode.DUPLICATE_ROUTE);
        }
        try {
            T result = action.get();
            routeRepository.flush();
            return result;
        } catch (DataIntegrityViolationException e) {
            if (isSlotViolation(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_ROUTE);
            }
            throw e;
        }
    }

    private boolean isSlotViolation(DataIntegrityViolationException e) {
        return ConstraintViolations.isViolationOf(e, ROUTE_SLOT_UNIQUE_CONSTRAINT);
    }
}
