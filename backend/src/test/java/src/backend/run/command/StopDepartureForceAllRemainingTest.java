package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.location.proximity.ProximityFixtures;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@link StopDepartureService#forceAllRemaining}(R17-T1 목표 3, Ruling 312) — 도착·미출발 정차지가
 * 여러 건 쌓인 채로 운행이 종료되면 <b>전부</b> 해소되는지 본다. {@code ProximityNotificationServiceTest}
 * 의 출발 판정 검사는 전부 정차지 1건짜리라, {@code forceAllRemaining} 이 반복문 첫 건만 처리하고
 * 나머지를 빠뜨려도(예: {@code return} 을 {@code for} 안에 잘못 넣는 실수) 그 결함을 잡지 못한다.
 */
@SpringBootTest
class StopDepartureForceAllRemainingTest {

    @Autowired
    private StopDepartureService stopDepartureService;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 뒷정리한다() {
        String academyIds = "(SELECT id FROM academy WHERE name = '근접알림시험학원')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE 'stop_departed:%'");
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '근접알림시험학원'");
    }

    @Test
    void 도착_미출발_정차지_세_건이_forceAllRemaining_한_번으로_전부_해소된다() {
        OffsetDateTime now = OffsetDateTime.now();
        ProximityFixtures fx = new ProximityFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);

        long stopA = fx.stop(academyId, "37.500000", "127.000000");
        long stopB = fx.stop(academyId, "37.510000", "127.010000");
        long stopC = fx.stop(academyId, "37.520000", "127.020000");
        long runStop1 = fx.runStopForStop(versionId, stopA, 1, now.plusMinutes(10));
        long runStop2 = fx.runStopForStop(versionId, stopB, 2, now.plusMinutes(20));
        long runStop3 = fx.runStopForStop(versionId, stopC, 3, now.plusMinutes(30));
        fx.arriveStop(runStop1, now);
        fx.arriveStop(runStop2, now);
        fx.arriveStop(runStop3, now);

        assertThat(runStopRepository.findAllArrivedNotDeparted(versionId)).as("사전 조건 — 셋 다 아직 미출발")
                .hasSize(3);

        stopDepartureService.forceAllRemaining(runId, academyId, now.plusHours(1));

        assertThat(runStopRepository.findAllArrivedNotDeparted(versionId)).as("한 번 호출로 대기열이 비어야 한다")
                .isEmpty();
        assertThat(runStopRepository.findById(runStop1).orElseThrow().getDepartedAt())
                .as("①seq 1 개별 확인").isNotNull();
        assertThat(runStopRepository.findById(runStop2).orElseThrow().getDepartedAt())
                .as("②seq 2 개별 확인 — 반복문이 첫 건만 처리하는 결함이면 여기서 null 로 남는다").isNotNull();
        assertThat(runStopRepository.findById(runStop3).orElseThrow().getDepartedAt())
                .as("③seq 3 개별 확인").isNotNull();
    }
}
