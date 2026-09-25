package src.backend.student.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 버스 간 이동으로 빠진 회차(명단의 {@code change=removed} 행)는 그 학생의 회차가 아니다(BR-016) — 명단 행이 있기만 하면
 * 속한다고 보면 학부모 앱이 옛 버스를 오늘 회차로 고른다.
 */
@SpringBootTest
@Transactional
class StudentRunResolverTransferTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1);

    @Autowired
    private StudentRunResolver resolver;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 이동으로_빠진_회차는_그_학생의_회차가_아니다() {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
        long academyId = fixtures.academyWithCoordinates();
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long studentId = fixtures.student(academyId, "이동학생");
        OffsetDateTime depart = SERVICE_DATE.atTime(8, 0).atOffset(ZoneOffset.ofHours(9));
        long fromRunId = fixtures.idleRun(academyId, fixtures.bus(academyId), SERVICE_DATE, Direction.TO_ACADEMY,
                depart, depart.minusMinutes(30));
        long toRunId = fixtures.idleRun(academyId, fixtures.bus(academyId), SERVICE_DATE, Direction.TO_ACADEMY,
                depart, depart.minusMinutes(30));
        jdbcTemplate.update("UPDATE run SET status = 'confirmed' WHERE id IN (?, ?)", fromRunId, toRunId);
        jdbcTemplate.update("INSERT INTO run_rider (run_id, student_id, stop_id, status, change) "
                + "VALUES (?, ?, ?, 'absent', 'removed')", fromRunId, studentId, stopId);
        jdbcTemplate.update("INSERT INTO run_rider (run_id, student_id, stop_id, status, change) "
                + "VALUES (?, ?, ?, 'waiting', 'added')", toRunId, studentId, stopId);
        entityManager.clear();

        assertThat(resolver.resolveAllByDate(academyId, studentId, SERVICE_DATE)).extracting(Run::getId)
                .containsExactly(toRunId);
    }
}
