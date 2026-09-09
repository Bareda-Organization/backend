package src.backend.manager.dto;

import java.util.Locale;

import src.backend.global.common.enums.ManagerRole;

/**
 * 배치 충돌 경고 한 건(MGR-06, API_SPEC §5.14 {@code warnings[]}).
 *
 * <p><b>저장은 되고 이것이 함께 실린다</b>(Ruling 152) — 근무 시간은 학원이 매니저에게 물어 적어 둔
 * 참고값이고 당일 대체·연장이 실재하므로, 차단으로 두면 오늘 실제로 태울 수 있는 기사를 시스템이
 * 배치 불가로 만든다.
 *
 * <p>{@code code} 를 두는 것이 요점이다(Ruling 165 ④) — 코드가 없으면 클라이언트가 <b>"적합해서
 * 조용한 것" 과 "판정하지 못한 것"</b> 을 가를 수단이 부재하다.
 *
 * @param code {@link AssignmentWarningCode} 의 이름
 * @param role 그 경고가 붙은 배치 자리 — 한 요청이 기사·동승자를 함께 바꾸므로 어느 쪽인지 필요하다
 */
public record AssignmentWarning(String code, Long managerId, String role, String message) {

    public static AssignmentWarning of(AssignmentWarningCode code, Long managerId, ManagerRole role) {
        return new AssignmentWarning(code.name(), managerId, role.name().toLowerCase(Locale.ROOT),
                code.getMessage());
    }
}
