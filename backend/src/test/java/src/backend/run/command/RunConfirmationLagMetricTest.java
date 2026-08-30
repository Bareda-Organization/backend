package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 배치 도래→완료 지연 지표(Phase 7 목표 8) — {@link RunConfirmationService#confirmOne} 이 확정할
 * 때마다 {@code run.confirm_at} 부터 완료 시각까지의 차를 정확히 기록하는지, 그리고 실패해 {@code idle}
 * 로 되돌아간 시도는 기록에서 빠지되 재시도 성공분이 그 실패 구간까지 포함한 누적 지연으로 잡히는지를
 * 본다. {@code RunConfirmationServiceTest} 는 4종 산출물의 <b>내용</b>을 보고, 이 클래스는 그 확정이
 * 남기는 <b>지연 값</b>만 본다.
 *
 * <p>{@code MeterRegistry} 는 스프링 컨텍스트에서 싱글턴이라 이 클래스 밖의 다른 시험도 같은 이름의
 * {@code Timer} 를 누적시킬 수 있다 — 그래서 매 단언은 <b>호출 전후의 차(delta)</b>로만 판정한다
 * (절대 {@code count()}·{@code totalTime()} 의 원값을 특정 수치와 비교하지 않는다).
 */
@SpringBootTest
@Transactional
class RunConfirmationLagMetricTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    private static final String LAG_METRIC = "schoolbus.run.confirmation.lag";

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
    private Clock clock;

    @Autowired
    private MeterRegistry registry;

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

    /** 이름은 같아도 태그가 없어 registry 전체에 하나뿐이다 — count·totalTime 을 델타 기준점으로 쓴다. */
    private Timer currentTimerOrNull() {
        return registry.find(LAG_METRIC).timer();
    }

    private long countOf(Timer timer) {
        return timer == null ? 0 : timer.count();
    }

    private long totalNanosOf(Timer timer) {
        return timer == null ? 0 : (long) timer.totalTime(TimeUnit.NANOSECONDS);
    }

    @Test
    @DisplayName("목표8 — 도래(confirm_at)로부터 17분 밀려 확정된 회차의 지연이 정확히 기록된다")
    void 확정_지연이_판정_시각부터_완료_시각까지의_차로_기록된다() {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures().stop(academyId, "37.561000", "126.971000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);
        long student = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(student, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");

        // confirm_at 을 고정 시계 기준 17분 "전"으로 심어 밀림 상황을 직접 만든다 — 도래 직후 처리되는
        // 시험이었다면 지연이 항상 0이라 실행 시간을 재도 통과했을 것이다.
        OffsetDateTime confirmAt = OffsetDateTime.now(clock).minusMinutes(17);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY,
                confirmAt.plusMinutes(30), confirmAt);

        Timer before = currentTimerOrNull();
        long countBefore = countOf(before);
        long totalBefore = totalNanosOf(before);

        confirmationService.confirmOne(runId);

        Timer after = currentTimerOrNull();
        assertThat(after).as("확정에 성공했으니 지표가 실재해야 한다").isNotNull();
        assertThat(after.count() - countBefore).as("이 확정 1건만큼만 늘어야 한다").isEqualTo(1);
        assertThat(after.totalTime(TimeUnit.NANOSECONDS) - totalBefore)
                .as("기록된 값이 confirm_at~완료 시각의 차(17분)와 정확히 같아야 한다 — 실행 시간이었다면 ms 단위였을 것이다")
                .isEqualTo((double) Duration.ofMinutes(17).toNanos());
    }

    @Test
    @DisplayName("목표8-보완 — 실패한 시도는 기록되지 않고, 재시도 성공분이 실패 구간까지 포함한 누적 지연으로 잡힌다")
    void 실패한_시도는_기록되지_않고_재시도_성공은_누적_지연을_기록한다() {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures().stop(academyId, "37.561000", "126.971000");
        // 아직 노선을 편성하지 않는다 — 첫 시도는 ROUTE_NOT_CONFIGURED_FOR_RUN 으로 반드시 실패한다.

        OffsetDateTime confirmAt = OffsetDateTime.now(clock).minusMinutes(20);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY,
                confirmAt.plusMinutes(30), confirmAt);

        Timer beforeFirstAttempt = currentTimerOrNull();
        long countBeforeFirstAttempt = countOf(beforeFirstAttempt);

        assertThatThrownBy(() -> confirmationService.confirmOne(runId)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);

        Timer afterFailedAttempt = currentTimerOrNull();
        assertThat(countOf(afterFailedAttempt) - countBeforeFirstAttempt)
                .as("confirmedAt 을 확보하기 전에 예외로 빠졌으니 지표가 늘면 안 된다").isZero();

        // 노선을 뒤늦게 편성해 재시도가 성공하도록 만든다 — confirm_at 은 처음 심은 값 그대로다.
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, lastStop);
        long student = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(student, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");

        long totalBeforeRetry = totalNanosOf(afterFailedAttempt);

        confirmationService.confirmOne(runId);

        Timer afterRetry = currentTimerOrNull();
        assertThat(afterRetry.count() - countBeforeFirstAttempt).as("재시도 성공 1건만 늘어야 한다").isEqualTo(1);
        assertThat(afterRetry.totalTime(TimeUnit.NANOSECONDS) - totalBeforeRetry)
                .as("최초 confirm_at(20분 전) 부터의 누적 지연이어야 한다 — 재시도 시점부터 다시 재면 훨씬 짧게 나온다")
                .isEqualTo((double) Duration.ofMinutes(20).toNanos());
    }
}
