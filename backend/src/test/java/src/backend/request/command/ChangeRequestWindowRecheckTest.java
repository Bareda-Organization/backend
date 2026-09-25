package src.backend.request.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.AuthUser;
import src.backend.request.dto.ChangeRequestCreateRequest;
import src.backend.request.dto.ChangeRequestCreateResponse;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.command.AddressVerification;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.GuardianStudent;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * BR-074 — 변경 신청의 구간 판정은 저장 시점의 시계로 다시 해야 한다. 주소 검증(외부 지오코딩)을 트랜잭션
 * 밖에서 기다리는 동안 확정 시각(출발 30분 전)이 지나면, 처음 판정한 ①로 저장해 "즉시 반영" 이라 답하지만
 * 확정 배치는 이미 그 회차를 확정해 노선에 반영되지 않는다.
 */
@SpringBootTest
@Transactional
class ChangeRequestWindowRecheckTest {

    private static final Instant START = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01(월) 12:00 KST

    @TestConfiguration
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    /** 지오코딩이 걸리는 시간만큼 시계를 앞으로 보내려고 쓰는 시계. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void reset() {
            now = START;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    private MutableClock clock;

    @MockitoBean
    private AddressVerification addressVerification;

    @Autowired
    private ChangeRequestCommandService changeRequestCommandService;

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
    private AccountRepository accountRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Test
    void 주소_검증_중_확정_시각이_지나면_승인대기로_접수된다() {
        clock.reset();
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        fixtures.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        long studentId = fixtures.student(academyId, "재판정학생");
        fixtures.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        OffsetDateTime now = OffsetDateTime.now(clock);
        long runId = fixtures.idleRun(academyId, busId, LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY,
                now.plusMinutes(31), now.plusMinutes(1));
        long parentAccountId = parentOf(academyId, studentId, now);

        // 확정 시각(1분 뒤)을 넘길 만큼 주소 검증이 걸린다.
        given(addressVerification.verifySingle(anyString())).willAnswer(invocation -> {
            clock.advance(Duration.ofMinutes(2));
            return new GeocodedPoint(new BigDecimal("37.561000"), new BigDecimal("126.971000"), "서울시 새 주소");
        });

        ChangeRequestCreateResponse response = changeRequestCommandService.submit(
                new AuthUser(parentAccountId, academyId, Role.PARENT, AccountStatus.ACTIVE), studentId,
                new ChangeRequestCreateRequest("relocate", runId, "서울시 새 주소", null));

        assertThat(response.result()).isEqualTo("pending_approval");
    }

    private long parentOf(long academyId, long studentId, OffsetDateTime now) {
        Account account = accountRepository.save(Account.forSignup(academyId, "recheck" + System.nanoTime(),
                "{noop}password", "보호자", "010-0000-0000", null, Role.PARENT));
        long guardianId = guardianRepository.save(Guardian.forSignup(academyId, account.getId(), "보호자",
                "010-0000-0000")).getId();
        guardianStudentRepository.save(GuardianStudent.uponLink(guardianId, studentId, now));
        return account.getId();
    }
}
