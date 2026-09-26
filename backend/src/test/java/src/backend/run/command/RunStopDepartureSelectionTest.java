package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.location.proximity.ProximityFixtures;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@link RunStopRepository#findFirstArrivedNotDeparted} 의 대상 선정(R17-T1 목표 2, Ruling 307) —
 * 신호 유실로 도착·미출발 정차지가 여러 개 쌓였을 때 ①{@code seq} 최솟값 1건만 선정되고
 * ②{@link StopDepartureService#claimAndPublish} 로 그 1건을 처리하면 다음 틱은 그다음
 * {@code seq} 를 순서대로 선정하는지 본다.
 *
 * <p>{@link ProximityNotificationServiceTest#도착_정차지에서_100m_밖으로_벗어나면_departed_at이_최초_1회_기록된다}
 * 등은 정차지 1건짜리라 이 누락(여러 건 중 어느 것을 고르는가)을 보지 못한다 — 이 클래스는 거리 판정을
 * 거치지 않고 저장소·{@code StopDepartureService} 를 직접 불러 선정 순서만 좁혀서 본다.
 */
@SpringBootTest
class RunStopDepartureSelectionTest {

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private StopDepartureService stopDepartureService;

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
    void 도착_미출발이_여러_건_쌓이면_seq_최솟값_1건만_선정되고_다음_틱은_그_다음_seq를_고른다() {
        OffsetDateTime now = OffsetDateTime.now();
        ProximityFixtures fx = new ProximityFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);

        // 삽입 순서(seq 3 → 1 → 2)가 seq 오름차순과 어긋나게 만든다 — id 순서로 우연히 통과하는 것을
        // 막는다. 세 곳 모두 신호 유실로 도착만 기록되고 출발이 밀린 상황을 흉내낸다.
        long stopA = fx.stop(academyId, "37.500000", "127.000000");
        long stopB = fx.stop(academyId, "37.510000", "127.010000");
        long stopC = fx.stop(academyId, "37.520000", "127.020000");
        long runStopSeq3 = fx.runStopForStop(versionId, stopA, 3, now.plusMinutes(30));
        long runStopSeq1 = fx.runStopForStop(versionId, stopB, 1, now.plusMinutes(10));
        long runStopSeq2 = fx.runStopForStop(versionId, stopC, 2, now.plusMinutes(20));
        fx.arriveStop(runStopSeq3, now);
        fx.arriveStop(runStopSeq1, now);
        fx.arriveStop(runStopSeq2, now);

        List<RunStop> 첫번째_틱_대상 = runStopRepository.findFirstArrivedNotDeparted(versionId, PageRequest.of(0, 1));
        assertThat(첫번째_틱_대상).as("①seq 최솟값(1) 1건만 대상이어야 한다 — 개수가 아니라 어느 id 인지로 본다")
                .extracting(RunStop::getId).containsExactly(runStopSeq1);

        stopDepartureService.claimAndPublish(첫번째_틱_대상.get(0), runId, academyId, now.plusMinutes(1));

        List<RunStop> 두번째_틱_대상 = runStopRepository.findFirstArrivedNotDeparted(versionId, PageRequest.of(0, 1));
        assertThat(두번째_틱_대상).as("②seq 1이 처리됐으니 다음은 seq 2여야 한다").extracting(RunStop::getId)
                .containsExactly(runStopSeq2);

        stopDepartureService.claimAndPublish(두번째_틱_대상.get(0), runId, academyId, now.plusMinutes(2));

        List<RunStop> 세번째_틱_대상 = runStopRepository.findFirstArrivedNotDeparted(versionId, PageRequest.of(0, 1));
        assertThat(세번째_틱_대상).as("③그다음은 seq 3이어야 한다").extracting(RunStop::getId).containsExactly(runStopSeq3);

        stopDepartureService.claimAndPublish(세번째_틱_대상.get(0), runId, academyId, now.plusMinutes(3));

        assertThat(runStopRepository.findFirstArrivedNotDeparted(versionId, PageRequest.of(0, 1)))
                .as("④셋 다 처리됐으면 더 이상 대상이 없어야 한다").isEmpty();
    }
}
