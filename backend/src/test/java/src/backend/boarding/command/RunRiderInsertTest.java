package src.backend.boarding.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.db.ConnectionCountingConfig;

/**
 * DA-8(Ruling 899) — 회차 확정 때 탑승자 {@code run_rider} 저장은 INSERT 한 문장이다. IDENTITY 키라 엔티티 저장은 학생 수만큼 INSERT 를 따로
 * 내(학생 30명이면 30문장) 확정 트랜잭션이 연결을 오래 쥐었다.
 */
@SpringBootTest
@Transactional
@Import({FixedClock20300401Config.class, ConnectionCountingConfig.class})
class RunRiderInsertTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1);

    @Autowired private RunConfirmationService confirmationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private WeeklyAddressRepository weeklyAddressRepository;
    @Autowired private RunRepository runRepository;

    @Test
    @DisplayName("DA-8 — 학생 30명 회차를 확정하면 run_rider 저장은 INSERT 한 문장이고 30행이 모두 들어간다")
    void 탑승자_저장은_INSERT_한_문장이다() {
        RunConfirmationFixtures fx = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        for (int i = 0; i < 30; i++) {
            long studentId = fx.student(academyId, "학생" + i);
            fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        }
        OffsetDateTime departTime = OffsetDateTime.parse("2030-04-01T15:00:00+09:00");
        long runId = fx.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        ConnectionCountingConfig.reset();
        confirmationService.confirmOne(runId);

        List<String> inserts = ConnectionCountingConfig.preparedStatements().stream()
                .filter(sql -> sql.startsWith("insert into run_rider")).toList();
        assertThat(inserts).as("run_rider INSERT 문장").hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run_rider WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(30);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run_rider WHERE run_id = ? AND status = 'waiting' AND created_at IS NOT NULL",
                Integer.class, runId)).isEqualTo(30);
    }
}
