package src.backend.request.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;

/**
 * 관계자의 승인 대기 상세({@code GET /staff/approvals/{id}}) 응답은 변경을 신청한 학생과 승하차지 좌표(노선 전·후 정차)를
 * 함께 싣는다 — 학생 한 명을 좌표에 묶는 응답이라 L3 조회 감사 행을 남기고, 같은 행위자의 10분 안 재조회는 묶는다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 감사 행이 {@code REQUIRES_NEW} 로 커밋되므로 시험 트랜잭션 안에서는 읽히지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalDetailL3AuditTest {

    private static final long ACTOR = 7_670_000_001L;

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 5, 6);

    private static final Weekday WEEKDAY = Weekday.MON;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @AfterEach
    void 감사_행을_지운다() {
        jdbcTemplate.update("delete from audit_log where actor_account_id = ?", ACTOR);
    }

    @Test
    void 승인_대기_상세는_응답에_실린_학생마다_L3_조회_감사_행을_남긴다() throws Exception {
        Scenario scenario = 확정된_회차와_승인_대기_건을_만든다();

        String body = 상세를_조회한다(scenario);

        Set<Long> named = new HashSet<>();
        List<Object> ids = JsonPath.read(body, "$.data.affected_students[*].student_id");
        ids.forEach(id -> named.add(Long.valueOf(String.valueOf(id))));
        assertThat(named).as("응답에 학생이 실리지 않으면 이 시험이 아무것도 보지 못한다").contains(scenario.studentId());
        assertThat(감사_학생_id들()).as("응답에 실린 학생마다 read 행 1건").containsExactlyInAnyOrderElementsOf(named);
    }

    @Test
    void 같은_행위자의_10분_안_재조회는_감사_행을_더하지_않는다() throws Exception {
        Scenario scenario = 확정된_회차와_승인_대기_건을_만든다();

        상세를_조회한다(scenario);
        int firstCount = 감사_학생_id들().size();
        상세를_조회한다(scenario);

        assertThat(firstCount).as("첫 조회에서 행이 쌓여야 묶기를 볼 수 있다").isPositive();
        assertThat(감사_학생_id들()).hasSize(firstCount);
    }

    private String 상세를_조회한다(Scenario scenario) throws Exception {
        return mockMvc.perform(get("/api/v1/staff/approvals/" + scenario.approvalId())
                        .header("Authorization", 토큰(tokenProvider, ACTOR, scenario.academyId(), Role.STAFF)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<Long> 감사_학생_id들() {
        return jdbcTemplate.queryForList("select target_id from audit_log where actor_account_id = ? "
                + "and category = 'data_access' and action = 'read' and target_type = 'student'", Long.class, ACTOR);
    }

    private record Scenario(long academyId, long studentId, long approvalId) {
    }

    /** 노선 세 정차에 학생 셋을 태워 확정한 회차에, 둘째 학생의 취소 신청을 대기로 올린다. */
    private Scenario 확정된_회차와_승인_대기_건을_만든다() {
        RunConfirmationFixtures fixtures = RunConfirmationFixtures.of(applicationContext);
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long midStop = fixtures.stop(academyId, "37.562000", "126.972000");
        long lastStop = fixtures.stop(academyId, "37.564000", "126.974000");
        fixtures.route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, midStop, lastStop);

        long student1 = fixtures.student(academyId, "학생1");
        long student2 = fixtures.student(academyId, "학생2");
        long student3 = fixtures.student(academyId, "학생3");
        fixtures.verifiedAddress(student1, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        fixtures.verifiedAddress(student2, midStop, WEEKDAY, Direction.TO_ACADEMY, "37.562000", "126.972000");
        fixtures.verifiedAddress(student3, lastStop, WEEKDAY, Direction.TO_ACADEMY, "37.564000", "126.974000");

        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(ZoneOffset.of("+09:00"));
        long runId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        confirmationService.confirmOne(runId);

        ChangeRequest request = ChangeRequest.forRequest(academyId, runId, student2,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, student2,
                OffsetDateTime.now());
        return new Scenario(academyId, student2, changeRequestRepository.save(request).getId());
    }
}
