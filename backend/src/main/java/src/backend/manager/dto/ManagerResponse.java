package src.backend.manager.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import src.backend.manager.entity.Manager;

/**
 * 매니저 응답(API_SPEC §5.13) — 목록·등록·수정이 함께 쓴다. 관계자 웹 전용이라 역할별로 가르지 않는다.
 *
 * <p>{@code workHours} 를 저장된 원문 그대로 싣는다 — {@code WorkHours} 를 거쳐 되돌리면 이 타입이
 * 생기기 전에 적재된 행(로컬 시드)이 조회만으로 실패한다.
 *
 * @param role {@code driver} · {@code escort} 소문자 — API_SPEC §9.1 의 값 공간이다
 * <p>{@code assigned_run_count}·{@code assignments[]} 는 목록 항목에만 싣는다(Ruling 817).
 *
 * @param accountId 연결된 계정 — 가입 연결 전(AUTH-11)이면 {@code null}. 관리자 경유 비밀번호 초기화(§5.22 · Ruling 329)의 대상
 */
public record ManagerResponse(Long id, String name, String phone, String role, Map<String, Object> workHours,
        String accountId, @JsonInclude(JsonInclude.Include.NON_NULL) Integer assignedRunCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RunItem> assignments) {

    /** 오늘·내일 배치 한 건(§5.13 {@code assignments[]}) — 회차와 운행일·호차·방향·출발 시각·상태. */
    public record RunItem(Long runId, LocalDate serviceDate, String busNo, String direction,
            OffsetDateTime departTime, String status) {
    }

    /**
     * {@link Manager} 엔티티에서 응답 필드를 뽑는다 — {@code accountId} 는 문자열로, 없으면 {@code null}. 목록 전용 필드
     * ({@code assigned_run_count}·{@code assignments[]})는 싣지 않는다(등록·수정 응답, Ruling 817).
     */
    public static ManagerResponse from(Manager manager) {
        return new ManagerResponse(manager.getId(), manager.getName(), manager.getPhone(),
                manager.getRole().name().toLowerCase(Locale.ROOT), manager.getWorkHours(),
                manager.getAccountId() == null ? null : String.valueOf(manager.getAccountId()), null, null);
    }

    /** 목록 항목 — 배치 중 회차 수와 오늘·내일 배치를 더한다(Ruling 817). */
    public static ManagerResponse listItem(Manager manager, int assignedRunCount, List<RunItem> assignments) {
        ManagerResponse base = from(manager);
        return new ManagerResponse(base.id(), base.name(), base.phone(), base.role(), base.workHours(),
                base.accountId(), assignedRunCount, assignments);
    }
}
