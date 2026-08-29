package src.backend.routing.assign.spec;

import java.util.List;
import java.util.Objects;

/**
 * 동승자 자동 배정 결과 — 후보 0명이거나 전원 거절이면 {@code managerId=null} 이다.
 *
 * <p>자동 배정 실패가 노선 계산 전체를 무르면 안 되므로 <b>예외를 던지지 않는다</b>
 * (ARCHITECTURE §9.4 회차 단위 격리와 같은 축).
 *
 * @param managerId  선정된 매니저. 없으면 {@code null}
 * @param rejections 배정되지 않은 후보와 그 사유 전부 — 선정 이후 후보도 포함한다. 관계자가 자동
 *                   배정 결과를 손으로 고치려면 "왜 다른 사람이 아니었나" 를 전부 볼 수 있어야 한다
 */
public record AttendantAssignment(Long managerId, List<AssignRejection> rejections) {

    public AttendantAssignment {
        rejections = List.copyOf(Objects.requireNonNull(rejections, "거절 목록이 없다"));
    }
}
