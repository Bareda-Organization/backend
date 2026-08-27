package src.backend.manager.dto;

import java.util.Locale;

import src.backend.global.common.enums.ManagerRole;

/**
 * 회차에 배치된 매니저 한 명(MGR-05, API_SPEC §5.10·§5.14 {@code assignments[]}).
 *
 * <p>{@code phone}·{@code workHours} 를 싣지 않는다 — 배치 목록은 "누가 붙어 있나" 를 보여 주는
 * 자리라 연락처가 필요 없고, 실으면 회차를 읽을 수 있는 모든 화면에 매니저 개인정보가 함께 열린다
 * (횡단 규칙 8: 응답은 역할별 DTO).
 *
 * @param role {@code driver} · {@code escort} 소문자 — API_SPEC §9.1 의 값 공간이다
 */
public record AssignedManagerResponse(Long managerId, String name, String role) {

    public static AssignedManagerResponse of(Long managerId, String name, ManagerRole role) {
        return new AssignedManagerResponse(managerId, name, role.name().toLowerCase(Locale.ROOT));
    }
}
