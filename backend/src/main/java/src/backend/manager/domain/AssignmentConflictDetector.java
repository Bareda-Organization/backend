package src.backend.manager.domain;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Weekday;
import src.backend.manager.dto.AssignmentWarning;
import src.backend.manager.dto.AssignmentWarningCode;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.WorkHours;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.run.entity.Run;

/**
 * 배치 충돌을 <b>검출</b>한다(MGR-06 · API_SPEC §5.14, Ruling 152·165) — 막지 않는다.
 *
 * <p>판정 결과는 {@code warnings[]} 로 응답에 실리고 배치는 그대로 저장된다. 차단으로 두면 당일
 * 대체·연장으로 <b>오늘 실제로 태울 수 있는 기사</b>를 시스템이 배치 불가로 만든다.
 *
 * <p><b>두 판정이 서로 독립이다</b>(Ruling 165 ③) — 근무 시간 대조와 중복 배치 대조는 보는 것이
 * 다르다. 묶으면 중복 배치 경고가 근무 시간 미기재 매니저에서 조용히 사라지는데, 근무 시간이 없다고
 * 해서 같은 시각에 두 대를 몰 수 있는 것은 아니다.
 *
 * <p>시간대는 <b>주입된 {@code Clock} 의 zone</b> 이다(Ruling 165 ①) — {@code ZoneId} 를 코드에 다시
 * 적으면 테스트가 시계를 갈아끼워도 요일 판정만 시스템 시간대를 따라 남는다.
 *
 * <p><b>두 경고가 이제 같은 구간 판정을 쓴다</b>(Phase 7 목표 12 · Phase 8 목표 13, Ruling 165 ②
 * · Ruling 193) — {@code WORK_HOURS_MISMATCH} 와 {@code MANAGER_DOUBLE_BOOKED} 모두 {@link
 * #windowEnd(OffsetDateTime, Integer)} 가 만든 구간으로 겹침을 가른다. {@code est_duration_min}
 * 이 없으면 시작 시각과 같은 점으로 접혀 예전 점 판정과 값이 같아진다 — 두 판정이 서로 다른 축을
 * 쓰던 비대칭(Ruling 193 이전, {@code AssignmentRepository#existsOverlappingAssignment} 가
 * 출발 시각 일치만 봤다)이 이 판정으로 해소됐다.
 */
@Component
@RequiredArgsConstructor
public class AssignmentConflictDetector {

    private final AssignmentRepository assignmentRepository;

    private final Clock clock;

    /**
     * 이 매니저를 이 회차의 이 자리에 붙였을 때 나오는 경고 전부 — 충돌이 없으면 <b>빈 목록</b>이다.
     *
     * @param run     배치 대상 회차. 이미 학원으로 좁혀 꺼낸 것이라 여기서 학원을 다시 판정하지 않는다
     * @param manager 배치할 매니저. 역할이 자리와 맞는지는 호출부가 조회 조건으로 이미 걸렀다
     */
    public List<AssignmentWarning> detect(Run run, Manager manager, ManagerRole role) {
        List<AssignmentWarning> warnings = new ArrayList<>();
        workHoursWarning(run, manager).ifPresent(code ->
                warnings.add(AssignmentWarning.of(code, manager.getId(), role)));
        if (doubleBooked(run, manager)) {
            warnings.add(AssignmentWarning.of(AssignmentWarningCode.MANAGER_DOUBLE_BOOKED, manager.getId(), role));
        }
        return warnings;
    }

    /**
     * 근무 시간 축의 판정 — 미기재({@code WORK_HOURS_NOT_SET})와 구간 밖({@code WORK_HOURS_MISMATCH})은
     * <b>다른 경고</b>다(Ruling 165 ④).
     *
     * <p>미기재를 "경고 없음" 으로 두지 않는 이유는 근무 시간이 등록 시 선택 항목이라 비어 있는 것이
     * 정상 상태이기 때문이다 — 적합해서 조용한 것이 아니라 판정할 근거가 부재한 것이고, 둘을 같은
     * 응답으로 답하면 클라이언트가 가를 수단이 없다.
     */
    private Optional<AssignmentWarningCode> workHoursWarning(Run run, Manager manager) {
        WorkHours workHours = WorkHours.fromColumnValue(manager.getWorkHours());
        if (workHours == null) {
            return Optional.of(AssignmentWarningCode.WORK_HOURS_NOT_SET);
        }
        return withinWorkHours(workHours, run)
                ? Optional.empty()
                : Optional.of(AssignmentWarningCode.WORK_HOURS_MISMATCH);
    }

    /**
     * 회차 시간대(구간)가 근무 구간에 드는가 — {@link #windowEnd(Run)} 가 만든 종료 시각까지 함께 본다.
     *
     * <p>자정을 넘어 시작·종료 요일이 갈리면 즉시 근무 시간 밖이다({@code SequentialAttendantAssigner}
     * 와 같은 관례) — {@link WorkHours} 는 자정을 넘는 구간을 담지 않으므로 어떤 근무 구간도 그런
     * 창을 커버할 수 없다. {@code est_duration_min} 이 없어 구간이 점으로 접히면(시작=종료) 이 조건은
     * 항상 같은 요일이라 통과하고, {@link WorkHours#coversWindow} 는 {@link WorkHours#covers} 와
     * 값이 같아져 <b>기존 점 판정과 동일하게 동작</b>한다 — Ruling 165 ② 가 우려한 "판정 불가가
     * 조용히 경고 없음이 되는" 퇴행이 없다.
     */
    private boolean withinWorkHours(WorkHours workHours, Run run) {
        OffsetDateTime runStart = run.getDepartTime();
        OffsetDateTime runEnd = windowEnd(run);
        Weekday startWeekday = weekdayOf(runStart);
        if (startWeekday != weekdayOf(runEnd)) {
            return false;
        }
        return workHours.coversWindow(startWeekday, timeOf(runStart), timeOf(runEnd));
    }

    /**
     * 회차의 종료 시각 — {@code est_duration_min} 이 채워졌으면 <b>구간</b>으로 올리고
     * (Ruling 165 ② 재판정, Phase 7 T2 확정 배치가 이 값을 채운다), 없으면 시작 시각과 같은
     * <b>점</b>으로 접는다.
     *
     * <p>확정 전 회차에 수동 배치하는 것은 정상 흐름이고 그때는 여전히 값이 없다 — 이 메서드가 그
     * 경우를 "판정 불가" 로 두지 않고 기존 점 판정으로 접어, 값이 없다고 경고가 조용히 사라지지
     * 않는다.
     *
     * <p>{@code MANAGER_DOUBLE_BOOKED}(Ruling 193, Phase 8 목표 13)도 <b>이제 이 규칙을 그대로
     * 쓴다</b> — 근무 시간 축과 다른 종료 시각 규칙을 따로 둘 이유가 없고, 두 개를 유지하면 다음
     * 사람이 어느 쪽을 따라야 할지 모른다. {@link #windowEnd(OffsetDateTime, Integer)} 가 실제 계산을
     * 맡는다 — 후보 창(다른 회차)은 {@link Run} 엔티티 없이 저장소 결과로만 들어오기 때문이다.
     */
    private OffsetDateTime windowEnd(Run run) {
        return windowEnd(run.getDepartTime(), run.getEstDurationMin());
    }

    /**
     * 시작 시각과 소요 분으로 종료 시각을 만든다 — {@link #windowEnd(Run)} 과 겹침 판정
     * {@link #doubleBooked} 양쪽이 같은 null 처분 규칙을 쓰도록 회차 없이도 부를 수 있게 뺐다.
     */
    private OffsetDateTime windowEnd(OffsetDateTime start, Integer estDurationMin) {
        return estDurationMin == null ? start : start.plusMinutes(estDurationMin);
    }

    /**
     * 다른 회차와 운행 구간이 겹치는 배치가 있는가(Ruling 193, Phase 8 목표 13) — 이 판정은
     * {@code work_hours} 를 보지 않는다.
     *
     * <p>지금 배치하려는 회차 자신을 제외한다 — 기사를 그대로 두고 동승자만 바꾸는 요청이 자기 자신을
     * 중복으로 세는 것을 막는다.
     *
     * <p><b>겹침은 양끝을 포함</b>한다({@link WorkHours#coversWindow} 와 같은 규칙, {@link
     * #overlaps} 참조) — 등원 회차가 끝나자마자 하원 회차가 시작하는 것처럼 <b>이어 붙는</b> 배치를
     * 겹침으로 본다. 배타로 두면 그 형태(같은 기사가 쉴 틈 없이 바로 다음 회차를 몬다)가 경고 없이
     * 통과한다.
     */
    private boolean doubleBooked(Run run, Manager manager) {
        OffsetDateTime start = run.getDepartTime();
        OffsetDateTime end = windowEnd(run);
        return assignmentRepository.findManagerRunWindows(run.getAcademyId(), manager.getId(), run.getId())
                .stream()
                .anyMatch(window -> overlaps(start, end, window.departTime(),
                        windowEnd(window.departTime(), window.estDurationMin())));
    }

    /** 두 구간 {@code [start1,end1]}·{@code [start2,end2]} 가 겹치는가 — 양끝을 포함한다. */
    private boolean overlaps(OffsetDateTime start1, OffsetDateTime end1, OffsetDateTime start2,
            OffsetDateTime end2) {
        return !start1.isAfter(end2) && !start2.isAfter(end1);
    }

    /** 주어진 시각의 요일 — 주입된 {@code Clock} 의 시간대로 옮겨 본다. */
    private Weekday weekdayOf(OffsetDateTime time) {
        return Weekday.valueOf(time.atZoneSameInstant(clock.getZone()).getDayOfWeek()
                .name().substring(0, 3).toUpperCase(Locale.ROOT));
    }

    /** 주어진 시각의 <b>시:분</b> — 근무 구간과 같은 축으로 옮긴다({@code Clock} 의 시간대). */
    private LocalTime timeOf(OffsetDateTime time) {
        return time.atZoneSameInstant(clock.getZone()).toLocalTime();
    }
}
