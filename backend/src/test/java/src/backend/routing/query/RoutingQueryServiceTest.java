package src.backend.routing.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.dto.RoutePlanResponse;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.repository.spec.RoutePlanRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * {@code getPublishedForDriver} 담당 기사·선탑자 인가 판정의 특성화 테스트(R1-①).
 * rideevent 와 동일한 로직 중복 지점이며, {@code BusCrewGuard} 로 뽑기 전 현재 동작을 고정한다.
 */
class RoutingQueryServiceTest {

    private final RoutePlanRepository routePlanRepository = mock(RoutePlanRepository.class);
    private final BusRepository busRepository = mock(BusRepository.class);

    private final RoutingQueryService service = new RoutingQueryService(routePlanRepository, busRepository);

    private static final Long TENANT_ID = 1L;

    @Test
    void getPublishedForDriver_byUnassignedAttendant_throwsForbidden() {
        AuthUser actor = authUser(999L, Role.ATTENDANT);
        Bus bus = bus(1L, user(300L, "driver@school.com", "박기사"), user(200L, "attendant@school.com", "김선탑"));
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));

        assertThatThrownBy(() -> service.getPublishedForDriver(actor, 1L, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void getPublishedForDriver_byAssignedDriver_returnsPlans() {
        AuthUser actor = authUser(300L, Role.DRIVER);
        Bus bus = bus(1L, user(300L, "driver@school.com", "박기사"), user(200L, "attendant@school.com", "김선탑"));
        given(busRepository.findById(1L)).willReturn(Optional.of(bus));
        LocalDate date = LocalDate.now();
        given(routePlanRepository.findByBusIdAndServiceDateAndStatusOrderByDirectionAsc(
                1L, date, RoutePlanStatus.PUBLISHED))
                .willReturn(List.of(routePlan(7L, 1L)));

        List<RoutePlanResponse> plans = service.getPublishedForDriver(actor, 1L, date);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).id()).isEqualTo(7L);
    }

    private RoutePlan routePlan(Long id, Long busId) {
        RoutePlan plan = RoutePlan.builder()
                .tenantId(TENANT_ID).busId(busId).direction(RouteDirection.PICKUP)
                .status(RoutePlanStatus.PUBLISHED).version(1).serviceDate(LocalDate.now()).build();
        ReflectionTestUtils.setField(plan, "id", id);
        return plan;
    }

    private Bus bus(Long id, User driver, User attendant) {
        Bus bus = Bus.builder().tenant(tenant(TENANT_ID)).name("3호차").seatCapacity(25)
                .driver(driver).attendant(attendant).build();
        ReflectionTestUtils.setField(bus, "id", id);
        return bus;
    }

    private User user(Long id, String email, String name) {
        User user = User.builder().email(email).name(name).password("x").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Tenant tenant(Long id) {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", id);
        return tenant;
    }

    private AuthUser authUser(Long userId, Role role) {
        return new AuthUser(userId, "user@school.com", List.of(new AuthUser.Membership(TENANT_ID, role)));
    }
}
