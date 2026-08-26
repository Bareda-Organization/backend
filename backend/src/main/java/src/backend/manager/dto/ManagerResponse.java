package src.backend.manager.dto;

import java.util.Locale;
import java.util.Map;

import src.backend.manager.entity.Manager;

/**
 * 매니저 응답(API_SPEC §5.13) — 목록·등록·수정이 함께 쓴다. 관계자 웹 전용이라 역할별로 가르지 않는다.
 *
 * <p>{@code workHours} 를 저장된 원문 그대로 싣는다 — {@code WorkHours} 를 거쳐 되돌리면 이 타입이
 * 생기기 전에 적재된 행(로컬 시드)이 조회만으로 실패한다.
 *
 * @param role {@code driver} · {@code escort} 소문자 — API_SPEC §9.1 의 값 공간이다
 */
public record ManagerResponse(Long id, String name, String phone, String role, Map<String, Object> workHours) {

    public static ManagerResponse from(Manager manager) {
        return new ManagerResponse(manager.getId(), manager.getName(), manager.getPhone(),
                manager.getRole().name().toLowerCase(Locale.ROOT), manager.getWorkHours());
    }
}
