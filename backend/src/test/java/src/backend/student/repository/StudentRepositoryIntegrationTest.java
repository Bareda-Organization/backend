package src.backend.student.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.spec.BusRepository;
import src.backend.route.entity.Route;
import src.backend.route.repository.spec.RouteRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;
import src.backend.tenant.entity.Tenant;
import src.backend.tenant.repository.spec.TenantRepository;

/**
 * {@link StudentRepository#countByAssignedBus_Route_IdAndActiveTrue} 가 실제 Postgres 에서
 * 의도한 대로 해석되는지 확인한다.
 *
 * <p>단위 테스트({@code RouteQueryServiceTest})는 이 메서드를 mock 으로 스텁하기 때문에
 * "이름이 파싱된다"({@code BackendApplicationTests})는 것만 알 수 있을 뿐, 조인 경로와
 * {@code active} 필터가 실제로 WHERE 절에 들어가는지는 증명하지 못한다 — 그 공백을 메운다.
 *
 * <p>{@code @Transactional} 로 감싸 테스트 후 자동 롤백한다 — Flyway 시드 데이터를 건드리지 않는다.
 */
@SpringBootTest
@Transactional
class StudentRepositoryIntegrationTest {

    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private BusRepository busRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void countsOnlyActiveStudentsOnBusesAssignedToTheGivenRoute() {
        Tenant tenant = tenantRepository.save(Tenant.builder().name("집계 테스트 학원").build());
        Route routeA = routeRepository.save(
                Route.builder().tenant(tenant).name("A노선").assignCapacity(10).build());

        // 노선 A 를 운행하는 버스 2대 — 각각 활성 학생 2명·1명
        Bus busA1 = busRepository.save(
                Bus.builder().tenant(tenant).name("A1호차").seatCapacity(20).route(routeA).build());
        Bus busA2 = busRepository.save(
                Bus.builder().tenant(tenant).name("A2호차").seatCapacity(20).route(routeA).build());
        // 노선이 없는(무관한) 버스 1대 — 활성 학생 1명. 노선 A 집계에 섞이면 조인 경로가 잘못된 것이다.
        Bus busNoRoute = busRepository.save(
                Bus.builder().tenant(tenant).name("무소속호차").seatCapacity(20).route(null).build());

        studentRepository.save(activeStudent(tenant, "busA1-활성1", busA1));
        studentRepository.save(activeStudent(tenant, "busA1-활성2", busA1));
        studentRepository.save(activeStudent(tenant, "busA2-활성1", busA2));
        studentRepository.save(activeStudent(tenant, "무관버스-활성1", busNoRoute));

        // 노선 A 버스(busA1)에 배정됐지만 비활성(퇴원)인 학생 — active 필터가 빠지면 이 학생이 섞여 든다.
        Student inactiveOnRouteA = activeStudent(tenant, "busA1-비활성1", busA1);
        inactiveOnRouteA.deactivate();
        studentRepository.save(inactiveOnRouteA);

        studentRepository.flush();

        long assignedCount = studentRepository.countByAssignedBus_Route_IdAndActiveTrue(routeA.getId());

        assertThat(assignedCount)
                .as("노선A 를 운행하는 버스(busA1·busA2)의 활성 학생만 세어야 한다: "
                        + "busA1 활성 2명 + busA2 활성 1명 = 3명. "
                        + "무관 버스(busNoRoute)의 활성 학생 1명이 섞이면 4가 되고, "
                        + "busA1 의 비활성 학생까지 세면 5가 된다.")
                .isEqualTo(3L);
    }

    private Student activeStudent(Tenant tenant, String name, Bus assignedBus) {
        return Student.builder()
                .tenant(tenant)
                .name(name)
                .assignedBus(assignedBus)
                .build();
    }
}
