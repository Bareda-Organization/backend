package src.backend.location.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.location.infrastructure.RunPositionStore;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

import testsupport.location.ProximityJudging;

/**
 * {@link ProximityNotificationService#judgeDeparture} 의 <b>선정과 거리 판정의 결합</b>(R17-T1b 공백
 * 2, Ruling 307) — {@code judgeDeparture} 는 {@link RunStopRepository#findFirstArrivedNotDeparted}
 * 가 고른 정차지의 {@code stopId} 로 좌표를 다시 조회해 그 좌표로 거리 판정을 한다. 정차지 1건짜리
 * 시험({@link ProximityNotificationServiceTest})과 저장소만 보는 선정 시험
 * ({@link RunStopDepartureSelectionTest})은 각자 따로 통과해도, 그 사이의 결합 — "고른 id 의 좌표를
 * 정확히 쓰는가" — 은 서로 못 잡는다. 이 클래스는 세 정차지를 서로 멀리 떨어뜨려 두고, 매 틱 버스
 * 위치를 그때그때 대상이어야 할 정차지의 100m 밖에 두어 종단 간으로 확인한다.
 */
@SpringBootTest
class ProximityNotificationServiceDepartureSelectionTest {

    /** 정차지 3곳을 서로 1km 이상 떨어뜨려 둔다 — 엉뚱한 정차지 좌표로 판정해도 우연히 통과하지 않게. */
    private static final String STOP_A_LAT = "37.500000";

    private static final String STOP_B_LAT = "37.510000";

    private static final String STOP_C_LAT = "37.520000";

    private static final String STOP_A_LNG = "127.000000";

    private static final String STOP_B_LNG = "127.010000";

    private static final String STOP_C_LNG = "127.020000";

    @Autowired
    private ProximityNotificationService proximityNotificationService;

    @Autowired
    private RunPositionStore runPositionStore;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

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
    void 도착_미출발이_여러_건_쌓인_상태에서_judgeDeparture가_seq_최솟값의_좌표로_판정하고_다음_틱은_그_다음_seq의_좌표로_판정한다() {
        OffsetDateTime now = OffsetDateTime.now();
        ProximityFixtures fx = new ProximityFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);

        // 삽입 순서(seq 3 → 1 → 2)를 seq 오름차순과 어긋나게 만든다 — RunStopDepartureSelectionTest 와
        // 같은 근거, id 순서로 우연히 통과하는 것을 막는다.
        long stopA = fx.stop(academyId, STOP_A_LAT, STOP_A_LNG);
        long stopB = fx.stop(academyId, STOP_B_LAT, STOP_B_LNG);
        long stopC = fx.stop(academyId, STOP_C_LAT, STOP_C_LNG);
        long runStopSeq3 = fx.runStopForStop(versionId, stopA, 3, now.plusMinutes(30));
        long runStopSeq1 = fx.runStopForStop(versionId, stopB, 1, now.plusMinutes(10));
        long runStopSeq2 = fx.runStopForStop(versionId, stopC, 2, now.plusMinutes(20));
        fx.arriveStop(runStopSeq3, now);
        fx.arriveStop(runStopSeq1, now);
        fx.arriveStop(runStopSeq2, now);
        fx.observeNear(runStopSeq3, now);
        fx.observeNear(runStopSeq1, now);
        fx.observeNear(runStopSeq2, now);

        // ①틱1 — 버스를 seq 1(stopB) 기준 100m 밖에 둔다. 정차지끼리 1km+ 떨어져 있으므로, 만약
        // 코드가 seq 최솟값이 아닌 다른 정차지(stopA·stopC)의 좌표로 판정했다면 그 좌표는 이 버스
        // 위치에서 훨씬 멀리 떨어져 있어 결과가 우연히 맞아떨어질 수는 있어도, 어느 id 의 departed_at
        // 이 채워졌는지를 직접 확인하므로 잘못 고른 id 가 채워지면 바로 드러난다.
        writePosition(runId, "37.501800", STOP_B_LNG);
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);
        assertThat(departedAt(runStopSeq1)).as("①seq 최솟값(1, stopB)의 좌표로 판정해 출발이 기록돼야 한다").isNotNull();
        assertThat(departedAt(runStopSeq2)).as("①아직 seq 2는 대상이 아니다").isNull();
        assertThat(departedAt(runStopSeq3)).as("①아직 seq 3은 대상이 아니다").isNull();

        // ②틱2 — seq 1이 해소됐으니 다음 대상은 seq 2(stopC)다. 버스를 stopC 기준 100m 밖으로 옮긴다.
        writePosition(runId, "37.511800", STOP_C_LNG);
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);
        assertThat(departedAt(runStopSeq2)).as("②seq 2(stopC)의 좌표로 판정해 출발이 기록돼야 한다").isNotNull();
        assertThat(departedAt(runStopSeq3)).as("②아직 seq 3은 대상이 아니다").isNull();

        // ③틱3 — 마지막 seq 3(stopA).
        writePosition(runId, "37.521800", STOP_A_LNG);
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);
        assertThat(departedAt(runStopSeq3)).as("③seq 3(stopA)의 좌표로 판정해 출발이 기록돼야 한다").isNotNull();
    }

    private void writePosition(long runId, String lat, String lng) {
        String json = """
                {"lat":%s,"lng":%s,"recordedAt":"2030-04-01T00:00:00Z","receivedAt":"2030-04-01T00:00:01Z","currentStopName":"흉내"}
                """.formatted(lat, lng).strip();
        stringRedisTemplate.opsForValue().set("run:%d:position".formatted(runId), json);
    }

    private OffsetDateTime departedAt(long runStopId) {
        return jdbcTemplate.queryForObject(
                "SELECT departed_at FROM run_stop WHERE id = ?", OffsetDateTime.class, runStopId);
    }
}
