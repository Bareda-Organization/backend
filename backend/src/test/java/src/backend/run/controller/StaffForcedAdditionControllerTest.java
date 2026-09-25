package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.entity.BusSeating;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.ForcedAdditionStore;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.dto.ForcedAdditionRequest;
import src.backend.run.dto.NewStudentRequest;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * §5.7 {@code POST /staff/runs/{runId}/forced-add}(RTE-06, Ruling 197·198).
 *
 * <p><b>이 클래스의 최우선 단언은 구간 판정이다</b> — ①구간(출발 30분 초과 전)만 허용하고 ②③구간은
 * 관계자도 예외 없이 403 이다(§5.7 본문). "정원·주소는 정상인데 시각만 다른" 두 요청을 나란히 둬야
 * 구간 판정이 실제로 창을 가르는지 알 수 있다.
 *
 * <p>정원 판정 대상은 이 시점에 {@code run_rider} 가 없는 idle 회차라, 요일별 주소 기준 투영 인원 +
 * 이미 대기 중인 강제 추가 건수로 계산한다(ForcedAdditionCommandService javadoc) — 정원 1석 차량에
 * 두 번째 강제 추가를 시도해 그 계산이 실제로 누적되는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffForcedAdditionControllerTest {

    private static final long STAFF_ACCOUNT_ID = 9001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

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
    private ForcedAdditionStore forcedAdditionStore;

    private RunConfirmationFixtures fixtures;

    /** 시각을 고정한다(횡단 규칙 1) — 구간 판정이 주입된 시계를 보는지 재려면 고정이 필요하다. */
    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2026-08-26T02:00:00Z");

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

    // ── 구간 판정(목표 10) ────────────────────────────────────────────────

    /** 출발 31분 전(①구간, IMMEDIATE)은 강제 추가를 받아들인다. */
    @Test
    void 출발_31분_전에는_강제_추가가_성공한다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);

        강제_추가한다(runId, 학생_추가_본문(null, "새학생", "테스트로 100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.run_id").value(runId))
                .andExpect(jsonPath("$.data.status").value("staged"));
    }

    /** 출발 20분 전(②구간)은 관계자 요청이라도 403 이다 — ①구간 전용이 §5.7 본문의 명시 규칙이다. */
    @Test
    void 출발_20분_전에는_403_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 20);

        강제_추가한다(runId, 학생_추가_본문(null, "새학생", "테스트로 100"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    // ── 정원(BUS-04) ─────────────────────────────────────────────────────

    /** 학생 정원 1석 차량에 강제 추가 두 번째 시도는 409 다 — 대기 중인 첫 건이 정원 계산에 누적된다. */
    @Test
    void 정원을_초과하면_409_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = busRepository.save(Bus.register(academyId, "정원시험", "00가0000", new BusSeating(3, 1, 1)))
                .getId();
        long runId = 회차를_만든다(academyId, busId, 31);

        강제_추가한다(runId, 학생_추가_본문(null, "학생1", "테스트로 100")).andExpect(status().isCreated());

        강제_추가한다(runId, 학생_추가_본문(null, "학생2", "테스트로 200"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CAPACITY_EXCEEDED"));
    }

    // ── 주소 검증(STU-05) ────────────────────────────────────────────────

    /** 주소 검증 실패는 422 이고, 이 시점엔 대기 행이 하나도 저장되지 않는다. */
    @Test
    void 주소_검증에_실패하면_422_이고_행이_저장되지_않는다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);

        강제_추가한다(runId, 학생_추가_본문(null, "새학생", "번지_없는_주소"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_FAILED"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run_forced_addition WHERE run_id = ?",
                Integer.class, runId))
                .as("검증 실패 시점엔 아무 행도 저장되지 않아야 한다")
                .isZero();
    }

    // ── 학생 지정 조건(§5.7 배타) ────────────────────────────────────────

    /** {@code student_id} 와 {@code new_student.name} 을 둘 다 보내면 422 다. */
    @Test
    void 학생_지정이_둘_다면_422_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);
        long studentId = fixtures().student(academyId, "기존학생");

        강제_추가한다(runId, 학생_추가_본문(studentId, "새학생", "테스트로 100"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** {@code student_id} 도 {@code new_student.name} 도 없으면 422 다. */
    @Test
    void 학생_지정이_없으면_422_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);

        강제_추가한다(runId, 학생_추가_본문(null, null, "테스트로 100"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** 기존 학생을 {@code student_id} 로 지정하면 그 학생 id 가 응답에 그대로 실린다. */
    @Test
    void 기존_학생을_강제로_추가할_수_있다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);
        long studentId = fixtures().student(academyId, "기존학생");

        강제_추가한다(runId, 학생_추가_본문(studentId, null, "테스트로 100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.student_id").value(studentId));
    }

    // ── 취소 회차(BR-042) · 확정 경합(BR-044) ──────────────────────────────

    /** 취소된 회차에 강제 추가하면 확정되지 않아 학생이 어느 명단에도 없게 된다 — 409 RUN_CANCELED 다. */
    @Test
    void 취소된_회차에_강제_추가하면_409_RUN_CANCELED_다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);
        jdbcTemplate.update("UPDATE run SET canceled_at = now() WHERE id = ?", runId);
        entityManager.clear();

        강제_추가한다(runId, 학생_추가_본문(null, "새학생", "테스트로 100"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_CANCELED"));
    }

    /**
     * 구간 판정은 지오코딩(수 초) 전에 끝난다 — 그 사이 확정 배치가 회차를 확정하면, 저장 단계가 회차 상태를
     * 다시 보지 않는 한 201 을 받고도 이미 지나간 확정에 합류하지 못한다(BR-044). 저장 단계를 옛 {@code Run}
     * 으로 직접 불러 그 순간을 재현한다.
     */
    @Test
    void 판정_뒤_확정된_회차는_저장_단계에서_막힌다() {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);
        Run staleRun = runRepository.findById(runId).orElseThrow();
        jdbcTemplate.update("UPDATE run SET status = 'confirmed', confirmed_at = now() WHERE id = ?", runId);
        entityManager.clear();

        assertThatThrownBy(() -> forcedAdditionStore.stage(staleRun, STAFF_ACCOUNT_ID, null,
                new ForcedAdditionRequest(null, new NewStudentRequest("새학생"), "테스트로 100", null),
                new GeocodedPoint(new BigDecimal("37.560000"), new BigDecimal("126.970000"), "테스트로 100"),
                OffsetDateTime.now(clock)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHANGE_WINDOW_CLOSED);
    }

    // ── 학원 격리 ─────────────────────────────────────────────────────────

    /** 다른 학원의 회차를 지목하면 404 다(ARCHITECTURE §6.1 과 같은 형태 — 존재 여부를 드러내지 않는다). */
    @Test
    void 다른_학원의_회차는_404_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long runId = 회차를_만든다(academyId, busId, 31);

        long otherAcademyId = fixtures().academyWithCoordinates();

        mockMvc.perform(post("/api/v1/staff/runs/" + runId + "/forced-add")
                .header("Authorization", 토큰(otherAcademyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(학생_추가_본문(null, "새학생", "테스트로 100")))
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

    private ResultActions 강제_추가한다(long runId, String body) throws Exception {
        long academyId = jdbcTemplate.queryForObject("SELECT academy_id FROM run WHERE id = ?", Long.class, runId);
        return mockMvc.perform(post("/api/v1/staff/runs/" + runId + "/forced-add")
                .header("Authorization", 토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * {@code studentId} · {@code newStudentName} 을 <b>둘 다</b> 실을 수 있게 만든다 — 배타 조건
     * 위반(§5.7)을 실제로 재현하려면 두 필드가 한 요청에 동시에 실리는 경로가 있어야 한다.
     */
    private String 학생_추가_본문(Long studentId, String newStudentName, String address) {
        StringBuilder body = new StringBuilder("{");
        if (studentId != null) {
            body.append("\"student_id\":").append(studentId).append(",");
        }
        if (newStudentName != null) {
            body.append("\"new_student\":{\"name\":\"").append(newStudentName).append("\"},");
        }
        body.append("\"address\":\"").append(address).append("\"}");
        return body.toString();
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_ACCOUNT_ID, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
