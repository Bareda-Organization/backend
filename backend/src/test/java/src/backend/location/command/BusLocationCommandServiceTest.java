package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.BusLocationPing;
import src.backend.location.dto.BusLocationReportRequest;
import src.backend.location.dto.BusLocationView;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.event.BusLocationUpdatedEvent;
import src.backend.location.query.BusLocationQueryService;
import src.backend.location.repository.impl.InMemoryBusLocationRepository;
import src.backend.location.repository.spec.BusLocationRepository;
import src.backend.student.repository.spec.StudentGuardianRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.user.entity.Role;
import src.backend.user.entity.User;

/**
 * 기사 단말 보고의 좌표 출처(origin) 수신 단위 테스트(MON-2).
 * 예전에는 {@code reportSelf()}가 출처를 {@code GPS} 로 고정해 시뮬레이터 좌표까지 "단말" 로 기록했다 —
 * 여기서는 보고한 값이 저장·조회·이벤트 세 곳에 그대로 도달하는지, 그리고 {@code origin} 을 생략한
 * 기존 클라이언트가 여전히 {@code GPS} 로 기록되는지를 고정한다.
 * 저장소는 mock 이 아니라 실제 {@link InMemoryBusLocationRepository}를 써서 저장→조회 왕복을 그대로 관측한다.
 */
class BusLocationCommandServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long BUS_ID = 7L;
    private static final Long DRIVER_ID = 30L;
    private static final Long ADMIN_ID = 10L;

    private final BusLocationRepository busLocationRepository = new InMemoryBusLocationRepository();
    private final BusRepository busRepository = mock(BusRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final StudentGuardianRepository studentGuardianRepository = mock(StudentGuardianRepository.class);

    private final BusLocationCommandService commandService =
            new BusLocationCommandService(busLocationRepository, busRepository, eventPublisher);
    private final BusLocationQueryService queryService =
            new BusLocationQueryService(busLocationRepository, busRepository, studentGuardianRepository);

    /** 기존 프론트는 {busId, lat, lng} 만 보낸다 — 이 경로가 깨지면 기사 앱이 즉시 죽는다. */
    @Test
    void reportSelf_withoutOrigin_isStoredAsGps() {
        givenAssignedBus();

        commandService.reportSelf(driver(), new BusLocationReportRequest(BUS_ID, 37.5075, 127.0355, null));

        assertThat(busLocationRepository.findLatest(BUS_ID))
                .map(BusLocationPing::origin).contains(LocationOrigin.GPS);
    }

    /** 시뮬레이터로 보고한 좌표는 단말 좌표로 둔갑하지 않는다. */
    @Test
    void reportSelf_withMockOrigin_isStoredAsMock() {
        givenAssignedBus();

        commandService.reportSelf(driver(),
                new BusLocationReportRequest(BUS_ID, 37.5075, 127.0355, LocationOrigin.MOCK));

        assertThat(busLocationRepository.findLatest(BUS_ID))
                .map(BusLocationPing::origin).contains(LocationOrigin.MOCK);
    }

    /** 저장한 출처가 관제 조회 응답까지 그대로 도달한다(관제 상세 패널의 "단말/시뮬레이터" 표기 근거). */
    @Test
    void reportedOrigin_reachesMonitoringView() {
        Bus bus = givenAssignedBus();
        given(busRepository.findByTenantId(TENANT_ID)).willReturn(List.of(bus));

        commandService.reportSelf(driver(),
                new BusLocationReportRequest(BUS_ID, 37.5075, 127.0355, LocationOrigin.MOCK));

        List<BusLocationView> views = queryService.getTenantBusLocations(admin(), TENANT_ID);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).busId()).isEqualTo(BUS_ID);
        assertThat(views.get(0).origin()).isEqualTo(LocationOrigin.MOCK);
    }

    /** 실시간 push 로 나가는 이벤트도 같은 출처를 실어야 한다 — 조회와 push 가 다른 값을 말하면 안 된다. */
    @Test
    void reportedOrigin_isCarriedByPublishedEvent() {
        givenAssignedBus();

        commandService.reportSelf(driver(),
                new BusLocationReportRequest(BUS_ID, 37.5075, 127.0355, LocationOrigin.MOCK));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BusLocationUpdatedEvent.class);
        assertThat(((BusLocationUpdatedEvent) captor.getValue()).origin()).isEqualTo(LocationOrigin.MOCK);
    }

    /** 서버 내부 Mock 소스가 부르는 주입 경로는 예전 그대로 — 인자로 받은 출처를 저장한다. */
    @Test
    void ingest_keepsOriginGivenByServerSideSource() {
        commandService.ingest(TENANT_ID, BUS_ID, 37.5075, 127.0355, LocationOrigin.MOCK);

        assertThat(busLocationRepository.findLatest(BUS_ID))
                .map(BusLocationPing::origin).contains(LocationOrigin.MOCK);
    }

    private Bus givenAssignedBus() {
        Bus bus = bus();
        given(busRepository.findById(BUS_ID)).willReturn(Optional.of(bus));
        return bus;
    }

    private Bus bus() {
        Bus bus = Bus.builder().tenant(tenant()).name("3호차").seatCapacity(25).driver(driverUser()).build();
        ReflectionTestUtils.setField(bus, "id", BUS_ID);
        return bus;
    }

    private Tenant tenant() {
        Tenant tenant = Tenant.builder().name("한빛학원").build();
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    private User driverUser() {
        User user = User.builder().email("driver@school.com").name("박기사").password("x").build();
        ReflectionTestUtils.setField(user, "id", DRIVER_ID);
        return user;
    }

    private AuthUser driver() {
        return new AuthUser(DRIVER_ID, "driver@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.DRIVER)));
    }

    private AuthUser admin() {
        return new AuthUser(ADMIN_ID, "admin@school.com",
                List.of(new AuthUser.Membership(TENANT_ID, Role.ACADEMY_ADMIN)));
    }
}
