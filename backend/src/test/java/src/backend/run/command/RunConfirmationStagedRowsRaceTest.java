package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunTransfer;
import src.backend.run.repository.RunRepository;
import src.backend.run.repository.RunTransferRepository;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.roster.ProjectedRosterReader;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20260827Config;

/**
 * BR-044 — 확정 계산이 끝난 뒤 <b>이동 대기 1건이 취소되고 새 1건이 등록</b>되면 행 수는 그대로인데 명단은
 * 다르다. {@link RunConfirmationPersistence#persist} 가 수가 아니라 행 id 집합으로 비교해 이 낡은 명단을 막는지
 * 재현한다(R36-BE 목표 5).
 *
 * <p>불일치를 잡으면 {@code confirmIfIdle} 이후 첫 검사에서 던지므로 계산 결과·끝점은 아직 쓰이지 않는다 —
 * 그래서 두 인자에 {@code null} 을 넘긴다. 옛 수 비교로 되돌리면 검사를 통과해 그 {@code null} 을 밟는
 * {@link NullPointerException} 이 나 이 시험이 실패한다.
 */
@SpringBootTest
@Transactional
@Import(FixedClock20260827Config.class)
class RunConfirmationStagedRowsRaceTest {

    private static final long STAFF_ACCOUNT_ID = 9101L;

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
    private RunTransferRepository runTransferRepository;

    @Autowired
    private ProjectedRosterReader rosterReader;

    @Autowired
    private RunConfirmationPersistence persistence;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("BR-044 — 계산 뒤 이동 취소 1 + 새 이동 1 이 끼어 행 수가 같아도 확정 저장은 낡은 명단을 거절한다")
    void 계산_뒤_취소_1과_등록_1이_끼면_수가_같아도_확정_저장이_거절한다() {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
        long academyId = fixtures.academyWithCoordinates();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime departTime = now.plusMinutes(31);
        Run fromRun = runRepository.findById(fixtures.idleRun(academyId, fixtures.bus(academyId), now.toLocalDate(),
                Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30))).orElseThrow();
        Run toRun = runRepository.findById(fixtures.idleRun(academyId, fixtures.bus(academyId), now.toLocalDate(),
                Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30))).orElseThrow();
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long firstStudent = fixtures.student(academyId, "먼저이동");
        long secondStudent = fixtures.student(academyId, "나중이동");

        RunTransfer first = runTransferRepository.saveAndFlush(
                RunTransfer.stage(firstStudent, fromRun.getId(), toRun.getId(), stopId, null, STAFF_ACCOUNT_ID, now));
        // 확정 계산이 도착 회차 명단을 읽은 시점 — 이동 대기 1건이 보인다.
        ProjectedRoster stale = rosterReader.read(toRun);

        // 계산이 도는 사이 관계자가 그 대기 건을 취소하고 다른 학생을 새로 이동시킨다 — 행 수는 여전히 1.
        runTransferRepository.delete(first);
        runTransferRepository.saveAndFlush(
                RunTransfer.stage(secondStudent, fromRun.getId(), toRun.getId(), stopId, null, STAFF_ACCOUNT_ID, now));

        assertThatThrownBy(() -> persistence.persist(toRun, null, null, Weekday.of(toRun.getServiceDate()), stale,
                now)).isInstanceOf(IllegalStateException.class).hasMessageContaining("다음 틱");
    }
}
