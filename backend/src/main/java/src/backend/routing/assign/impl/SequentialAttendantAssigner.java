package src.backend.routing.assign.impl;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import src.backend.global.common.enums.Weekday;
import src.backend.manager.entity.WorkHours;
import src.backend.routing.assign.spec.AssignRejection;
import src.backend.routing.assign.spec.AttendantAssignInput;
import src.backend.routing.assign.spec.AttendantAssigner;
import src.backend.routing.assign.spec.AttendantAssignment;
import src.backend.routing.assign.spec.AttendantCandidate;
import src.backend.routing.assign.spec.BusyWindow;
import src.backend.routing.assign.spec.RejectReason;

/**
 * 후보 목록을 순서대로 훑어 <b>근무 시간·중복 배치 충돌이 없는 첫 매니저</b>를 선정한다
 * (ARCHITECTURE §8.2 ⑤, MGR-05·06).
 *
 * <p>선정된 뒤에도 <b>나머지 후보를 계속 판정</b>한다 — {@code rejections} 는 관계자가 자동 배정
 * 결과를 손으로 고칠 때 "왜 이 사람이 아니었나" 를 전부 보여줘야 하고, 선정 이후 후보를 건너뛰면
 * 그 정보가 응답에서 사라진다.
 *
 * <p>한 후보가 두 판정에 모두 걸리면 <b>근무 시간을 먼저</b> 본다 — 근무 시간 밖인 사람은 애초에
 * 그 회차를 몰 수 없는 사람이고, 중복 배치는 "몰 수는 있으나 이미 다른 곳에 있다"는 다른 종류의
 * 결격이다.
 *
 * <p>요일 판정은 <b>주입된 {@code Clock} 의 zone</b> 을 쓴다({@code AssignmentConflictDetector} 와
 * 같은 관례, Ruling 165 ①) — {@code ZoneId} 를 코드에 다시 적으면 테스트가 시계를 갈아끼워도 요일
 * 판정만 시스템 시간대를 따라 남는다.
 */
@Component
public class SequentialAttendantAssigner implements AttendantAssigner {

    private final Clock clock;

    public SequentialAttendantAssigner(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AttendantAssignment assign(AttendantAssignInput input) {
        OffsetDateTime runStart = input.departAt();
        OffsetDateTime runEnd = runStart.plusMinutes(input.estDurationMin());

        List<AssignRejection> rejections = new ArrayList<>();
        Long selected = null;
        for (AttendantCandidate candidate : input.candidates()) {
            RejectReason reason = rejectionReasonOf(candidate, runStart, runEnd);
            if (reason != null) {
                rejections.add(new AssignRejection(candidate.managerId(), reason));
            } else if (selected == null) {
                selected = candidate.managerId();
            }
        }
        return new AttendantAssignment(selected, rejections);
    }

    /** 통과하면 {@code null} — 두 판정 중 걸린 것을 앞의 것부터 먼저 본다. */
    private RejectReason rejectionReasonOf(AttendantCandidate candidate, OffsetDateTime runStart,
            OffsetDateTime runEnd) {
        if (!withinWorkHours(candidate.workHours(), runStart, runEnd)) {
            return RejectReason.OUT_OF_WORK_HOURS;
        }
        if (overlapsAssignment(candidate.alreadyAssigned(), runStart, runEnd)) {
            return RejectReason.ALREADY_ASSIGNED;
        }
        return null;
    }

    /**
     * 근무 시간 미등록({@code null})도 판정 근거 부재이므로 근무 시간 밖으로 다룬다 — 이 포트의
     * {@link RejectReason} 은 2종뿐이라 수동 배치처럼 {@code WORK_HOURS_NOT_SET} 을 따로 두지 않는다.
     *
     * <p>회차가 자정을 넘어 시작·종료 요일이 갈리면 즉시 근무 시간 밖이다 — {@link WorkHours} 는
     * 자정을 넘는 구간을 담지 않으므로 어떤 근무 구간도 그런 창을 커버할 수 없다.
     */
    private boolean withinWorkHours(WorkHours workHours, OffsetDateTime runStart, OffsetDateTime runEnd) {
        if (workHours == null) {
            return false;
        }
        Weekday startWeekday = weekdayOf(runStart);
        if (startWeekday != weekdayOf(runEnd)) {
            return false;
        }
        return workHours.coversWindow(startWeekday, timeOf(runStart), timeOf(runEnd));
    }

    private boolean overlapsAssignment(List<BusyWindow> busyWindows, OffsetDateTime runStart, OffsetDateTime runEnd) {
        return busyWindows.stream().anyMatch(window -> window.overlaps(runStart, runEnd));
    }

    private Weekday weekdayOf(OffsetDateTime time) {
        return Weekday.valueOf(time.atZoneSameInstant(clock.getZone()).getDayOfWeek()
                .name().substring(0, 3).toUpperCase(Locale.ROOT));
    }

    private LocalTime timeOf(OffsetDateTime time) {
        return time.atZoneSameInstant(clock.getZone()).toLocalTime();
    }
}
