package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.dto.RunCreateRequest;
import testsupport.clock.FixedClock20300401Config;

/**
 * 관계자의 임시 회차 추가(SCH-03, API_SPEC §5.10) — 운행일이 오늘(Asia/Seoul)보다 앞이면 {@code 422 VALIDATION_FAILED} 다. 지난 날짜의 {@code idle} 회차는 확정 배치가 집지 않아 영원히
 * 남는다. 오늘 날짜에 이미 지난 출발 시각은 그대로 받는다 — 오늘의 구간 규칙이 그 뒤를 맡는다.
 */
@SpringBootTest
@Transactional
@Import(FixedClock20300401Config.class)
class RunAddServiceDateTest {

    @Autowired private ApplicationContext applicationContext;
    @Autowired private RunCommandService runCommandService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("어제 운행일의 회차 추가는 422 VALIDATION_FAILED 이고 회차가 만들어지지 않는다")
    void 지난_운행일은_거절된다() {
        Setup s = 준비한다();

        assertThatThrownBy(() -> runCommandService.add(s.staff, 요청(s.busId, "2030-03-31", "09:00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(회차_수(s.busId)).isZero();
    }

    @Test
    @DisplayName("오늘 운행일에 이미 지난 출발 시각(08:00, 지금 12:00)은 그대로 받는다")
    void 오늘의_지난_출발_시각은_받는다() {
        Setup s = 준비한다();

        runCommandService.add(s.staff, 요청(s.busId, "2030-04-01", "08:00"));

        assertThat(회차_수(s.busId)).isEqualTo(1);
    }

    @Test
    @DisplayName("내일 운행일은 받는다")
    void 내일_운행일은_받는다() {
        Setup s = 준비한다();

        runCommandService.add(s.staff, 요청(s.busId, "2030-04-02", "08:00"));

        assertThat(회차_수(s.busId)).isEqualTo(1);
    }

    private int 회차_수(long busId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE bus_id = ?", Integer.class, busId);
    }

    private RunCreateRequest 요청(long busId, String serviceDate, String departTime) {
        return new RunCreateRequest(busId, serviceDate, "to_academy", departTime, "출발지", "도착지", null);
    }

    private Setup 준비한다() {
        DriverRunFixtures fixtures = DriverRunFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        return new Setup(busId, new AuthUser(1L, academyId, Role.STAFF, AccountStatus.ACTIVE));
    }

    private record Setup(long busId, AuthUser staff) {
    }
}
