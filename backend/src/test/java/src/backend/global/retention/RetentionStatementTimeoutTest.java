package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * DA-4 — 보존 정리 중 JDBC 로 도는 멱등 키 비우기와 학생 익명화도 <b>자기 트랜잭션 안에서만</b> {@code statement_timeout=0} 이다(조회는 풀의 30초 그대로).
 * 알림 로그 같은 저장소 삭제는 {@code DataAccessSettingsTest} 가 본다.
 */
@SpringBootTest
class RetentionStatementTimeoutTest {

    @Autowired private IdempotencyKeyExpiry idempotencyKeyExpiry;
    @Autowired private StudentAnonymizationService anonymizationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StudentRepository studentRepository;
    @MockitoSpyBean(name = "jdbcTemplate") private JdbcTemplate spiedJdbc;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private StopRepository stopRepository;
    @MockitoSpyBean private AccountRepository accountRepository;
    @Autowired private ManagerRepository managerRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private ConfirmedRouteRepository confirmedRouteRepository;
    @Autowired private RouteVersionRepository routeVersionRepository;
    @Autowired private RunStopRepository runStopRepository;
    @Autowired private RunRiderRepository runRiderRepository;
    @Autowired private AcademyStaffRepository academyStaffRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private GuardianStudentRepository guardianStudentRepository;
    @Autowired private ChangeRequestRepository changeRequestRepository;

    private Long academyId;

    @AfterEach
    void 뒷정리한다() {
        if (academyId != null) {
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
    }

    @Test
    @DisplayName("DA-4 — 멱등 키 비우기의 UPDATE 는 statement_timeout 0 인 트랜잭션 안에서 돈다")
    void 멱등_키_비우기는_시간_상한이_풀린_트랜잭션에서_돈다() {
        List<String> seen = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            JdbcTemplate self = (JdbcTemplate) invocation.getMock();
            seen.add(self.queryForObject("SHOW statement_timeout", String.class));
            return invocation.callRealMethod();
        }).when(spiedJdbc).update(anyString(), any(Object[].class));

        idempotencyKeyExpiry.expire(OffsetDateTime.now().minusYears(50), 10);

        assertThat(seen).as("UPDATE 시점에 본 statement_timeout").isNotEmpty().containsOnly("0");
        assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class)).as("트랜잭션 밖").isEqualTo("30s");
    }

    @Test
    @DisplayName("DA-4 — 학생 익명화의 파기 트랜잭션 안에서는 0, 밖에서는 30초다")
    void 익명화는_파기_트랜잭션_안에서만_시간_상한이_풀린다() {
        DriverRunFixtures fx = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        academyId = fx.academy();
        long studentId = fx.student(academyId, "퇴원학생");
        jdbcTemplate.update("UPDATE student SET deleted_at = now() - interval '100 days' WHERE id = ?", studentId);
        List<String> seen = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            seen.add(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class));
            return List.of();
        }).when(accountRepository).findAllByIdIn(any());

        int purged = anonymizationService.anonymize(List.of(studentId));

        assertThat(purged).isEqualTo(1);
        assertThat(seen).as("파기 트랜잭션 안(계정 조회 시점)에서 본 statement_timeout").containsExactly("0");
        assertThat(jdbcTemplate.queryForObject("SHOW statement_timeout", String.class)).as("트랜잭션 밖").isEqualTo("30s");
    }
}
