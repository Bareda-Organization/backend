package src.backend.location.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.StopDepartureService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

import testsupport.location.ProximityJudging;

/**
 * Ruling 875 — 출발 판정은 도착 처리 <b>뒤</b> 그 승하차지 100m <b>안</b>에서 받은 위치가 1회 이상 있은 다음, 100m 밖 위치를 받을 때다.
 * 진입 직전(100m 밖)에 도착을 눌러도 다음 위치에서 바로 출발로 판정돼 그 승하차지의 승하차·미승차 학부모 알림이 나가지 않고 되돌리기도
 * 막히던 것을 고정한다. 시각은 고정값으로 두고 위치의 {@code receivedAt} 순서만 바꾼다.
 */
@SpringBootTest
class ProximityDepartureObservationTest {

    /** 도착 처리 시각 — 위치의 {@code receivedAt} 이 이보다 뒤인지 앞인지가 "도착 처리 뒤 받은 위치" 의 기준이다. */
    private static final OffsetDateTime ARRIVED_AT = OffsetDateTime.parse("2030-04-01T00:00:00Z");

    private static final String STOP_LNG = "127.000000";

    /** 정차지(37.500000) 기준 약 50m — 출발 판정 100m 안. */
    private static final String INSIDE_LAT = "37.500449";

    /** 같은 정차지 기준 약 150m — 100m 밖(진입 직전에 도착을 누른 자리). */
    private static final String OUTSIDE_LAT = "37.501349";

    @Autowired
    private ProximityNotificationService proximityNotificationService;

    @Autowired
    private StopDepartureService stopDepartureService;

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
        jdbcTemplate.update("DELETE FROM guardian WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '근접알림시험학원'");
    }

    /** 진입 직전(150m 밖)에 도착을 눌렀다 — 그 뒤 위치가 계속 100m 밖이어도 출발로 보지 않는다(이전에는 다음 틱에 바로 출발). */
    @Test
    void 도착_뒤_100m_안_위치가_없으면_100m_밖_위치만으로는_출발이_아니다() {
        Scenario s = scenario();

        writePosition(s.runId, OUTSIDE_LAT, ARRIVED_AT.plusSeconds(1));
        judge(s);
        writePosition(s.runId, OUTSIDE_LAT, ARRIVED_AT.plusSeconds(31));
        judge(s);

        assertThat(departedAt(s.runStopId)).as("100m 안을 한 번도 못 봤으니 출발이 아니다").isNull();
    }

    /** 도착 뒤 100m 안 위치를 한 번 받으면 그 다음 100m 밖 위치에서 출발로 판정된다. */
    @Test
    void 도착_뒤_100m_안_위치를_받은_다음_100m_밖_위치를_받으면_출발이다() {
        Scenario s = scenario();

        writePosition(s.runId, INSIDE_LAT, ARRIVED_AT.plusSeconds(1));
        judge(s);
        assertThat(departedAt(s.runStopId)).as("안쪽 관측만으로는 아직 출발이 아니다").isNull();
        writePosition(s.runId, OUTSIDE_LAT, ARRIVED_AT.plusSeconds(31));
        judge(s);

        assertThat(departedAt(s.runStopId)).as("안쪽을 본 뒤 바깥 위치").isNotNull();
    }

    /** 도착 처리보다 <b>앞서</b> 받은 100m 안 위치(Redis 에 남은 옛 위치)는 관측으로 세지 않는다. */
    @Test
    void 도착_처리_앞서_받은_100m_안_위치는_관측으로_세지_않는다() {
        Scenario s = scenario();

        writePosition(s.runId, INSIDE_LAT, ARRIVED_AT.minusSeconds(10));
        judge(s);
        writePosition(s.runId, OUTSIDE_LAT, ARRIVED_AT.plusSeconds(31));
        judge(s);

        assertThat(departedAt(s.runStopId)).as("도착 처리 전 위치는 '도착 뒤' 관측이 아니다").isNull();
    }

    /**
     * 도착 뒤 100m 안 관측이 끝내 없으면 다음 승하차지 도착 때의 강제 발송(Ruling 854 · R51 H2)이 앞 승하차지를 출발 처리한다 — 이 판정이
     * 출발을 못 잡아도 알림이 영영 빠지지 않는다.
     */
    @Test
    void 안쪽_관측이_끝내_없으면_다음_승하차지_도착의_강제_발송이_출발_처리한다() {
        Scenario s = scenario();
        writePosition(s.runId, OUTSIDE_LAT, ARRIVED_AT.plusSeconds(1));
        judge(s);
        assertThat(departedAt(s.runStopId)).isNull();

        stopDepartureService.forceAllBefore(s.runId, s.academyId, s.nextRunStopId, ARRIVED_AT.plusMinutes(5));

        assertThat(departedAt(s.runStopId)).as("다음 승하차지 도착이 앞 승하차지를 출발 처리").isNotNull();
    }

    /** 운행 종료의 강제 발송도 같다. */
    @Test
    void 안쪽_관측이_끝내_없으면_운행_종료의_강제_발송이_출발_처리한다() {
        Scenario s = scenario();
        writePosition(s.runId, OUTSIDE_LAT, ARRIVED_AT.plusSeconds(1));
        judge(s);

        stopDepartureService.forceAllRemaining(s.runId, s.academyId, ARRIVED_AT.plusMinutes(10));

        assertThat(departedAt(s.runStopId)).as("운행 종료가 남은 승하차지를 출발 처리").isNotNull();
    }

    private record Scenario(long academyId, long runId, long runStopId, long nextRunStopId) {
    }

    /** 움직이는 회차 1건 — 첫 승하차지(37.5)는 도착 처리됐고, 둘째 승하차지(37.52)는 아직 안 갔다. */
    private Scenario scenario() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = new ProximityFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long firstStop = fx.stop(academyId, "37.500000", STOP_LNG);
        long secondStop = fx.stop(academyId, "37.520000", STOP_LNG);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, firstStop, 1, now.plusMinutes(10));
        long nextRunStopId = fx.runStopForStop(versionId, secondStop, 2, now.plusMinutes(20));
        fx.arriveStop(runStopId, ARRIVED_AT);
        return new Scenario(academyId, runId, runStopId, nextRunStopId);
    }

    private void judge(Scenario s) {
        ProximityJudging.judge(proximityNotificationService, runPositionStore, s.runId, s.academyId);
    }

    /** 위치 한 건을 Redis 에 쓴다 — {@code receivedAt} 으로 "도착 처리 앞/뒤" 를 고정한다. */
    private void writePosition(long runId, String lat, OffsetDateTime receivedAt) {
        String json = """
                {"lat":%s,"lng":%s,"recordedAt":"%s","receivedAt":"%s","currentStopName":"흉내"}
                """.formatted(lat, STOP_LNG, receivedAt, receivedAt).strip();
        stringRedisTemplate.opsForValue().set("run:%d:position".formatted(runId), json);
    }

    private OffsetDateTime departedAt(long runStopId) {
        return jdbcTemplate.queryForObject("SELECT departed_at FROM run_stop WHERE id = ?", OffsetDateTime.class,
                runStopId);
    }
}
