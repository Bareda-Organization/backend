package src.backend.run.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.AuthUser;
import src.backend.run.command.Phase9RosterFixtures;
import src.backend.run.dto.ManagerRunResponse;

/**
 * 매니저 앱의 담당 회차 목록(§4.1) — 담당 회차가 늘어도 SQL 수가 그대로다(회차마다 명단 · 확정 노선 · 정차 순서를 따로 읽지 않는다). 차량 행을 찾지 못한 회차도 목록 전체를 500 으로
 * 만들지 않는다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class ManagerRunListQueryCountTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2031, 7, 1);

    @Autowired private ApplicationContext applicationContext;
    @Autowired private ManagerRunQueryService queryService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private EntityManager entityManager;
    @MockitoSpyBean private BusRepository busRepository;

    @Test
    @DisplayName("담당 회차가 1건에서 4건으로 늘어도 목록 조회의 SQL 수는 같다")
    void 회차가_늘어도_쿼리_수는_같다() {
        Phase9RosterFixtures fx = Phase9RosterFixtures.of(applicationContext);
        long academyId = fx.academyWithCoordinates();
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.ESCORT, "동승자");
        배치한다(fx, academyId, manager, 0);

        long oneRun = 쿼리_수(academyId, manager);
        for (int i = 1; i < 4; i++) {
            배치한다(fx, academyId, manager, i);
        }

        assertThat(쿼리_수(academyId, manager)).as("회차마다 쿼리가 더해지면 오전 출근 시간에 매니저 수만큼 곱해진다")
                .isEqualTo(oneRun);
    }

    @Test
    @DisplayName("차량 행이 조회되지 않는 회차가 있어도 목록은 예외 없이 회차를 돌려준다")
    void 차량이_없어도_예외가_나지_않는다() {
        Phase9RosterFixtures fx = Phase9RosterFixtures.of(applicationContext);
        long academyId = fx.academyWithCoordinates();
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.ESCORT, "동승자");
        배치한다(fx, academyId, manager, 0);
        doReturn(List.of()).when(busRepository).findAllByAcademyIdAndIdIn(anyLong(), any());

        assertThatCode(() -> {
            List<ManagerRunResponse> items = queryService.list(escort(academyId, manager), SERVICE_DATE.toString());
            assertThat(items).hasSize(1);
        }).doesNotThrowAnyException();
    }

    private long 쿼리_수(long academyId, Phase9RosterFixtures.ManagerAccount manager) {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        queryService.list(escort(academyId, manager), SERVICE_DATE.toString());
        return statistics.getPrepareStatementCount();
    }

    private void 배치한다(Phase9RosterFixtures fx, long academyId, Phase9RosterFixtures.ManagerAccount manager,
            int index) {
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.TUE, Direction.TO_ACADEMY, stopId);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-01T08:00:00+09:00").plusMinutes(index);
        long runId = fx.confirmedRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fx.assign(runId, manager.managerId(), ManagerRole.ESCORT);
    }

    private AuthUser escort(long academyId, Phase9RosterFixtures.ManagerAccount manager) {
        return new AuthUser(manager.accountId(), academyId, Role.ESCORT, AccountStatus.ACTIVE);
    }
}
