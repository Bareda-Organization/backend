package src.backend.location.proximity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.location.entity.RunPosition;
import src.backend.location.repository.RunPositionRepository;
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
 * {@link ProximityNotificationService#judgeOne} 의 알림 축(NTF-04, 목표 13) — 근접 알림이
 * <b>정확히 최초 1회</b>만 나가고, {@link RiderStatus#ABSENT} 학생은 대상에서 빠지는지 본다
 * (Ruling 207 · C-02).
 *
 * <p>동시 실행 선점(목표 15)은 이 클래스가 아니라 {@link RunStopProximityClaimConcurrencyTest} 가
 * 맡는다 — 이쪽은 위치·거리·라이더 필터링이라는 <b>판정 로직</b>을, 그쪽은 {@code claimProximityNotice}
 * 조건부 UPDATE 라는 <b>동시성 방어</b>를 검사해 검증 대상이 겹치지 않는다.
 */
@SpringBootTest
class ProximityNotificationServiceTest {

    /** 정차지 좌표(37.500000, 127.000000) 기준 약 200m — 300m 문턱 안쪽. */
    private static final String NEAR_LAT = "37.501799";

    /** 같은 정차지 기준 약 400m — 300m 문턱 바깥. */
    private static final String FAR_LAT = "37.503597";

    /** 정차지 좌표 기준 약 50m — 출발 판정 100m 문턱 안쪽(Ruling 307). */
    private static final String WITHIN_DEPARTURE_LAT = "37.500449";

    private static final String STOP_LNG = "127.000000";

    @Autowired
    private ProximityNotificationService proximityNotificationService;

    @Autowired
    private RunPositionStore runPositionStore;

    @MockitoSpyBean
    private ProximityJudge proximityJudge;

    @MockitoSpyBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RunPositionRepository runPositionRepository;

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

    @MockitoSpyBean
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
        reset(proximityJudge, confirmedRouteRepository);
        String academyIds = "(SELECT id FROM academy WHERE name = '근접알림시험학원')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE 'approaching:%'");
        // R15-T3(Ruling 308) — BoardingNotificationListener#appendStopDeparted 가 쓰는 dedup_key.
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE 'stop_departed:%'");
        jdbcTemplate.update("DELETE FROM run_position WHERE run_id IN (SELECT id FROM run WHERE academy_id IN " + academyIds + ")");
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

        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

        // 학생 계정을 연결하지 않아 학부모 몫만 적재된다 — dedup_key 대상 자리는 목표 4 로 "parent"·
        // "student" 로 갈린다(R14).
        // 학부모 몫은 보호자 계정마다 한 행이라 대상 자리가 "parent:<계정>" 이다(BR-073).
        String dedupKey = "approaching:%d:%d:%d:parent:%%".formatted(runId, stopId, studentId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT type, student_id, student_name FROM notification_log WHERE dedup_key LIKE ?", dedupKey);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("type")).isEqualTo("arrive");
        assertThat(proximityNotifiedAt(runStopId)).isNotNull();

        // R13 — arrive 는 studentId 를 들고 있는 단일 학생 이벤트다(docs/archive/rounds/be-rounds-r5-r14.md §8.16 목표 3).
        assertThat(rows.get(0).get("student_id")).as("student_id 가 채워진다").isEqualTo(studentId);
        assertThat(rows.get(0).get("student_name")).as("student_name 이 채워진다").isEqualTo("근접학생1");
    }

    /**
     * R14 목표 1·3·4 — {@code arrive} 문구에 자녀 이름이 실리고(ATT-03), 그 이름이 {@code student_name}
     * 컬럼과 같으며, 학생 본인 계정에도 별도 행이 적재된다(API_SPEC §9.7 수신자 "학부모·학생").
     */
    @Test
    void 근접_알림은_문구에_자녀_이름을_싣고_학부모와_학생_본인_모두에게_적재된다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long studentId = fx.studentWithAccount(academyId, "근접학생4");
        fx.guardianOf(academyId, studentId, "근접학부모4", now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.rider(runId, studentId, stopId, RiderStatus.WAITING, null);

        writePosition(runId, NEAR_LAT, STOP_LNG);

        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT recipient_role, body, student_name FROM notification_log "
                        + "WHERE dedup_key LIKE ? OR dedup_key = ? ORDER BY recipient_role",
                "approaching:%d:%d:%d:parent:%%".formatted(runId, stopId, studentId),
                "approaching:%d:%d:%d:student".formatted(runId, stopId, studentId));
        assertThat(rows).as("학부모·학생 두 행이 각각 적재된다").hasSize(2);
        assertThat(rows).extracting(row -> row.get("recipient_role"))
                .containsExactlyInAnyOrder("parent", "student");
        assertThat(rows).as("문구에 자녀 이름이 실린다(ATT-03)")
                .allSatisfy(row -> assertThat((String) row.get("body")).contains("근접학생4"));
        assertThat(rows).as("문구 속 이름과 student_name 컬럼이 같다(목표 3)")
                .allSatisfy(row -> assertThat((String) row.get("body")).contains((String) row.get("student_name")));
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

        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

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
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);
        // 버스가 문턱 안에 계속 머무는 다음 틱을 흉내낸다 — 재진입 재발송이 없어야 한다(Ruling 207).
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

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

        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

        assertThat(notificationCount(runId, stopId, absentStudentId)).isZero();
        assertThat(notificationCount(runId, stopId, waitingStudentId)).isEqualTo(1);
    }

    // ── 목표 6a(R14-T2, Ruling 307) — 출발 판정: 도착 정차지에서 100m 밖으로 이탈 ──────────

    @Test
    void 도착_정차지에서_100m_밖으로_벗어나면_departed_at이_최초_1회_기록된다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.arriveStop(runStopId, now);

        writePosition(runId, FAR_LAT, STOP_LNG);

        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

        assertThat(departedAt(runStopId)).as("①100m 밖이므로 최초 1회 기록돼야 한다").isNotNull();
    }

    @Test
    void 도착_정차지에서_100m_안쪽이면_departed_at이_기록되지_않는다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.arriveStop(runStopId, now);

        writePosition(runId, WITHIN_DEPARTURE_LAT, STOP_LNG);

        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

        assertThat(departedAt(runStopId)).as("②100m 안쪽이면 아직 출발이 아니다").isNull();
    }

    @Test
    void 출발_판정을_반복해도_두_번째_틱에서는_값이_바뀌지_않는다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.arriveStop(runStopId, now);

        writePosition(runId, FAR_LAT, STOP_LNG);
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);
        OffsetDateTime firstDepartedAt = departedAt(runStopId);

        // 버스가 계속 100m 밖에 머무는 다음 틱을 흉내낸다 — claimDeparture 의 조건부 UPDATE 가
        // 이미 채워진 값을 갱신하지 않아야 한다(③최초 1회, claimProximityNotice 와 같은 근거).
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

        assertThat(departedAt(runStopId)).as("③재판정해도 최초 기록값 그대로여야 한다").isEqualTo(firstDepartedAt);
    }

    // ── docs/archive/rounds/be-rounds-r15-r21.md §8.23 T3 목표 2(Ruling 308) — claimDeparture 가 1행을 갱신한 직후에만 알림이 적재된다 ──

    @Test
    void 출발_판정이_claimDeparture_에_성공하면_그_승하차지의_확정_결과가_통지된다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long studentId = fx.student(academyId, "출발학생1");
        fx.guardianOf(academyId, studentId, "출발보호자1", now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.arriveStop(runStopId, now);
        fx.rider(runId, studentId, stopId, RiderStatus.BOARDED, now);

        // ①100m 안쪽 — claimDeparture 가 아직 0행이므로 알림도 아직 없다. student_name 으로 좁힌다 —
        // notification_log 에는 데모 시드가 만든 다른 'boarding' 행이 이미 있을 수 있다.
        writePosition(runId, WITHIN_DEPARTURE_LAT, STOP_LNG);
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'boarding' AND student_id = ?", Integer.class,
                studentId)).as("①100m 안쪽이면 claimDeparture 가 0행이라 알림도 없다").isZero();

        // ②100m 밖 — claimDeparture 가 1행을 갱신하고 나서야 알림이 적재된다.
        writePosition(runId, FAR_LAT, STOP_LNG);
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'boarding' AND student_id = ?", Integer.class,
                studentId)).as("②claimDeparture 성공 직후 1건 적재").isEqualTo(1);

        // ③재판정 — claimDeparture 가 이미 채워진 값을 다시 갱신하지 않으므로(0행) 알림도 늘지 않는다.
        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'boarding' AND student_id = ?", Integer.class,
                studentId)).as("③재판정해도 여전히 1건").isEqualTo(1);
    }

    /** T1 이 아직 만들지 않은 위치 계약(runId·lat·lng·recordedAt·receivedAt·currentStopName)을 직접 흉내낸다. */
    /**
     * Redis 가 죽은 동안에도 근접 판정은 {@code run_position} 최신 행으로 한다(R46 · Ruling 624) — 그 틱을 건너뛰면 그 사이 기사가 도착
     * 처리해 "다음 미도착" 이 넘어간 정차지는 선점 한 번 못 해 <b>도착 임박 알림이 영구히 빠진다</b>.
     */
    @Test
    void Redis_읽기가_실패해도_run_position_최신_행으로_근접_판정한다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", STOP_LNG);
        long studentId = fx.student(academyId, "근접학생R");
        fx.guardianOf(academyId, studentId, "근접학부모R", now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long runStopId = fx.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fx.rider(runId, studentId, stopId, RiderStatus.WAITING, null);
        runPositionRepository.save(RunPosition.onReceive(runId, new BigDecimal(NEAR_LAT), new BigDecimal(STOP_LNG), now,
                now, null, null));
        Mockito.doThrow(new RedisConnectionFailureException("redis down")).when(stringRedisTemplate).opsForValue();

        ProximityJudging.judge(proximityNotificationService, runPositionStore, runId, academyId);

        assertThat(notificationCount(runId, stopId, studentId)).as("Redis 없이도 도착 임박 알림이 적재된다").isEqualTo(1);
        assertThat(proximityNotifiedAt(runStopId)).isNotNull();
    }

    private void writePosition(long runId, String lat, String lng) {
        String json = """
                {"lat":%s,"lng":%s,"recordedAt":"2030-04-01T00:00:00Z","receivedAt":"2030-04-01T00:00:01Z","currentStopName":"흉내"}
                """.formatted(lat, lng).strip();
        stringRedisTemplate.opsForValue().set("run:%d:position".formatted(runId), json);
    }

    /**
     * BR-235 · R46-LATERBE L5 — 근접·출발은 한 읽기 트랜잭션에서 같이 돌지만 판정별로 격리된다. 근접 판정이 던져도 출발 판정은
     * 기록되고(출발 이탈 정차지의 {@code departed_at}), 출발 판정이 던져도 근접 알림은 적재된다. 실패는 판정마다 한 번씩 {@code onFailure}
     * 로 알려진다. 두 판정이 같은 위치에서 모두 걸리도록 정차지 둘(도착한 1번 · 400m 북쪽 2번)을 두고 버스를 가운데(각각 약 200m)에 둔다.
     */
    @Test
    void 근접_판정이_던져도_출발_판정은_기록되고_출발_판정이_던져도_근접_알림은_적재된다() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ProximityFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long arrivedStop = fx.stop(academyId, "37.500000", STOP_LNG);
        long nextStop = fx.stop(academyId, "37.503599", STOP_LNG);
        long studentId = fx.student(academyId, "격리학생");
        fx.guardianOf(academyId, studentId, "격리학부모", now);
        long runId = fx.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long arrivedRunStop = fx.runStopForStop(versionId, arrivedStop, 1, now.plusMinutes(10));
        fx.runStopForStop(versionId, nextStop, 2, now.plusMinutes(20));
        fx.arriveStop(arrivedRunStop, now);
        fx.rider(runId, studentId, nextStop, RiderStatus.WAITING, null);
        writePosition(runId, NEAR_LAT, STOP_LNG);
        RunPositionRedisValue position = runPositionStore.find(runId).orElseThrow();
        List<ProximityNotificationService.Judgment> failures = new ArrayList<>();

        doThrow(new IllegalStateException("의도적 실패 — 근접")).when(proximityJudge).isWithinThreshold(any(), any());
        proximityNotificationService.judgeRun(runId, academyId, position, (judgment, e) -> failures.add(judgment));
        assertThat(failures).as("근접 판정만 실패로 알려진다").containsExactly(ProximityNotificationService.Judgment.APPROACH);
        assertThat(departedAt(arrivedRunStop)).as("근접 판정이 던져도 출발 판정은 기록된다").isNotNull();
        assertThat(notificationCount(runId, nextStop, studentId)).as("근접 알림은 적재되지 않았다").isZero();

        reset(proximityJudge);
        jdbcTemplate.update("UPDATE run_stop SET departed_at = NULL WHERE id = ?", arrivedRunStop);
        failures.clear();
        doThrow(new IllegalStateException("의도적 실패 — 출발")).when(proximityJudge).hasDeparted(any(), any());
        proximityNotificationService.judgeRun(runId, academyId, position, (judgment, e) -> failures.add(judgment));
        assertThat(failures).as("출발 판정만 실패로 알려진다").containsExactly(ProximityNotificationService.Judgment.DEPARTURE);
        assertThat(departedAt(arrivedRunStop)).as("출발 판정이 던졌으니 기록되지 않는다").isNull();
        assertThat(notificationCount(runId, nextStop, studentId)).as("출발 판정이 던져도 근접 알림은 적재된다").isEqualTo(1);
    }

    /**
     * BR-345 — 읽기 트랜잭션 시작·확정 노선 조회가 실패하면 두 판정 모두 이번 틱에 돌지 못한 것이다. 던지지 않고 근접·출발 각각을 실패로
     * 알려야 스케줄러가 건별 실패로 세고 다음 회차로 넘어간다.
     */
    @Test
    void 읽기_트랜잭션이_실패하면_두_판정_모두_실패로_알리고_던지지_않는다() {
        long runId = 987_654_321L;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RunPositionRedisValue position = new RunPositionRedisValue(new BigDecimal(NEAR_LAT), new BigDecimal(STOP_LNG), now,
                now, null);
        doThrow(new DataAccessResourceFailureException("db down")).when(confirmedRouteRepository).findById(runId);
        List<ProximityNotificationService.Judgment> failures = new ArrayList<>();

        proximityNotificationService.judgeRun(runId, 1L, position, (judgment, e) -> failures.add(judgment));

        assertThat(failures).containsExactlyInAnyOrder(ProximityNotificationService.Judgment.APPROACH,
                ProximityNotificationService.Judgment.DEPARTURE);
    }

    private int notificationCount(long runId, long stopId, long studentId) {
        String dedupKey = "approaching:%d:%d:%d:parent:%%".formatted(runId, stopId, studentId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE dedup_key LIKE ?", Integer.class, dedupKey);
        return count == null ? 0 : count;
    }

    private OffsetDateTime proximityNotifiedAt(long runStopId) {
        return jdbcTemplate.queryForObject(
                "SELECT proximity_notified_at FROM run_stop WHERE id = ?", OffsetDateTime.class, runStopId);
    }

    private OffsetDateTime departedAt(long runStopId) {
        return jdbcTemplate.queryForObject(
                "SELECT departed_at FROM run_stop WHERE id = ?", OffsetDateTime.class, runStopId);
    }
}
