package src.backend.location.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.location.event.StopDepartedEvent;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@link StopDepartureService#forceAllRemaining} 의 <b>이벤트 발행 축</b>(R17-T1b 공백 1, Ruling 312) —
 * {@link StopDepartureForceAllRemainingTest} 는 {@code departed_at} 컬럼만 보므로, 누가
 * {@link StopDepartureService#claimAndPublish} 대신 벌크 UPDATE 로 바꿔 컬럼은 채우면서
 * {@link StopDepartedEvent} 발행을 빠뜨려도(학부모 알림 누락) 잡지 못한다 — 이 클래스가 그 축을 메운다.
 *
 * <p>이미 선점된 정차지 1건을 섞어 두어, {@code forceAllRemaining} 이 그 건을 대상에서 아예 빼는지
 * (재발행하지 않는지)도 같이 본다 — {@link RunStopRepository#findAllArrivedNotDeparted} 의
 * {@code departedAt IS NULL} 조건이 실제로 이벤트 발행 단계까지 지켜지는지가 관건이다.
 */
@SpringBootTest
@RecordApplicationEvents
class StopDepartureForceAllRemainingEventsTest {

    @Autowired
    private StopDepartureService stopDepartureService;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private ApplicationEvents applicationEvents;

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
    void forceAllRemaining이_남은_세_건마다_StopDepartedEvent를_정확히_한_번씩_발행하고_이미_선점된_건은_건너뛴다() {
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
        long alreadyDepartedStop = fx.stop(academyId, "37.530000", "127.030000");
        long runStopA = fx.runStopForStop(versionId, stopA, 1, now.plusMinutes(10));
        long runStopB = fx.runStopForStop(versionId, stopB, 2, now.plusMinutes(20));
        long runStopC = fx.runStopForStop(versionId, stopC, 3, now.plusMinutes(30));
        long runStopAlreadyDeparted = fx.runStopForStop(versionId, alreadyDepartedStop, 4, now.plusMinutes(40));
        fx.arriveStop(runStopA, now);
        fx.arriveStop(runStopB, now);
        fx.arriveStop(runStopC, now);
        // 이미 다른 경로(예: judgeDeparture 의 100m 이탈 판정)가 먼저 선점한 상황을 흉내낸다 —
        // 도착 처리는 됐지만 findAllArrivedNotDeparted 조건(departedAt IS NULL)에서 이미 빠져 있다.
        fx.arriveStop(runStopAlreadyDeparted, now);
        runStopRepository.claimDeparture(runStopAlreadyDeparted, now);

        stopDepartureService.forceAllRemaining(runId, academyId, now.plusHours(1));

        List<StopDepartedEvent> events = applicationEvents.stream(StopDepartedEvent.class).toList();
        assertThat(events).as("①남은 세 건마다 정확히 하나씩 — 개수가 아니라 stopId 집합으로 본다")
                .extracting(StopDepartedEvent::stopId).containsExactlyInAnyOrder(stopA, stopB, stopC);
        assertThat(events).as("②이미 선점된 정차지의 stopId 는 섞여 있으면 안 된다(재발행 금지)")
                .extracting(StopDepartedEvent::stopId).doesNotContain(alreadyDepartedStop);
    }
}
