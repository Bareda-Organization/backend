package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.entity.RunStatus;
import src.backend.run.entity.RunTransfer;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.run.repository.RunTransferRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 확정 배치 오케스트레이터({@link RunConfirmationService#confirmOne})의 <b>내용물</b> 검증
 * (Phase 7 목표 1 · 5) — 스케줄러의 판정 시각 대상 선정은 {@code RunConfirmationSchedulerTest}(목표
 * 3 · 4 · 6)가 맡고, 이 클래스는 회차 1건을 직접 확정했을 때 4종 산출물이 정확한 값으로 남는지만 본다.
 *
 * <p>{@code @Transactional} 을 쓴다({@code RouteComputationPipelineTest} 와 같은 근거) —
 * {@link #confirmOne} 을 메인 스레드에서 직접 부르므로(스케줄러의 비동기 실행 스레드를 거치지 않는다)
 * 테스트 트랜잭션이 이 호출이 여는 모든 자원을 그대로 감싸고, 종료 시 롤백이 뒷정리를 대신한다.
 */
@SpringBootTest
@Transactional
class RunConfirmationServiceTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    @Autowired
    private RunConfirmationService confirmationService;

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
    private RunForcedAdditionRepository runForcedAdditionRepository;

    @Autowired
    private RunTransferRepository runTransferRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private RunConfirmationFixtures fixtures;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private RunConfirmationFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return fixtures;
    }

    @Test
    @DisplayName("목표1 — idle 회차를 확정하면 4종 산출물이 정확한 값으로 남는다")
    void 회차를_확정하면_4종_산출물이_전부_생긴다() {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures().stop(academyId, "37.561000", "126.971000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);

        long studentAtFirst = fixtures().student(academyId, "학생1");
        long studentAtLast = fixtures().student(academyId, "학생2");
        fixtures().verifiedAddress(studentAtFirst, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000",
                "126.970000");
        fixtures().verifiedAddress(studentAtLast, lastStop, WEEKDAY, Direction.TO_ACADEMY, "37.561000",
                "126.971000");

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(3);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        confirmationService.confirmOne(runId);

        assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.CONFIRMED);
        assertThat(runRepository.findById(runId).orElseThrow().getConfirmedAt())
                .isEqualTo(OffsetDateTime.now(clock));

        Long versionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        assertThat(versionId).as("current_version_id 가 배정돼야 확정 노선이 유효하다").isNotNull();

        Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT version_no, source, confirmed_route_id FROM route_version WHERE id = ?", versionId);
        assertThat(version.get("version_no")).isEqualTo(1);
        assertThat(version.get("source")).isEqualTo("confirm_batch");
        assertThat(version.get("confirmed_route_id")).isEqualTo(runId);

        List<Long> runStopIds = jdbcTemplate.queryForList(
                "SELECT stop_id FROM run_stop WHERE route_version_id = ? ORDER BY seq", Long.class, versionId);
        assertThat(runStopIds).as("v1 정차 목록은 편성된 두 정차지 순서 그대로여야 한다")
                .containsExactly(firstStop, lastStop);

        List<Long> riderStudentIds = jdbcTemplate.queryForList(
                "SELECT student_id FROM run_rider WHERE run_id = ? ORDER BY student_id", Long.class, runId);
        assertThat(riderStudentIds).containsExactlyInAnyOrder(studentAtFirst, studentAtLast);

        Long riderStopForFirst = jdbcTemplate.queryForObject(
                "SELECT stop_id FROM run_rider WHERE run_id = ? AND student_id = ?", Long.class, runId,
                studentAtFirst);
        assertThat(riderStopForFirst).as("탑승자의 정차지는 그 학생의 요일별 주소가 가리키는 정차지와 같아야 한다")
                .isEqualTo(firstStop);
    }

    /**
     * 게이트 리뷰 지적 #5(T7) — 강제 추가(RTE-06, §5.7)는 대기 행만 쌓고, 그 행이 확정 배치를
     * 거쳐 {@code run_rider}·{@code run_stop} 에 실제로 반영되는지는 어떤 시험도 보지 않았다.
     * 대상 학생은 요일별 주소를 아예 안 준다 — 정상 경로로는 이 회차 명단에 오를 수 없어야
     * "강제 추가 병합이 했다" 는 것이 이 시험 하나로 갈린다. 정차지도 편성 노선(firstStop·
     * lastStop) 밖의 것을 써서, 노선 재계산이 그 정차지를 실제로 새로 만들었는지까지 본다.
     */
    @Test
    @DisplayName("게이트 리뷰 #5 — 강제 추가 대기 행이 확정 배치를 거쳐 명단·정차 목록에 실제로 반영된다")
    void 강제_추가_대기_행이_확정_배치에서_명단에_반영된다() {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures().stop(academyId, "37.561000", "126.971000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);

        long studentAtFirst = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentAtFirst, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000",
                "126.970000");

        // 강제 추가 대상 — 요일별 주소를 주지 않는다. 정상 경로(weeklyAddress)로는 이 회차 명단에
        // 절대 오르지 않아야, 강제 추가 병합이 실제로 일어났는지가 이 학생 하나로 갈린다.
        long forcedStudent = fixtures().student(academyId, "강제추가학생");
        // 편성 노선(firstStop·lastStop) 밖의 정차지 — route_stop 이 아니라 강제 추가가 직접
        // 끌어와야만 정차 목록에 나타난다.
        long forcedStop = fixtures().stop(academyId, "37.562000", "126.972000");

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(3);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        runForcedAdditionRepository.save(
                RunForcedAddition.forRun(runId, forcedStudent, forcedStop, 1L, OffsetDateTime.now(clock)));

        confirmationService.confirmOne(runId);

        List<Long> riderStudentIds = jdbcTemplate.queryForList(
                "SELECT student_id FROM run_rider WHERE run_id = ? ORDER BY student_id", Long.class, runId);
        assertThat(riderStudentIds).as("강제 추가 대기 행이 확정 배치의 명단에 실제로 합쳐져야 한다 — "
                + "저장만 되고 반영되지 않으면 화면엔 추가됐다고 뜨는데 버스 명단엔 없는 상태가 남는다")
                .contains(forcedStudent);

        Long forcedRiderStop = jdbcTemplate.queryForObject(
                "SELECT stop_id FROM run_rider WHERE run_id = ? AND student_id = ?", Long.class, runId,
                forcedStudent);
        assertThat(forcedRiderStop).as("강제 추가 시 지정한 정차지가 학생의 탑승 기록에 그대로 반영돼야 한다")
                .isEqualTo(forcedStop);

        Long versionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        List<Long> runStopIds = jdbcTemplate.queryForList("SELECT stop_id FROM run_stop WHERE route_version_id = ?",
                Long.class, versionId);
        assertThat(runStopIds).as("강제 추가된 정차지가 편성 노선 밖이어도 실제 배포 노선에 나타나야 한다")
                .contains(forcedStop);
    }

    /**
     * F4 S1 목표 7 — 버스 간 이동(RTE-07, §5.8, Ruling 256) 대기 행이 강제 추가와 같은 합류 지점
     * ({@code applyOutgoingTransfers}·{@code applyIncomingTransfers})에서 <b>양쪽 회차</b>에
     * 각각 반영되는지 본다. 대상 학생은 요일별 주소로 출발 회차에 정상 배정돼 있다 — 이동이 실제로
     * 뺐는지가 "원래도 명단에 없었다"와 구별되려면 정상 경로로 이미 태워져 있어야 한다. 도착 정차지는
     * 도착 회차의 편성 노선 밖의 것을 써서(강제 추가 시험과 같은 근거) override 가 실제로 적용됐는지
     * 함께 본다.
     */
    @Test
    @DisplayName("목표7 — 버스 간 이동 대기 행이 출발·도착 두 확정 배치에 각각 반영되고 상태가 applied 로 바뀐다")
    void 버스_간_이동_대기_행이_출발_도착_양쪽_확정_배치에_반영된다() {
        long academyId = fixtures().academyWithCoordinates();

        long fromBusId = fixtures().bus(academyId);
        long fromFirstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long fromLastStop = fixtures().stop(academyId, "37.561000", "126.971000");
        fixtures().route(academyId, fromBusId, WEEKDAY, Direction.TO_ACADEMY, fromFirstStop, fromLastStop);

        long toBusId = fixtures().bus(academyId);
        long toFirstStop = fixtures().stop(academyId, "37.570000", "126.980000");
        long toLastStop = fixtures().stop(academyId, "37.571000", "126.981000");
        fixtures().route(academyId, toBusId, WEEKDAY, Direction.TO_ACADEMY, toFirstStop, toLastStop);

        // 이동 대상 학생 — 요일별 주소로 출발 회차에 정상 배정돼 있다(정상 경로로 이미 탑승 중).
        long transferringStudent = fixtures().student(academyId, "이동학생");
        fixtures().verifiedAddress(transferringStudent, fromFirstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000",
                "126.970000");

        // 도착 회차의 편성 노선(toFirstStop·toLastStop) 밖의 정차지 — override 가 실제로 적용됐는지 본다.
        long destinationStop = fixtures().stop(academyId, "37.572000", "126.982000");

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(3);
        long fromRunId = fixtures().idleRun(academyId, fromBusId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long toRunId = fixtures().idleRun(academyId, toBusId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        RunTransfer transfer = runTransferRepository.save(RunTransfer.stage(transferringStudent, fromRunId, toRunId,
                destinationStop, null, 1L, OffsetDateTime.now(clock)));

        confirmationService.confirmOne(fromRunId);

        List<Long> fromRiderIds = jdbcTemplate.queryForList(
                "SELECT student_id FROM run_rider WHERE run_id = ?", Long.class, fromRunId);
        assertThat(fromRiderIds).as("출발 회차 확정 배치가 이동 대상을 명단에서 빼야 한다 — 요일별 주소만 봤다면 "
                + "여전히 남아 있을 것이다").doesNotContain(transferringStudent);

        String statusAfterOutgoing = jdbcTemplate.queryForObject(
                "SELECT status FROM run_transfer WHERE id = ?", String.class, transfer.getId());
        assertThat(statusAfterOutgoing).as("출발 쪽 확정 배치만 돌아도 상태는 이미 applied 로 바뀐다("
                + "두 회차 중 먼저 도는 쪽이 반영하는 시점 기준)").isEqualTo("applied");

        confirmationService.confirmOne(toRunId);

        List<Long> toRiderIds = jdbcTemplate.queryForList(
                "SELECT student_id FROM run_rider WHERE run_id = ?", Long.class, toRunId);
        assertThat(toRiderIds).as("도착 회차 확정 배치가 이동 대상을 명단에 더해야 한다").contains(transferringStudent);

        Long toRiderStop = jdbcTemplate.queryForObject(
                "SELECT stop_id FROM run_rider WHERE run_id = ? AND student_id = ?", Long.class, toRunId,
                transferringStudent);
        assertThat(toRiderStop).as("이동 신청 시 지정한 정차지가 도착 회차의 탑승 기록에 그대로 반영돼야 한다")
                .isEqualTo(destinationStop);

        Long toVersionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, toRunId);
        List<Long> toRunStopIds = jdbcTemplate.queryForList(
                "SELECT stop_id FROM run_stop WHERE route_version_id = ?", Long.class, toVersionId);
        assertThat(toRunStopIds).as("이동으로 지정한 정차지가 도착 회차의 편성 노선 밖이어도 실제 배포 노선에 나타나야 한다")
                .contains(destinationStop);

        String statusAfterIncoming = jdbcTemplate.queryForObject(
                "SELECT status FROM run_transfer WHERE id = ?", String.class, transfer.getId());
        assertThat(statusAfterIncoming).as("도착 쪽 확정 배치도 상태로 거르지 않고 다시 처리한다(자기 치유) — "
                + "이미 applied 였어도 그대로 applied 로 남는다").isEqualTo("applied");
    }

    @Test
    @DisplayName("목표5 — 학원 좌표가 없으면 대체 기준점 없이 그 회차만 실패한다")
    void 학원_좌표가_없으면_확정이_실패하고_산출물이_남지_않는다() {
        long academyId = fixtures().academyWithoutCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures().stop(academyId, "37.561000", "126.971000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(3);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        assertThatThrownBy(() -> confirmationService.confirmOne(runId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACADEMY_COORDINATES_MISSING);

        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .as("확정 표시조차 되지 않아야 한다 — confirmIfIdle 이 불리기 전에 실패한다").isEqualTo(RunStatus.IDLE);
        Integer confirmedRouteCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM confirmed_route WHERE run_id = ?", Integer.class, runId);
        assertThat(confirmedRouteCount)
                .as("대체 기준점으로 계산을 강행했다면 여기 행이 남는다 — Ruling 190 은 그것을 금지한다")
                .isZero();
    }
}
