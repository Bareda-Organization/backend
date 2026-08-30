package src.backend.request.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * §5.5 {@code /staff/approvals} — 목록은 재최적화를 실행하지 않고(목표 12 호출 수 검증), 상세만 그
 * 시점에 1회 실행한다. 미리보기 캐시가 입력이 그대로인 반복 조회를 재계산 없이 같은 토큰으로
 * 돌려주는지, 입력이 바뀌면 갱신되는지도 함께 본다.
 *
 * <p><b>이 클래스의 최우선 단언은 {@link RouteComputationPipeline#compute} 호출 횟수</b>다 — 응답
 * 필드가 맞아도 목록에서 재최적화가 함께 도는 구현은 대기 건 N개마다 N회 동기 호출을 만들어 화면을
 * 멈춘다({@link src.backend.request.query.ApprovalQueryService} javadoc).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffApprovalControllerTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 5, 6); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @MockitoSpyBean
    private RouteComputationPipeline pipeline;

    private RunConfirmationFixtures fixtures;

    private RunConfirmationFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return fixtures;
    }

    // ── 목표 12 — 목록은 재최적화를 실행하지 않는다 ──────────────────────────

    /** 목록 조회는 대기 건이 있어도 {@link RouteComputationPipeline#compute} 를 <b>한 번도</b> 부르지 않는다. */
    @Test
    void 목록_조회는_재최적화를_실행하지_않는다() throws Exception {
        시나리오 s = 확정된_회차와_승인_대기_건을_만든다();

        목록_조회(관계자_토큰(s.academyId), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.pending_count").value(1));

        verify(pipeline, times(0)).compute(any());
    }

    /** 대기 건이 없으면 {@code items} 는 빈 배열이고 {@code pending_count} 는 0이다. */
    @Test
    void 대기_건이_없으면_빈_목록이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();

        목록_조회(관계자_토큰(academyId), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.data.pending_count").value(0));

        verify(pipeline, times(0)).compute(any());
    }

    // ── 목표 12 — 상세는 정확히 1회 실행한다 ──────────────────────────────

    /** 상세 조회는 {@link RouteComputationPipeline#compute} 를 <b>정확히 1회</b> 부른다. */
    @Test
    void 상세_조회는_재최적화를_1회만_실행한다() throws Exception {
        시나리오 s = 확정된_회차와_승인_대기_건을_만든다();

        상세_조회(관계자_토큰(s.academyId), s.approvalId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preview_token").isNotEmpty())
                .andExpect(jsonPath("$.data.preview_stale").value(false));

        verify(pipeline, times(1)).compute(any());
    }

    /**
     * 입력이 그대로인 채 상세를 반복 조회하면 같은 {@code preview_token} 을 돌려주고, 그동안
     * {@link RouteComputationPipeline#compute} 는 <b>더 불리지 않는다</b>(캐시 안정성).
     *
     * <p>시간만 흘러도(만료 없음) 값이 바뀌지 않는다는 것도 이 반복 호출로 함께 확인된다 —
     * {@code preview_stale} 이 만료 기반이 아니라 <b>입력 변경</b> 기반이라는 것이 이 태스크의 계약이다.
     */
    @Test
    void 입력이_그대로면_같은_토큰을_돌려주고_추가_호출이_없다() throws Exception {
        시나리오 s = 확정된_회차와_승인_대기_건을_만든다();

        String 첫토큰 = 상세_본문에서_토큰(s);
        verify(pipeline, times(1)).compute(any());

        String 둘째토큰 = 상세_본문에서_토큰(s);
        String 셋째토큰 = 상세_본문에서_토큰(s);

        assertThat(둘째토큰).as("입력이 그대로면 캐시가 같은 토큰을 돌려준다").isEqualTo(첫토큰);
        assertThat(셋째토큰).isEqualTo(첫토큰);
        verify(pipeline, times(1)).compute(any());

        상세_조회(관계자_토큰(s.academyId), s.approvalId)
                .andExpect(jsonPath("$.data.preview_stale").value(false));
    }

    /**
     * 명단(입력)이 바뀌면 다음 상세 조회에서 {@code preview_stale=true} 로 갱신되고
     * {@link RouteComputationPipeline#compute} 가 다시 불린다 — 시간 경과가 아니라 <b>입력 변경</b>이
     * 갱신 기준이라는 것을 보이는 단언이다.
     */
    @Test
    void 명단이_바뀌면_다음_조회에서_stale_이_참이_되고_재계산한다() throws Exception {
        시나리오 s = 확정된_회차와_승인_대기_건을_만든다();

        상세_조회(관계자_토큰(s.academyId), s.approvalId)
                .andExpect(jsonPath("$.data.preview_stale").value(false));
        verify(pipeline, times(1)).compute(any());

        long 넷째학생 = fixtures().student(s.academyId, "학생4");
        runRiderRepository.save(RunRider.uponConfirmation(s.runId, 넷째학생, s.midStopId));

        String 두번째토큰 = 상세_조회(관계자_토큰(s.academyId), s.approvalId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preview_stale").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String 새토큰 = JsonPath.read(두번째토큰, "$.data.preview_token");

        assertThat(새토큰).as("입력이 바뀌면 새 토큰을 발급한다").isNotEqualTo(s.첫토큰);
        verify(pipeline, times(2)).compute(any());
    }

    // ── 격리·404 ──────────────────────────────────────────────────────────

    /** 다른 학원 관계자가 남의 승인 건을 상세 조회하면 {@code 403 ACADEMY_SCOPE_VIOLATION}. */
    @Test
    void 다른_학원의_승인_건을_상세_조회하면_403_이다() throws Exception {
        시나리오 s = 확정된_회차와_승인_대기_건을_만든다();
        long 남의학원 = fixtures().academyWithCoordinates();

        상세_조회(관계자_토큰(남의학원), s.approvalId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));

        verify(pipeline, times(0)).compute(any());
    }

    /** 존재하지 않는 승인 건을 상세 조회하면 {@code 404 APPROVAL_NOT_FOUND}. */
    @Test
    void 존재하지_않는_승인_건은_404_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();

        상세_조회(관계자_토큰(academyId), 999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_NOT_FOUND"));

        verify(pipeline, times(0)).compute(any());
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /**
     * 확정 노선 위에 승인 대기 1건을 올린 시나리오 — Ruling 198(②구간 요청이 있을 때는 이미 회차가
     * 확정돼 있다) 을 그대로 재현한다: 먼저 {@link RunConfirmationService#confirmOne} 로 회차를
     * 확정해 4종 산출물을 만들고, 그 위에 {@link ChangeRequest} 를 직접 쌓는다(제출 경로 자체는
     * 이 태스크 범위 밖 — T2·T3 소관).
     */
    private 시나리오 확정된_회차와_승인_대기_건을_만든다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long midStop = fixtures().stop(academyId, "37.562000", "126.972000");
        long lastStop = fixtures().stop(academyId, "37.564000", "126.974000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, midStop, lastStop);

        long 학생1 = fixtures().student(academyId, "학생1");
        long 학생2 = fixtures().student(academyId, "학생2");
        long 학생3 = fixtures().student(academyId, "학생3");
        fixtures().verifiedAddress(학생1, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        fixtures().verifiedAddress(학생2, midStop, WEEKDAY, Direction.TO_ACADEMY, "37.562000", "126.972000");
        fixtures().verifiedAddress(학생3, lastStop, WEEKDAY, Direction.TO_ACADEMY, "37.564000", "126.974000");

        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(java.time.ZoneOffset.of("+09:00"));
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        confirmationService.confirmOne(runId);
        // 확정 배치도 같은 파이프라인을 부른다 — 이후 단언은 "이 시나리오의 조회부터" 만 세야 하므로
        // 준비 단계의 호출을 여기서 지운다(호출 수 검증의 기준선을 0으로 되돌린다).
        org.mockito.Mockito.clearInvocations(pipeline);

        ChangeRequest changeRequest = ChangeRequest.forRequest(academyId, runId, 학생2,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, 학생2,
                OffsetDateTime.now());
        long approvalId = changeRequestRepository.save(changeRequest).getId();

        return new 시나리오(academyId, runId, approvalId, midStop, null);
    }

    private String 상세_본문에서_토큰(시나리오 s) throws Exception {
        String body = 상세_조회(관계자_토큰(s.academyId), s.approvalId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = JsonPath.read(body, "$.data.preview_token");
        if (s.첫토큰 == null) {
            s.첫토큰 = token;
        }
        return token;
    }

    private ResultActions 목록_조회(String token, String status) throws Exception {
        String uri = status == null ? "/api/v1/staff/approvals" : "/api/v1/staff/approvals?status=" + status;
        return mockMvc.perform(get(uri).header("Authorization", token));
    }

    private ResultActions 상세_조회(String token, long approvalId) throws Exception {
        return mockMvc.perform(get("/api/v1/staff/approvals/" + approvalId).header("Authorization", token));
    }

    private String 관계자_토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }

    /** 시나리오 픽스처 값 묶음 — {@code 첫토큰} 은 반복 조회 도우미가 최초 호출 시 채워 넣는다. */
    private static final class 시나리오 {
        final long academyId;
        final long runId;
        final long approvalId;
        final long midStopId;
        String 첫토큰;

        시나리오(long academyId, long runId, long approvalId, long midStopId, String 첫토큰) {
            this.academyId = academyId;
            this.runId = runId;
            this.approvalId = approvalId;
            this.midStopId = midStopId;
            this.첫토큰 = 첫토큰;
        }
    }
}
