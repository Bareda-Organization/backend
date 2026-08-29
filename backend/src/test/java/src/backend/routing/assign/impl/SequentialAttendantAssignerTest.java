package src.backend.routing.assign.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.manager.entity.WorkHours;
import src.backend.routing.assign.spec.AssignRejection;
import src.backend.routing.assign.spec.AttendantAssignInput;
import src.backend.routing.assign.spec.AttendantAssignment;
import src.backend.routing.assign.spec.AttendantCandidate;
import src.backend.routing.assign.spec.BusyWindow;
import src.backend.routing.assign.spec.RejectReason;

/**
 * 동승자 자동 배정(ARCHITECTURE §8.2 ⑤, MGR-05·06)의 판정 2종과 경계 시각을 검증한다.
 *
 * <p>2026-03-02 는 월요일이다 — {@code RouteComputationPipelineTest} 가 같은 날짜를 쓴다.
 */
class SequentialAttendantAssignerTest {

    private static final OffsetDateTime MON_08_00 =
            OffsetDateTime.of(2026, 3, 2, 8, 0, 0, 0, ZoneOffset.ofHours(9));

    private static final Clock SEOUL_CLOCK = Clock.fixed(Instant.EPOCH, ZoneId.of("Asia/Seoul"));

    private final SequentialAttendantAssigner assigner = new SequentialAttendantAssigner(SEOUL_CLOCK);

    @Test
    @DisplayName("근무 시간 안이고 중복 배치가 없으면 그 매니저가 선정된다")
    void selectsCandidateWithinWorkHoursAndNoOverlap() {
        WorkHours workHours = workHours("07:00", "10:00");
        AttendantAssignInput input = inputWith(30,
                new AttendantCandidate(1L, workHours, List.of()));

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isEqualTo(1L);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    @DisplayName("회차 종료가 근무 종료 시각과 정확히 같으면 허용한다 — WorkHours.covers 의 양끝 포함 관례를 구간으로 확장")
    void allowsRunEndingExactlyAtWorkHoursEnd() {
        // 08:00 ~ 08:30(estDurationMin=30) 회차, 근무 시간 07:00~08:30 — 종료 시각이 정확히 겹친다.
        WorkHours workHours = workHours("07:00", "08:30");
        AttendantAssignInput input = inputWith(30,
                new AttendantCandidate(1L, workHours, List.of()));

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isEqualTo(1L);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    @DisplayName("회차 시간대가 근무 시간을 1분이라도 벗어나면 OUT_OF_WORK_HOURS 로 거절한다")
    void rejectsWhenRunWindowExceedsWorkHoursByOneMinute() {
        // 근무 종료가 08:29, 회차 종료는 08:30 — 1분 초과.
        WorkHours workHours = workHours("07:00", "08:29");
        AttendantAssignInput input = inputWith(30,
                new AttendantCandidate(1L, workHours, List.of()));

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isNull();
        assertThat(result.rejections())
                .containsExactly(new AssignRejection(1L, RejectReason.OUT_OF_WORK_HOURS));
    }

    @Test
    @DisplayName("근무 시간을 등록하지 않은 매니저는 OUT_OF_WORK_HOURS 로 거절한다")
    void rejectsCandidateWithoutRegisteredWorkHours() {
        AttendantAssignInput input = inputWith(30,
                new AttendantCandidate(1L, null, List.of()));

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isNull();
        assertThat(result.rejections())
                .containsExactly(new AssignRejection(1L, RejectReason.OUT_OF_WORK_HOURS));
    }

    @Test
    @DisplayName("같은 시간대에 다른 회차와 겹치면 ALREADY_ASSIGNED 로 거절한다")
    void rejectsOverlappingAssignment() {
        WorkHours workHours = workHours("07:00", "10:00");
        BusyWindow overlapping = new BusyWindow(MON_08_00.minusMinutes(10), MON_08_00.plusMinutes(10));
        AttendantAssignInput input = inputWith(30,
                new AttendantCandidate(1L, workHours, List.of(overlapping)));

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isNull();
        assertThat(result.rejections())
                .containsExactly(new AssignRejection(1L, RejectReason.ALREADY_ASSIGNED));
    }

    @Test
    @DisplayName("앞 회차가 끝나는 시각과 새 회차가 시작하는 시각이 같은 맞배치는 겹침이 아니다")
    void backToBackAssignmentIsNotOverlap() {
        WorkHours workHours = workHours("07:00", "10:00");
        // 앞 회차: 07:00~08:00, 새 회차: 08:00~08:30 — 경계가 닿기만 한다.
        BusyWindow backToBack = new BusyWindow(MON_08_00.minusHours(1), MON_08_00);
        AttendantAssignInput input = inputWith(30,
                new AttendantCandidate(1L, workHours, List.of(backToBack)));

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isEqualTo(1L);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    @DisplayName("후보가 0명이면 예외 없이 managerId=null 을 돌려준다")
    void returnsNullManagerIdWithoutExceptionWhenNoCandidates() {
        AttendantAssignInput input = new AttendantAssignInput(1L, 1L, MON_08_00, 30, List.of());

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isNull();
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    @DisplayName("전원 거절이어도 managerId=null 이고 모두의 거절 사유가 남는다")
    void allCandidatesRejectedLeavesManagerIdNullWithAllReasons() {
        AttendantCandidate outOfHours = new AttendantCandidate(1L, workHours("13:00", "18:00"), List.of());
        AttendantCandidate doubleBooked = new AttendantCandidate(2L, workHours("07:00", "10:00"),
                List.of(new BusyWindow(MON_08_00.minusMinutes(10), MON_08_00.plusMinutes(10))));
        AttendantAssignInput input = inputWith(30, outOfHours, doubleBooked);

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isNull();
        assertThat(result.rejections()).containsExactlyInAnyOrder(
                new AssignRejection(1L, RejectReason.OUT_OF_WORK_HOURS),
                new AssignRejection(2L, RejectReason.ALREADY_ASSIGNED));
    }

    @Test
    @DisplayName("앞 후보가 거절돼도 뒤 후보가 선정되고, 앞 후보의 거절 사유는 그대로 남는다")
    void skipsRejectedCandidateAndSelectsNextWhileKeepingRejection() {
        AttendantCandidate rejected = new AttendantCandidate(1L, workHours("13:00", "18:00"), List.of());
        AttendantCandidate selectable = new AttendantCandidate(2L, workHours("07:00", "10:00"), List.of());
        AttendantAssignInput input = inputWith(30, rejected, selectable);

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isEqualTo(2L);
        assertThat(result.rejections())
                .containsExactly(new AssignRejection(1L, RejectReason.OUT_OF_WORK_HOURS));
    }

    @Test
    @DisplayName("근무 시간과 중복 배치에 모두 걸리면 근무 시간 사유가 우선한다")
    void reportsOutOfWorkHoursBeforeAlreadyAssignedWhenBothApply() {
        AttendantCandidate both = new AttendantCandidate(1L, workHours("13:00", "18:00"),
                List.of(new BusyWindow(MON_08_00.minusMinutes(10), MON_08_00.plusMinutes(10))));
        AttendantAssignInput input = inputWith(30, both);

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.rejections())
                .containsExactly(new AssignRejection(1L, RejectReason.OUT_OF_WORK_HOURS));
    }

    @Test
    @DisplayName("회차가 자정을 넘어가면 어떤 근무 구간도 커버할 수 없어 OUT_OF_WORK_HOURS 다")
    void rejectsWhenRunWindowCrossesMidnight() {
        OffsetDateTime lateNight = OffsetDateTime.of(2026, 3, 2, 23, 50, 0, 0, ZoneOffset.ofHours(9));
        WorkHours workHours = WorkHours.of(Map.of(
                "mon", List.of(Map.of("start", "22:00", "end", "23:59")),
                "tue", List.of(Map.of("start", "00:00", "end", "06:00"))));
        AttendantAssignInput input = new AttendantAssignInput(1L, 1L, lateNight, 30,
                List.of(new AttendantCandidate(1L, workHours, List.of())));

        AttendantAssignment result = assigner.assign(input);

        assertThat(result.managerId()).isNull();
        assertThat(result.rejections())
                .containsExactly(new AssignRejection(1L, RejectReason.OUT_OF_WORK_HOURS));
    }

    private static WorkHours workHours(String start, String end) {
        return WorkHours.of(Map.of("mon", List.of(Map.of("start", start, "end", end))));
    }

    private static AttendantAssignInput inputWith(int estDurationMin, AttendantCandidate... candidates) {
        return new AttendantAssignInput(1L, 1L, MON_08_00, estDurationMin, List.of(candidates));
    }
}
