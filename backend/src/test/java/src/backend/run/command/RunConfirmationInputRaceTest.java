package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Weekday;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.roster.ProjectedRosterReader;
import testsupport.clock.FixedClock20300401Config;

/**
 * 확정 계산이 끝난 뒤, 확정 저장이 회차 행을 잡은 시점에 입력을 다시 읽어 낡은 명단을 알아챈다 — 명단은 확정 계산 전에 짧은 별도 트랜잭션들로 읽으므로, 그 사이 학부모의 ①구간 탑승 끄기나 승인된
 * 승하차지 이동이 커밋되면 그 변경이 빠진 채 {@code confirmed} 가 된다. 불일치를 잡으면 저장은 던지고(롤백되어 {@code idle} 로 남아 다음 틱이 다시 읽는다) 계산 결과 · 끝점은 쓰이지 않으므로 두 인자에
 * {@code null} 을 넘긴다 — 검사가 없으면 그 {@code null} 을 밟는 {@link NullPointerException} 이 나 이 시험이 실패한다.
 */
@SpringBootTest
@Transactional
@Import(FixedClock20300401Config.class)
class RunConfirmationInputRaceTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    @Autowired private ApplicationContext applicationContext;
    @Autowired private Clock clock;
    @Autowired private RunRepository runRepository;
    @Autowired private ProjectedRosterReader rosterReader;
    @Autowired private RunConfirmationPersistence persistence;
    @Autowired private BoardingIntentRepository boardingIntentRepository;
    @Autowired private ChangeRequestRepository changeRequestRepository;

    @Test
    @DisplayName("명단을 읽은 뒤 ①구간 탑승 끄기가 커밋되면 확정 저장은 낡은 명단을 거절한다")
    void 명단을_읽은_뒤_탑승_끄기가_끼면_거절한다() {
        Fixture f = 준비한다();
        ProjectedRoster stale = rosterReader.read(f.run);
        assertThat(stale.contains(f.studentId)).isTrue();

        OffsetDateTime now = OffsetDateTime.now(clock);
        BoardingIntent intent = BoardingIntent.forRun(f.run.getId(), f.studentId, now);
        intent.applyRiding(false, ChangeWindow.IMMEDIATE, now, f.requesterAccountId);
        boardingIntentRepository.saveAndFlush(intent);

        assertThatThrownBy(() -> persistence.persist(f.run, null, null, Weekday.MON, stale, now))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("다음 틱");
    }

    @Test
    @DisplayName("명단을 읽은 뒤 승하차지 이동 승인이 커밋되면 확정 저장은 낡은 명단을 거절한다")
    void 명단을_읽은_뒤_이동_승인이_끼면_거절한다() {
        Fixture f = 준비한다();
        ProjectedRoster stale = rosterReader.read(f.run);

        OffsetDateTime now = OffsetDateTime.now(clock);
        ChangeRequest relocation = ChangeRequest.forRequest(f.run.getAcademyId(), f.run.getId(), f.studentId,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.RELOCATE, (short) 2, f.requesterAccountId, now);
        relocation.assignRelocationTarget("새 주소", new BigDecimal("37.562000"), new BigDecimal("126.972000"),
                f.otherStopId);
        relocation.approve(f.requesterAccountId, now, false, null);
        changeRequestRepository.saveAndFlush(relocation);

        assertThatThrownBy(() -> persistence.persist(f.run, null, null, Weekday.MON, stale, now))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("다음 틱");
    }

    private Fixture 준비한다() {
        Phase9RosterFixtures fx = Phase9RosterFixtures.of(applicationContext);
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        long otherStopId = fx.stop(academyId, "37.561000", "126.971000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId, otherStopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.560000", "126.970000");
        OffsetDateTime depart = OffsetDateTime.now(clock).plusMinutes(31);
        long runId = fx.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, depart, depart.minusMinutes(30));
        long requesterAccountId = fx.manager(academyId, ManagerRole.DRIVER, "요청자").accountId();
        return new Fixture(runRepository.findById(runId).orElseThrow(), studentId, otherStopId, requesterAccountId);
    }

    private record Fixture(Run run, long studentId, long otherStopId, long requesterAccountId) {
    }
}
