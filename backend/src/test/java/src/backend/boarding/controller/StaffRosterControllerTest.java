package src.backend.boarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.run.entity.RunTransfer;
import src.backend.run.repository.RunTransferRepository;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.Phase9RosterFixtures;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * §5.4 {@code GET /staff/runs/{runId}/roster} — RST-03·A-04, Phase 9 목표 6.
 *
 * <p>{@link RunRosterControllerTest}(§4.2)와 정확히 반대되는 두 지점을 본다 — 보호자 연락처가
 * <b>원문 그대로</b> 내려가는지, 결석 학생이 <b>행으로 남아</b> {@code status=absent} 로 표시되는지.
 * 확정 전(idle) 회차도 매니저 앱과 달리 막히지 않는다 — 학원 관계자는 배치와 무관하게 그 학원
 * 회차 전체를 볼 권한을 이미 가졌다({@code STUDENT_READ_SENSITIVE}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffRosterControllerTest {

    private static final String SERVICE_DATE = "2031-07-03";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

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
    private ManagerRepository managerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private BoardingIntentRepository boardingIntentRepository;

    @Autowired
    private RunTransferRepository runTransferRepository;

    private Phase9RosterFixtures fixtures() {
        RunConfirmationFixtures base = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        return new Phase9RosterFixtures(base, managerRepository, accountRepository, assignmentRepository,
                guardianRepository, guardianStudentRepository, confirmationService);
    }

    @Test
    void 보호자_연락처는_원문_그대로_내려간다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.guardianWithPhone(academyId, studentId, "010-2345-8814");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        long staffAccountId = 관계자_계정을_만든다(academyId);

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/runs/" + runId + "/roster").header("Authorization",
                        토큰(staffAccountId, academyId)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat((String) JsonPath.read(본문(result), "$.data[0].guardian_phone")).isEqualTo("010-2345-8814");
    }

    @Test
    void 결석_학생도_행으로_남고_상태만_absent_다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        결석_처리한다(academyId, runId, studentId);
        long staffAccountId = 관계자_계정을_만든다(academyId);

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/runs/" + runId + "/roster").header("Authorization",
                        토큰(staffAccountId, academyId)))
                .andExpect(status().isOk())
                .andReturn();

        String body = 본문(result);
        List<?> items = JsonPath.read(body, "$.data");
        assertThat(items).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.data[0].status")).isEqualTo("absent");
    }

    /** 확정 전(idle) 회차는 요일별 주소 기준 예정 명단이다 — {@code status=waiting} · {@code change} 부재(§5.4). */
    @Test
    void 확정_전_회차는_예정_명단을_준다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "예정학생");
        fx.verifiedAddress(studentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.guardianWithPhone(academyId, studentId, "010-2345-8814");
        long runId = 확정_전_회차(fx, academyId, busId);
        long staffAccountId = 관계자_계정을_만든다(academyId);

        String body = 명단을_읽는다(runId, staffAccountId, academyId);

        assertThat((List<?>) JsonPath.read(body, "$.data")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.data[0].student_id")).isEqualTo(String.valueOf(studentId));
        assertThat((String) JsonPath.read(body, "$.data[0].status")).isEqualTo("waiting");
        assertThat((Object) JsonPath.read(body, "$.data[0].change")).isNull();
        assertThat((String) JsonPath.read(body, "$.data[0].guardian_phone")).isEqualTo("010-2345-8814");
    }

    /** 탑승 OFF(riding=false) 학생은 예정 명단에 넣지 않는다. */
    @Test
    void 확정_전_예정_명단에서_탑승_OFF_학생은_빠진다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long ridingId = fx.student(academyId, "탑승학생");
        long offId = fx.student(academyId, "탑승OFF학생");
        fx.verifiedAddress(ridingId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.verifiedAddress(offId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        long runId = 확정_전_회차(fx, academyId, busId);
        BoardingIntent intent = BoardingIntent.forRun(runId, offId, OffsetDateTime.now());
        intent.applyRiding(false, ChangeWindow.IMMEDIATE, OffsetDateTime.now(), null);
        boardingIntentRepository.save(intent);
        long staffAccountId = 관계자_계정을_만든다(academyId);

        String body = 명단을_읽는다(runId, staffAccountId, academyId);

        List<String> studentIds = JsonPath.read(body, "$.data[*].student_id");
        assertThat(studentIds).containsExactly(String.valueOf(ridingId));
    }

    /**
     * 이동 대기(staged)는 출발 회차에서 빼고 도착 회차에 넣는다 — 확정 배치와 §5.8 판정이 쓰는 계산 그대로다.
     * 방금 옮긴 학생이 출발 명단에 그대로 보이면 관계자가 다시 옮기려다 TRANSFER_ALREADY_STAGED 를 받는다.
     */
    @Test
    void 확정_전_예정_명단은_이동_대기를_출발에서_빼고_도착에_넣는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long fromBusId = fx.bus(academyId);
        long toBusId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        long toStopId = fx.stop(academyId, "37.510000", "127.010000");
        fx.route(academyId, fromBusId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "이동학생");
        fx.verifiedAddress(studentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        long fromRunId = 확정_전_회차(fx, academyId, fromBusId);
        long toRunId = 확정_전_회차(fx, academyId, toBusId);
        runTransferRepository.save(RunTransfer.stage(studentId, fromRunId, toRunId, toStopId, null, 1L,
                OffsetDateTime.now()));
        long staffAccountId = 관계자_계정을_만든다(academyId);

        String fromBody = 명단을_읽는다(fromRunId, staffAccountId, academyId);
        String toBody = 명단을_읽는다(toRunId, staffAccountId, academyId);

        assertThat((List<?>) JsonPath.read(fromBody, "$.data")).isEmpty();
        assertThat((List<?>) JsonPath.read(toBody, "$.data")).hasSize(1);
        assertThat((String) JsonPath.read(toBody, "$.data[0].student_id")).isEqualTo(String.valueOf(studentId));
        assertThat((String) JsonPath.read(toBody, "$.data[0].status")).isEqualTo("waiting");
    }

    /** 확정 뒤에는 run_rider 만 읽는다 — 확정 뒤 요일별 주소가 바뀌어도 응답이 그대로다. */
    @Test
    void 확정_뒤_회차는_요일별_주소가_바뀌어도_run_rider_그대로다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "확정학생");
        fx.verifiedAddress(studentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        long lateStudentId = fx.student(academyId, "확정뒤학생");
        fx.verifiedAddress(lateStudentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        long staffAccountId = 관계자_계정을_만든다(academyId);

        String body = 명단을_읽는다(runId, staffAccountId, academyId);

        List<String> studentIds = JsonPath.read(body, "$.data[*].student_id");
        assertThat(studentIds).containsExactly(String.valueOf(studentId));
    }

    private long 확정_전_회차(Phase9RosterFixtures fx, long academyId, long busId) {
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        return fx.idleRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
    }

    private String 명단을_읽는다(long runId, long staffAccountId, long academyId) throws Exception {
        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/runs/" + runId + "/roster").header("Authorization",
                        토큰(staffAccountId, academyId)))
                .andExpect(status().isOk())
                .andReturn();
        return 본문(result);
    }

    /**
     * API_SPEC §1.5, Ruling 239, 2026-09-03 사용자 판정 ② — 존재하는 회차라도 요청자 소속 학원과
     * 다르면 {@code 403 ACADEMY_SCOPE_VIOLATION}(옛 동작은 조회 조건에 학원 id 를 섞어 404 로 뭉갰다).
     */
    @Test
    void 타_학원_회차_명단_조회는_403_이다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long myAcademyId = fx.academyWithCoordinates();
        long staffAccountId = 관계자_계정을_만든다(myAcademyId);

        long otherAcademyId = fx.academyWithCoordinates();
        long otherBusId = fx.bus(otherAcademyId);
        long otherStopId = fx.stop(otherAcademyId, "37.500000", "127.000000");
        fx.route(otherAcademyId, otherBusId, Weekday.THU, Direction.TO_ACADEMY, otherStopId);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long otherRunId = fx.confirmedRun(otherAcademyId, otherBusId, LocalDate.parse(SERVICE_DATE),
                Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));

        mockMvc.perform(get("/api/v1/staff/runs/" + otherRunId + "/roster").header("Authorization",
                토큰(staffAccountId, myAcademyId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));
    }

    /** 존재하지 않는 회차 id 는 학원 범위와 무관하게 {@code 404 RUN_NOT_FOUND} 그대로다. */
    @Test
    void 존재하지_않는_회차는_404_다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long staffAccountId = 관계자_계정을_만든다(academyId);

        mockMvc.perform(get("/api/v1/staff/runs/999999999/roster").header("Authorization",
                토큰(staffAccountId, academyId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    /** BR-227 — 메인 관리자(학원 id 없음)도 권한표상 이 경로를 읽는다: 실재 회차의 명단이 그 회차 학원 기준으로 나와야 한다. */
    @Test
    void 메인_관리자는_실재_회차의_명단을_받는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.guardianWithPhone(academyId, studentId, "010-2345-8814");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long confirmedRunId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        String adminToken = "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);

        MvcResult confirmed = mockMvc
                .perform(get("/api/v1/staff/runs/" + confirmedRunId + "/roster").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn();

        List<?> items = JsonPath.read(본문(confirmed), "$.data");
        assertThat(items).hasSize(1);
        assertThat((String) JsonPath.read(본문(confirmed), "$.data[0].guardian_phone")).isEqualTo("010-2345-8814");
    }

    private long 관계자_계정을_만든다(long academyId) {
        Account account = accountRepository.save(Account.forSignup(academyId,
                "p9staff" + System.nanoTime(), "{noop}password", "관계자", "010-0000-0000", null, Role.STAFF));
        return account.getId();
    }

    private void 결석_처리한다(long academyId, long runId, long studentId) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(runId, academyId);
        RunRider target = riders.stream().filter(rider -> rider.getStudentId() == studentId).findFirst()
                .orElseThrow();
        target.markAbsent(OffsetDateTime.now());
        runRiderRepository.save(target);
    }

    private String 토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
