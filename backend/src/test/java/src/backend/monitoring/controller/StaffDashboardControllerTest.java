package src.backend.monitoring.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * §5.3 {@code GET /staff/dashboard} — Phase 13 T1 목표 1·2·3·4(뒷항)·5.
 *
 * <p>{@code p13-task-t1.md §6} 완료 기준 3(목표 3은 실제 {@code PATCH .../riders/{riderId}} 호출을
 * 시험 본문에 담아야 함)·4(목표 4 뒷항은 재배포 후 {@code ack_driver=false} 를 검사해야 함)를 그대로
 * 따른다 — 손으로 {@code run_rider} 행을 UPDATE 하지 않는다(같은 브리프 목표 3).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class StaffDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

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
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

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

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private Clock clock;

    /**
     * {@code DriverRunFixtures.confirmedRun}·{@code idleRun} 이 {@code serviceDate} 를
     * {@code 2030-04-01} 로 고정해서 만든다(자바독 없이 하드코딩 — 다른 T1 시험들의 공통 전제) —
     * 대시보드가 기본값으로 쓰는 "오늘"이 이 날짜와 맞아야 {@code runs[]} 가 비지 않는다.
     */

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    // ── 목표 1 — §5.3 필드 전부 ──────────────────────────────────────────

    /**
     * 목표 1 — {@code metrics} 5종 + {@code runs[]} 14개 필드(정본 {@code API_SPEC.md §5.3} 직접
     * 계수, {@code runs[]} 표는 7개 행이지만 {@code driver_name·escort_name} 처럼 한 행이 필드
     * 여럿을 묶어 실제 JSON 리프 키는 14개다 — 브리프의 "12" 는 이 재계수로 정정해 보고서에 남긴다).
     */
    @Test
    @DisplayName("목표1 — metrics 5종과 runs[] 14개 필드가 전부 채워진다")
    void 대시보드_응답이_정본_필드를_전부_담는다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        OffsetDateTime departTime = now().plusHours(1);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long versionId = fx.confirmedRouteWithVersion(runId, departTime.minusMinutes(40));
        fx.runStopForStop(versionId, stopId, 1, departTime);
        long studentId = fx.student(academyId, "학생1");
        fx.rider(runId, studentId, stopId, src.backend.boarding.entity.RiderStatus.WAITING, now());
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", now());
        fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자1", now());
        fx.unassignedManager(academyId, ManagerRole.DRIVER, "미배치기사1");
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes").header("Authorization",
                토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.moving_buses").exists())
                .andExpect(jsonPath("$.data.metrics.boarded").exists())
                .andExpect(jsonPath("$.data.metrics.no_show").exists())
                .andExpect(jsonPath("$.data.metrics.absent").exists())
                .andExpect(jsonPath("$.data.metrics.unassigned_managers").value(1))
                .andExpect(jsonPath("$.data.runs[0].run_id").exists())
                .andExpect(jsonPath("$.data.runs[0].bus_no").exists())
                .andExpect(jsonPath("$.data.runs[0].direction").exists())
                .andExpect(jsonPath("$.data.runs[0].depart_time").exists())
                .andExpect(jsonPath("$.data.runs[0].driver_name").value("기사1"))
                .andExpect(jsonPath("$.data.runs[0].escort_name").value("동승자1"))
                .andExpect(jsonPath("$.data.runs[0].boarded_count").exists())
                .andExpect(jsonPath("$.data.runs[0].total_count").value(1))
                .andExpect(jsonPath("$.data.runs[0].run_status").value("confirmed"))
                .andExpect(jsonPath("$.data.runs[0].added_count").exists())
                .andExpect(jsonPath("$.data.runs[0].removed_count").exists())
                .andExpect(jsonPath("$.data.runs[0].ack_driver").value(true))
                .andExpect(jsonPath("$.data.runs[0].ack_escort").value(false))
                .andExpect(jsonPath("$.data.runs[0].no_show_cases").isArray())
                .andReturn();

        // 이 픽스처는 강제 추가·이동 없이 확정해 변경분이 0 이다. run_rider.change 를 쓰는 경로(확정 배치)의
        // 값 검사는 RunConfirmationServiceTest 가 맡는다(BR-016).
        assertThat((Integer) JsonPath.read(본문(result), "$.data.runs[0].added_count")).isZero();
        assertThat((Integer) JsonPath.read(본문(result), "$.data.runs[0].removed_count")).isZero();
    }

    /**
     * BR-220(Ruling 375) — 임시 취소된 회차는 {@code runs[]} 에서 빠지고 지표에도 들지 않는다. 취소된 회차에만 배치된
     * 매니저는 그 회차가 운행하지 않으므로 {@code unassigned_managers} 에 센다.
     */
    @Test
    @DisplayName("BR-220 — 임시 취소된 회차는 runs[] 에서 빠지고 그 회차에만 배치된 매니저는 미배치로 센다")
    void 임시_취소된_회차는_대시보드에서_빠진다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = now().plusHours(1);
        long liveRunId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long canceledRunId = fx.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fx.assignedManager(academyId, liveRunId, ManagerRole.DRIVER, "살아있는기사", now());
        fx.assignedManager(academyId, canceledRunId, ManagerRole.ESCORT, "취소회차동승자", now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");
        runRepository.findById(canceledRunId).orElseThrow().cancel(now(), src.backend.run.entity.RunCancelSource.STAFF);

        mockMvc.perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs.length()").value(1))
                .andExpect(jsonPath("$.data.runs[0].run_id").value(liveRunId))
                .andExpect(jsonPath("$.data.metrics.unassigned_managers").value(1));
    }

    // ── R21-B 목표 1·2·4 — 실제 출발·도착 시각(started_at·finished_at) ─────────

    /**
     * R21-B 목표 1·2·4 — {@code started_at}·{@code finished_at} 은 회차가 실제로 그 상태를
     * 지나야만 값이 찬다("예정"인 {@code depart_time} 과 구별). 확정 직후엔 둘 다 없고, 출발
     * 처리 후엔 {@code started_at} 만, 종료 처리 후엔 둘 다 채워진다 — 세 상태를 한 시험에서
     * 순서대로 확인해 "출발 전에는 실제 값이 없다"는 완료 조건 4의 전제를 직접 본다.
     */
    @Test
    @DisplayName("R21-B 목표1·2·4 — started_at·finished_at 이 회차 진행 상태에 맞춰 채워진다")
    void 실제_출발_도착_시각이_회차_진행에_맞춰_채워진다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = now().plusHours(1);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(
                get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].started_at").doesNotExist())
                .andExpect(jsonPath("$.data.runs[0].finished_at").doesNotExist());

        OffsetDateTime startedAt = departTime.plusMinutes(1);
        fx.startRun(runId, startedAt);

        mockMvc.perform(
                get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].started_at").exists())
                .andExpect(jsonPath("$.data.runs[0].finished_at").doesNotExist());

        OffsetDateTime finishedAt = startedAt.plusMinutes(30);
        fx.finishRun(runId, finishedAt);

        mockMvc.perform(
                get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].started_at").exists())
                .andExpect(jsonPath("$.data.runs[0].finished_at").exists());
    }

    // ── R21-B2 목표 1·2 — 예정 도착(est_arrival_time = depart_time + est_duration_min) ──

    /**
     * R21-B2 목표 1 — {@code est_duration_min} 이 있으면 {@code depart_time} 에 그 분(分)을 더한
     * 값이 {@code est_arrival_time} 으로 나온다. R20-A 가 시드의 {@code est_duration_min} 을 실
     * NCP 응답으로 채워(docs/archive/rounds/be-rounds-r15-r21.md §8.33) 이 값이 이제 실측치라는 전제를 그대로 시험한다.
     */
    @Test
    @DisplayName("R21-B2 목표1 — est_arrival_time 은 depart_time + est_duration_min 이다")
    void 예정_도착_시각이_출발_시각과_소요시간의_합이다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = now().plusHours(1);
        int estDurationMin = 27;
        fx.confirmedRunWithDuration(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30), estDurationMin);
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/dashboard").header("Authorization",
                        토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].est_arrival_time").exists())
                .andReturn();

        // Jackson 직렬화 형식(UTC "Z" 표기)과 테스트가 만든 값(+09:00 표기)은 문자열이 달라도
        // 같은 순간이면 된다 — 원문이 아니라 Instant 로 비교한다.
        OffsetDateTime actual = OffsetDateTime.parse((String) JsonPath.read(본문(result),
                "$.data.runs[0].est_arrival_time"));
        assertThat(actual.toInstant()).isEqualTo(departTime.plusMinutes(estDurationMin).toInstant());
    }

    /**
     * R21-B2 목표 2 — {@code est_duration_min} 이 없는 회차(확정 전 등, 정본이 {@code null} 허용)는
     * {@code est_arrival_time} 도 {@code null} 로 견딘다 — 예외를 던지지 않는다.
     */
    @Test
    @DisplayName("R21-B2 목표2 — est_duration_min 이 없으면 est_arrival_time 도 null 로 견딘다")
    void 소요시간이_없으면_예정_도착_시각도_null_이다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = now().plusHours(1);
        // 기존 confirmedRun 은 est_duration_min 을 항상 null 로 둔다(DriverRunFixtures 자바독).
        fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(
                get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].est_arrival_time").doesNotExist());
    }

    // ── 목표 8 — unassigned_managers 는 소프트 삭제된 매니저를 세지 않는다 ─────

    /**
     * 목표 8 — {@code ManagerRepository.countUnassignedForStaffDashboard} 는 이미
     * {@code deleted_at IS NULL} 조건을 갖고 있다(프로덕션 변경 없음, 회귀 방지 시험만 추가). 소프트
     * 삭제된 미배치 매니저 1명을 심어 두고, 살아있는 미배치 매니저 1명만 집계되는지 본다.
     *
     * <p>오늘 회차를 1건 만들어야 한다 — {@code StaffDashboardQueryService.dashboard} 는 오늘 회차가
     * 없으면 {@code countActiveForStaffDashboard}(별도 메서드, 이 시험의 검사 대상이 아니다)로 빠진다
     * (수정 라운드 1 — 팀리드 재지시, 원래 판은 회차를 안 만들어 검사 대상 쿼리를 한 번도 안 태웠다).
     */
    @Test
    @DisplayName("목표8 — 소프트 삭제된 미배치 매니저는 unassigned_managers 에 잡히지 않는다")
    void 소프트_삭제된_매니저는_unassigned_managers_에_잡히지_않는다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = now().plusHours(1);
        fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fx.unassignedManager(academyId, ManagerRole.DRIVER, "미배치기사1");
        long deletedAccountId = fx.unassignedManager(academyId, ManagerRole.DRIVER, "삭제될기사");
        Manager deletedManager = managerRepository.findByAccountId(deletedAccountId).orElseThrow();
        deletedManager.delete(now());
        managerRepository.save(deletedManager);
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.unassigned_managers")
                        .value(1));
    }

    // ── 목표 2 — §5.4 기존 명단 검증(고치지 않는다) ───────────────────────

    /**
     * 목표 2 — {@code StaffRosterController} 를 대시보드가 만든 {@code run_id} 로 그대로 호출해
     * §5.4 필드 키를 대조한다. 새 컨트롤러를 만들지 않는다.
     */
    @Test
    @DisplayName("목표2 — 대시보드 run_id 로 기존 명단 API 를 호출하면 §5.4 필드가 전부 있다")
    void 대시보드_run_id_로_기존_명단_API_를_검증한다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        OffsetDateTime departTime = now().plusHours(1);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long versionId = fx.confirmedRouteWithVersion(runId, departTime.minusMinutes(40));
        fx.runStopForStop(versionId, stopId, 1, departTime);
        long studentId = fx.student(academyId, "학생1");
        fx.rider(runId, studentId, stopId, src.backend.boarding.entity.RiderStatus.WAITING, now());
        fx.guardianOf(academyId, studentId, "보호자1", now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        MvcResult dashboard = mockMvc
                .perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andReturn();
        Long dashboardRunId = Long.parseLong(JsonPath.read(본문(dashboard), "$.data.runs[0].run_id"));
        assertThat(dashboardRunId).isEqualTo(runId);

        mockMvc.perform(get("/api/v1/staff/runs/" + dashboardRunId + "/roster").header("Authorization",
                토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].student_id").exists())
                .andExpect(jsonPath("$.data[0].name").exists())
                .andExpect(jsonPath("$.data[0].stop_name").exists())
                .andExpect(jsonPath("$.data[0].guardian_phone").exists())
                .andExpect(jsonPath("$.data[0].status").value("waiting"));
        // class_name·change·note 는 선택 필드라 이 시나리오에서는 null 로 내려간다(§5.4 표의
        // 필수(●)·선택(○) 구분 그대로) — 존재 자체는 이 rider 가 change=null 인 것으로 이미 확인됨.
        // items[] 래핑 여부는 §5.4 정본 산문("items[] (학생 단위 표)")과 실제 응답(전역 ApiResponse
        // 봉투 안 bare List)이 다르다 — StaffRosterControllerTest 도 $.data[0] 로 이미 접근하는
        // 기존 관례라 코드 결함이 아니라 정본 산문의 서술 부정확으로 판단(확신 없는 지점, 보고서 §2).
    }

    // ── 목표 3 — 실제 승하차 처리 반영 ──────────────────────────────────

    /**
     * 목표 3 — 실제 {@code PATCH /runs/{runId}/riders/{riderId}} 호출로 {@code boarded_count}·
     * {@code metrics.boarded} 가 반영되고, {@code no_show} 처리는 {@code no_show_cases[]} 에 뜨며,
     * 실제 {@code POST .../no-show-contacts} 응답(해소) 뒤에는 제외된다.
     */
    @Test
    @DisplayName("목표3 — 실제 승하차 PATCH 로 boarded_count 가 반영되고 미승차는 해소되면 제외된다")
    void 실제_승하차_처리가_대시보드에_반영된다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        OffsetDateTime departTime = now().minusMinutes(10);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fx.startRun(runId, now());
        long versionId = fx.confirmedRouteWithVersion(runId, departTime.minusMinutes(40));
        fx.runStopForStop(versionId, stopId, 1, departTime);
        long boardedStudentId = fx.student(academyId, "탑승학생");
        long noShowStudentId = fx.student(academyId, "미승차학생");
        long boardedRiderId = fx.rider(runId, boardedStudentId, stopId,
                src.backend.boarding.entity.RiderStatus.WAITING, now());
        long noShowRiderId = fx.rider(runId, noShowStudentId, stopId,
                src.backend.boarding.entity.RiderStatus.WAITING, now());
        long escortAccountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자1", now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        // 실제 승하차 처리 — PATCH 로 boarded 반영(완료 기준 3).
        mockMvc.perform(patch("/api/v1/runs/" + runId + "/riders/" + boardedRiderId)
                .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "boarded", "verify_method": "manual", "client_key": "%s"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());

        // 실제 미승차 처리 — no_show 로 전이시켜 NoShowCase 를 실제로 생성.
        mockMvc.perform(patch("/api/v1/runs/" + runId + "/riders/" + noShowRiderId)
                .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "no_show", "verify_method": "manual", "client_key": "%s"}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].boarded_count").value(1))
                .andExpect(jsonPath("$.data.metrics.boarded").value(1))
                .andExpect(jsonPath("$.data.runs[0].no_show_cases", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.runs[0].no_show_cases[0].student_name").value("미승차학생"))
                .andExpect(jsonPath("$.data.runs[0].no_show_cases[0].stop_name").exists())
                .andExpect(jsonPath("$.data.runs[0].no_show_cases[0].expires_at").exists());

        // 실제 연락 시도(응답함)로 해소 — 실제 POST .../no-show-contacts.
        mockMvc.perform(post("/api/v1/runs/" + runId + "/riders/" + noShowRiderId + "/no-show-contacts")
                .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"attempt_type": "call", "result": "answered"}
                        """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].no_show_cases", org.hamcrest.Matchers.hasSize(0)));
    }

    // ── 목표 4(뒷항) — 재배포 후 ack 리셋 ────────────────────────────────

    /**
     * 목표 4 뒷항 — 재배포(새 {@code RouteVersion} 이 {@code currentVersionId} 를 갈아치움) 후
     * {@code ack_driver}·{@code ack_escort} 가 {@code false} 로 되돌아간다({@code ERD:632} 판정식,
     * {@code StaffAssignmentAckView#acked} — 버전 비교이지 {@code acked_at != null} 단순 확인이
     * 아님).
     *
     * <p>재배포는 {@code POST /staff/approvals/{id}/decide} 전체 흐름(지문 재검증 등, {@code request/}
     * 모듈 범위 밖) 대신 새 {@code RouteVersion}(버전 2)을 직접 저장하고
     * {@code ConfirmedRouteRepository#assignCurrentVersion} 을 호출해 재현한다 — 이 메서드가
     * {@code ChangeRequestDecisionService#approve} 가 실제로 부르는 것과 정확히 같은 프로덕션
     * 메서드라, 판정 로직(버전 비교) 검증 목적에는 동등하다고 판단(확신 없는 지점, 보고서 §2).
     * {@link DriverRunFixtures#confirmedRouteWithVersion} 을 그대로 두 번째로 호출하지 않는 이유는
     * 그 메서드가 매번 {@code ConfirmedRoute} 도 함께 새로 저장해 {@code run_id} 기준 PK 충돌
     * (DataIntegrityViolationException)이 나기 때문이다.
     */
    @Test
    @DisplayName("목표4뒷항 — 재배포 후 ack_driver·ack_escort 가 false 로 되돌아간다")
    void 재배포_후_ack_이_리셋된다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = now().plusHours(1);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fx.confirmedRouteWithVersion(runId, departTime.minusMinutes(40));
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", now());
        long escortAccountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자1", now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes").header("Authorization",
                토큰(driverAccountId, academyId, Role.DRIVER))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes").header("Authorization",
                토큰(escortAccountId, academyId, Role.ESCORT))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].ack_driver").value(true))
                .andExpect(jsonPath("$.data.runs[0].ack_escort").value(true));

        // 재배포 — confirmed_route 는 이미 있으니 새 RouteVersion(버전 2)만 저장하고
        // currentVersionId 를 갈아치운다(위 javadoc 의 판단 근거 참고).
        RouteVersion redeployedVersion = routeVersionRepository.save(RouteVersion.forConfirmedRoute(runId, 2,
                RouteVersionSource.CONFIRM_BATCH, 30, new BigDecimal("10.00"), now(), "fp-" + runId + "-v2",
                "engine-v1", Map.of(), false, List.of(), null, now()));
        confirmedRouteRepository.assignCurrentVersion(runId, redeployedVersion.getId());

        mockMvc.perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].ack_driver").value(false))
                .andExpect(jsonPath("$.data.runs[0].ack_escort").value(false));
    }

    // ── 목표 5 — 학원 범위 격리 ──────────────────────────────────────────

    /**
     * 목표 5 — 관계자 토큰 응답에 타 학원 회차가 부재하고, 타 학원 회차의 명단 조회는 실패한다.
     *
     * <p>{@code RosterQueryService.staffRoster} 가 존재 판정({@code findById})과 학원 범위 판정
     * ({@link src.backend.global.security.access.AcademyScope#assertAccessible})을 분리하도록
     * 고쳐(API_SPEC §1.5, Ruling 239, 2026-09-03 사용자 판정 ②) 타 학원 회차는 {@code 403
     * ACADEMY_SCOPE_VIOLATION} 을 던진다 — 존재하지 않는 회차 id 만 {@code 404 RUN_NOT_FOUND} 다.
     */
    @Test
    @DisplayName("목표5 — 타 학원 회차는 대시보드에 없고 그 회차 명단 조회는 403 이다")
    void 타_학원_회차는_보이지_않고_명단_조회는_실패한다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long myAcademyId = fx.academy();
        long myBusId = fx.bus(myAcademyId);
        OffsetDateTime departTime = now().plusHours(1);
        long myRunId = fx.confirmedRun(myAcademyId, myBusId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long staffAccountId = fx.staffAccount(myAcademyId, "관계자1");

        long otherAcademyId = fx.academy();
        long otherBusId = fx.bus(otherAcademyId);
        long otherRunId = fx.confirmedRun(otherAcademyId, otherBusId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/dashboard").header("Authorization", 토큰(staffAccountId, myAcademyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andReturn();
        // run_id 는 문자열로 나간다(API_SPEC §1.1, Ruling 332) — Long 인 myRunId·otherRunId 와
        // 비교하려면 파싱해서 맞춘다.
        List<String> rawRunIds = JsonPath.read(본문(result), "$.data.runs[*].run_id");
        List<Long> runIds = rawRunIds.stream().map(Long::parseLong).toList();
        assertThat(runIds).contains(myRunId).doesNotContain(otherRunId);

        mockMvc.perform(get("/api/v1/staff/runs/" + otherRunId + "/roster").header("Authorization",
                토큰(staffAccountId, myAcademyId, Role.STAFF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
