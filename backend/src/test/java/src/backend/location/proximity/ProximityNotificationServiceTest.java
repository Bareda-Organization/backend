package src.backend.location.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
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
import testsupport.redis.RedisTestContainerBase;

/**
 * {@link ProximityNotificationService#judgeOne} 의 알림 축(NTF-04, 목표 13) — 근접 알림이
 * <b>정확히 최초 1회</b>만 나가고, {@link RiderStatus#ABSENT} 학생은 대상에서 빠지는지 본다
 * (Ruling 207 · C-02).
 *
 * <p>동시 실행 선점(목표 15)은 이 클래스가 아니라 {@link RunStopProximityClaimConcurrencyTest} 가
 * 맡는다 — 이쪽은 위치·거리·라이더 필터링이라는 <b>판정 로직</b>을, 그쪽은 {@code claimProximityNotice}
 * 조건부 UPDATE 라는 <b>동시성 방어</b>를 검사해 검증 대상이 겹치지 않는다.
 */
@SpringBootTest
class ProximityNotificationServiceTest extends RedisTestContainerBase {

    /** 정차지 좌표(37.500000, 127.000000) 기준 약 200m — 300m 문턱 안쪽. */
    private static final String NEAR_LAT = "37.501799";

    /** 같은 정차지 기준 약 400m — 300m 문턱 바깥. */
    private static final String FAR_LAT = "37.503597";

    private static final String STOP_LNG = "127.000000";

    @Autowired
    private ProximityNotificationService proximityNotificationService;

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

    /**
     * {@code run} 삭제가 {@code confirmed_route}·{@code route_version}·{@code run_stop}·
     * {@code run_rider} 를 CASCADE 로 함께 지운다(V1 스키마). {@code guardian} 삭제는
     * {@code guardian_student} 를 CASCADE 로 지운다. 나머지는 {@code academy} 로 향하는 FK 가
     * {@code RESTRICT} 라 이 순서를 지켜야 한다 — {@code notification_log} 는 FK 자체가 없어
     * (ERD §4.2, 보존 14일 자립 설계) 순서와 무관하게 지운다.
     */
    @AfterEach
    void 뒷정리한다() {
        String academyIds = "(SELECT id FROM academy WHERE name = '근접알림시험학원')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE 'approaching:%'");
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM guardian WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '근접알림시험학원'");
    }

    private ProximityFixtures fixtures() {
        return new ProximityFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository);
    }

    @Test
    void 문턱_300m_안으로_처음_들어오면_알림이_1건_적재된다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long studentId = fx.student(academyId, "근접학생1");
        fx.guardianOf(academyId, studentId, "근접학부모1", now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.rider(runId, studentId, stopId, RiderStatus.WAITING, null);

        writePosition(runId, NEAR_LAT, STOP_LNG);

        proximityNotificationService.judgeOne(runId, academyId);

        String dedupKey = "approaching:%d:%d:%d".formatted(runId, stopId, studentId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT type FROM notification_log WHERE dedup_key = ?", dedupKey);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("type")).isEqualTo("arrive");
        assertThat(proximityNotifiedAt(runStopId)).isNotNull();
    }

    @Test
    void 문턱_300m_밖이면_알림이_적재되지_않는다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long studentId = fx.student(academyId, "근접학생2");
        fx.guardianOf(academyId, studentId, "근접학부모2", now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.rider(runId, studentId, stopId, RiderStatus.WAITING, null);

        writePosition(runId, FAR_LAT, STOP_LNG);

        proximityNotificationService.judgeOne(runId, academyId);

        assertThat(notificationCount(runId, stopId, studentId)).isZero();
        assertThat(proximityNotifiedAt(runStopId)).isNull();
    }

    @Test
    void 재진입해도_두_번째_틱에서는_다시_적재되지_않는다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long studentId = fx.student(academyId, "근접학생3");
        fx.guardianOf(academyId, studentId, "근접학부모3", now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.rider(runId, studentId, stopId, RiderStatus.WAITING, null);

        writePosition(runId, NEAR_LAT, STOP_LNG);
        proximityNotificationService.judgeOne(runId, academyId);
        // 버스가 문턱 안에 계속 머무는 다음 틱을 흉내낸다 — 재진입 재발송이 없어야 한다(Ruling 207).
        proximityNotificationService.judgeOne(runId, academyId);

        assertThat(notificationCount(runId, stopId, studentId)).isEqualTo(1);
    }

    @Test
    void ABSENT_학생은_알림_대상에서_빠진다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long absentStudentId = fx.student(academyId, "결석학생");
        long waitingStudentId = fx.student(academyId, "대기학생");
        fx.guardianOf(academyId, absentStudentId, "결석보호자", now);
        fx.guardianOf(academyId, waitingStudentId, "대기보호자", now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.rider(runId, absentStudentId, stopId, RiderStatus.ABSENT, now);
        fx.rider(runId, waitingStudentId, stopId, RiderStatus.WAITING, null);

        writePosition(runId, NEAR_LAT, STOP_LNG);

        proximityNotificationService.judgeOne(runId, academyId);

        assertThat(notificationCount(runId, stopId, absentStudentId)).isZero();
        assertThat(notificationCount(runId, stopId, waitingStudentId)).isEqualTo(1);
    }

    /** T1 이 아직 만들지 않은 위치 계약(runId·lat·lng·recordedAt·receivedAt·currentStopName)을 직접 흉내낸다. */
    private void writePosition(long runId, String lat, String lng) {
        String json = """
                {"lat":%s,"lng":%s,"recordedAt":"2030-04-01T00:00:00Z","receivedAt":"2030-04-01T00:00:01Z","currentStopName":"흉내"}
                """.formatted(lat, lng).strip();
        stringRedisTemplate.opsForValue().set("run:%d:position".formatted(runId), json);
    }

    private int notificationCount(long runId, long stopId, long studentId) {
        String dedupKey = "approaching:%d:%d:%d".formatted(runId, stopId, studentId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE dedup_key = ?", Integer.class, dedupKey);
        return count == null ? 0 : count;
    }

    private OffsetDateTime proximityNotifiedAt(long runStopId) {
        return jdbcTemplate.queryForObject(
                "SELECT proximity_notified_at FROM run_stop WHERE id = ?", OffsetDateTime.class, runStopId);
    }
}
