package src.backend.run.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * §5.8 {@code POST /staff/students/{id}/transfer}(RTE-07, Ruling 256) — F4 S1 목표 6.
 *
 * <p>대상이 두 회차(출발·도착)라 {@code StaffForcedAdditionControllerTest} 와 달리 구간·정원·학원
 * 판정을 <b>어느 쪽 회차 기준인지</b>까지 갈라서 확인한다. 명단 소속(§5.8 이 말하는 "당일 명단")은
 * 경로 정확도보다 존재 여부만 필요하므로, 요일별 주소 대신 강제 추가 1건으로 만든다 —
 * {@code TransferCommandService.projectedRosterStudentIds} 가 강제 추가도 명단에 합치기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffStudentTransferControllerTest {

    private static final long STAFF_ACCOUNT_ID = 9101L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

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

    private RunConfirmationFixtures fixtures;

    /** 시각을 고정한다(횡단 규칙 1) — 구간 판정이 주입된 시계를 보는지 재려면 고정이 필요하다. */
    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2026-08-27T02:00:00Z");

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

    // ── 성공 ─────────────────────────────────────────────────────────────

    /** 두 회차 모두 ①구간이고 학생이 출발 회차 명단에 있으면 201 이고 impact 가 실린다. */
    @Test
    void 이동_신청이_성공하면_201_이고_impact_가_실린다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long fromBusId = fixtures().bus(academyId);
        long toBusId = fixtures().bus(academyId);
        long fromRunId = 회차를_만든다(academyId, fromBusId, 31);
        long toRunId = 회차를_만든다(academyId, toBusId, 31);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        long studentId = fixtures().student(academyId, "이동학생");
        학생을_회차_명단에_넣는다(fromRunId, studentId);

        이동_신청한다(studentId, academyId, 이동_본문(fromRunId, toRunId, stopId, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("staged"))
                .andExpect(jsonPath("$.data.from_run_id").value(fromRunId))
                .andExpect(jsonPath("$.data.to_run_id").value(toRunId))
                .andExpect(jsonPath("$.data.impact.from.rider_count_before").value(1))
                .andExpect(jsonPath("$.data.impact.from.rider_count_after").value(0))
                .andExpect(jsonPath("$.data.impact.to.rider_count_before").value(0))
                .andExpect(jsonPath("$.data.impact.to.rider_count_after").value(1));
    }

    // ── 정원(BUS-04) ─────────────────────────────────────────────────────

    /** 도착 회차 정원이 이미 찼으면(강제 추가로 1석 소진) 409 CAPACITY_EXCEEDED 다. */
    @Test
    void 도착_회차_정원을_초과하면_409_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long fromBusId = fixtures().bus(academyId);
        long toBusId = busRepository.save(Bus.register(academyId, "이동정원", "00나0000", new BusSeating(3, 1, 1)))
                .getId();
        long fromRunId = 회차를_만든다(academyId, fromBusId, 31);
        long toRunId = 회차를_만든다(academyId, toBusId, 31);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");

        long studentId = fixtures().student(academyId, "이동학생");
        학생을_회차_명단에_넣는다(fromRunId, studentId);
        long occupyingStudentId = fixtures().student(academyId, "선점학생");
        학생을_회차_명단에_넣는다(toRunId, occupyingStudentId);

        이동_신청한다(studentId, academyId, 이동_본문(fromRunId, toRunId, stopId, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CAPACITY_EXCEEDED"));
    }

    // ── 구간 판정(둘 중 한 회차라도 ②구간이면 막는다) ───────────────────────

    /** 출발 회차가 ②구간(출발 20분 전)이면 도착 회차가 열려 있어도 403 이다. */
    @Test
    void 출발_회차가_2구간이면_403_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long fromBusId = fixtures().bus(academyId);
        long toBusId = fixtures().bus(academyId);
        long fromRunId = 회차를_만든다(academyId, fromBusId, 20);
        long toRunId = 회차를_만든다(academyId, toBusId, 31);
        long studentId = fixtures().student(academyId, "이동학생");

        이동_신청한다(studentId, academyId, 이동_본문(fromRunId, toRunId, null, "테스트로 100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    /** 출발 회차는 ①구간이어도 도착 회차가 ②구간이면 403 이다 — "둘 중 한 회차라도" 를 확인한다. */
    @Test
    void 도착_회차가_2구간이면_403_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long fromBusId = fixtures().bus(academyId);
        long toBusId = fixtures().bus(academyId);
        long fromRunId = 회차를_만든다(academyId, fromBusId, 31);
        long toRunId = 회차를_만든다(academyId, toBusId, 20);
        long studentId = fixtures().student(academyId, "이동학생");

        이동_신청한다(studentId, academyId, 이동_본문(fromRunId, toRunId, null, "테스트로 100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    // ── 출발 회차 명단 소속 ──────────────────────────────────────────────

    /** 학생이 출발 회차의 당일 명단에 없으면(강제 추가도 요일별 주소도 없음) 409 STUDENT_NOT_IN_RUN 이다. */
    @Test
    void 학생이_출발_회차_명단에_없으면_409_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long fromBusId = fixtures().bus(academyId);
        long toBusId = fixtures().bus(academyId);
        long fromRunId = 회차를_만든다(academyId, fromBusId, 31);
        long toRunId = 회차를_만든다(academyId, toBusId, 31);
        long studentId = fixtures().student(academyId, "명단밖학생");

        이동_신청한다(studentId, academyId, 이동_본문(fromRunId, toRunId, null, "테스트로 100"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_IN_RUN"));
    }

    // ── from = to ────────────────────────────────────────────────────────

    /** 출발·도착 회차가 같으면 422 VALIDATION_FAILED 다. */
    @Test
    void 출발과_도착이_같은_회차면_422_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);
        long studentId = fixtures().student(academyId, "이동학생");

        이동_신청한다(studentId, academyId, 이동_본문(runId, runId, null, "테스트로 100"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── 학원 격리 ─────────────────────────────────────────────────────────

    /** 도착 회차가 다른 학원 소속이면 403 ACADEMY_SCOPE_VIOLATION 이다(from 과 다른 판정, Ruling 163 예외). */
    @Test
    void 도착_회차가_타_학원이면_403_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long fromRunId = 회차를_만든다(academyId, busId, 31);

        long otherAcademyId = fixtures().academyWithCoordinates();
        long otherBusId = fixtures().bus(otherAcademyId);
        long toRunId = 회차를_만든다(otherAcademyId, otherBusId, 31);

        long studentId = fixtures().student(academyId, "이동학생");

        이동_신청한다(studentId, academyId, 이동_본문(fromRunId, toRunId, null, "테스트로 100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));
    }

    // ── not-found ────────────────────────────────────────────────────────

    /** {id}(학생)가 다른 학원 소속이면 존재 비노출(Ruling 163)로 404 STUDENT_NOT_FOUND 다. */
    @Test
    void 학생이_없으면_404_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long fromBusId = fixtures().bus(academyId);
        long toBusId = fixtures().bus(academyId);
        long fromRunId = 회차를_만든다(academyId, fromBusId, 31);
        long toRunId = 회차를_만든다(academyId, toBusId, 31);

        long otherAcademyId = fixtures().academyWithCoordinates();
        long otherAcademyStudentId = fixtures().student(otherAcademyId, "타학원학생");

        이동_신청한다(otherAcademyStudentId, academyId, 이동_본문(fromRunId, toRunId, null, "테스트로 100"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    /** 존재하지 않는 출발 회차 id 는 404 RUN_NOT_FOUND 다. */
    @Test
    void 출발_회차가_없으면_404_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long toRunId = 회차를_만든다(academyId, busId, 31);
        long studentId = fixtures().student(academyId, "이동학생");

        이동_신청한다(studentId, academyId, 이동_본문(9_999_999L, toRunId, null, "테스트로 100"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    /** 존재하지 않는 도착 회차 id 도 404 RUN_NOT_FOUND 다. */
    @Test
    void 도착_회차가_없으면_404_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long fromRunId = 회차를_만든다(academyId, busId, 31);
        long studentId = fixtures().student(academyId, "이동학생");

        이동_신청한다(studentId, academyId, 이동_본문(fromRunId, 9_999_999L, null, "테스트로 100"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private long 회차를_만든다(long academyId, long busId, int minutesBeforeDepart) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime departTime = now.plusMinutes(minutesBeforeDepart);
        OffsetDateTime confirmAt = departTime.minusMinutes(30);
        return fixtures().idleRun(academyId, busId, now.toLocalDate(), Direction.TO_ACADEMY, departTime, confirmAt);
    }

    /** 강제 추가 1건으로 그 회차의 당일 명단에 학생을 올린다({@code projectedRosterStudentIds} 가 합친다). */
    private void 학생을_회차_명단에_넣는다(long runId, long studentId) {
        long stopId = fixtures().stop(academyId(runId), "37.561000", "126.971000");
        runForcedAdditionRepository
                .save(RunForcedAddition.forRun(runId, studentId, stopId, STAFF_ACCOUNT_ID, OffsetDateTime.now(clock)));
    }

    private long academyId(long runId) {
        return runRepository.findById(runId).orElseThrow().getAcademyId();
    }

    private String 이동_본문(Long fromRunId, Long toRunId, Long stopId, String address) {
        StringBuilder body = new StringBuilder("{");
        body.append("\"from_run_id\":").append(fromRunId).append(",");
        body.append("\"to_run_id\":").append(toRunId).append(",");
        if (stopId != null) {
            body.append("\"stop_id\":").append(stopId);
        } else {
            body.append("\"address\":\"").append(address).append("\"");
        }
        body.append("}");
        return body.toString();
    }

    private ResultActions 이동_신청한다(long studentId, long requesterAcademyId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/students/" + studentId + "/transfer")
                .header("Authorization", 토큰(requesterAcademyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_ACCOUNT_ID, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
