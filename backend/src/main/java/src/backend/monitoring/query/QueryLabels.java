package src.backend.monitoring.query;

import java.util.List;
import java.util.Locale;

import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.dto.StaffAssignmentAckView;

/**
 * 관제 조회들이 응답 문자열과 회차 배치 정보를 만들 때 함께 쓰는 도우미 — 열거형 이름을 응답용 소문자로 바꾸고, 회차의 배치 행에서
 * 역할별 이름 · 전화 · 확인 여부를 찾는다. 조회마다 복사해 두면 한 곳의 규칙만 바뀌어 화면마다 값이 달라진다.
 */
final class QueryLabels {

    private QueryLabels() {
    }

    /** 열거형 이름을 응답용 소문자로 — 로케일에 따라 달라지지 않는다. */
    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** 그 역할의 배치된 매니저 이름 — 배치가 없으면 {@code null}. */
    static String nameOf(List<StaffAssignmentAckView> acks, ManagerRole role) {
        return acks.stream().filter(view -> view.role() == role).map(StaffAssignmentAckView::name).findFirst()
                .orElse(null);
    }

    /** 그 역할의 배치된 매니저 전화(원문) — 배치가 없으면 {@code null}. */
    static String phoneOf(List<StaffAssignmentAckView> acks, ManagerRole role) {
        return acks.stream().filter(view -> view.role() == role).map(StaffAssignmentAckView::phone).findFirst()
                .orElse(null);
    }

    /** 그 역할의 배치가 현재 노선 버전을 확인했는지 — 배치가 없으면 거짓. */
    static boolean ackedOf(List<StaffAssignmentAckView> acks, ManagerRole role) {
        return acks.stream().filter(view -> view.role() == role).findFirst().map(StaffAssignmentAckView::acked)
                .orElse(false);
    }
}
