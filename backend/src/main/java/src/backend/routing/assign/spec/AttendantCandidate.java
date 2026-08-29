package src.backend.routing.assign.spec;

import java.util.List;
import java.util.Objects;

import src.backend.manager.entity.WorkHours;

/**
 * 배정 후보 매니저 1명.
 *
 * @param managerId       후보 매니저
 * @param workHours       {@code manager.work_hours}(jsonb) 를 Ruling 150 의 형태로 읽은 것. 등록하지
 *                        않았으면 {@code null} — 판정 근거가 부재하므로
 *                        {@link RejectReason#OUT_OF_WORK_HOURS} 로 다룬다(수동 배치의
 *                        {@code WORK_HOURS_NOT_SET} 과 달리 이 포트의 사유는 2종뿐이다)
 * @param alreadyAssigned 이 매니저가 이미 배정된 다른 회차들의 시간대(MGR-06 중복 배치 판정 근거)
 */
public record AttendantCandidate(long managerId, WorkHours workHours, List<BusyWindow> alreadyAssigned) {

    public AttendantCandidate {
        alreadyAssigned = List.copyOf(Objects.requireNonNull(alreadyAssigned, "기배정 시간대 목록이 없다"));
    }
}
