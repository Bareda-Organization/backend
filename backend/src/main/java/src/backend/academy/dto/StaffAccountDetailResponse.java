package src.backend.academy.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;

import src.backend.academy.entity.StaffStatus;
import src.backend.account.entity.Account;

/**
 * 관계자 계정 수정 응답(API_SPEC §6.7) — §1.9 대로 <b>변경 후 자원 상태</b>를 그대로 돌려준다.
 *
 * <p>{@code temporaryPassword} 에만 {@link JsonInclude}({@code NON_NULL})를 붙인다. 초기화를 요청하지
 * 않은 응답에서는 이 키가 <b>값이 null 인 채로 남는 것이 아니라 JSON 에서 빠져야</b> 한다 — §6.7 이
 * "1회 반환" 으로 규정한 값이라, 매 응답에 키가 남으면 그 자리가 화면·프록시 로그에 상시 노출된다.
 * 클래스가 아니라 필드에 붙이는 이유는 {@code email} 처럼 <b>값이 없는 것이 정상</b>인 필드까지
 * 키가 사라지면 클라이언트가 null 검사와 키 존재 검사 중 무엇을 쓸지 갈리기 때문이다.
 *
 * @param status            {@code academy_staff.status}(재직 2종) — 계정 상태가 아니다
 * @param temporaryPassword 초기화를 요청했을 때만 실린다. 저장은 해시로만 하며 <b>원문은 이 응답이 유일한 전달 수단</b>
 */
public record StaffAccountDetailResponse(Long accountId, String name, String loginId, String phone, String email,
        String academyName, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String temporaryPassword) {

    public static StaffAccountDetailResponse from(Account account, String academyName, StaffStatus status,
            String temporaryPassword) {
        return new StaffAccountDetailResponse(account.getId(), account.getName(), account.getLoginId(),
                account.getPhone(), account.getEmail(), academyName, status.name().toLowerCase(Locale.ROOT),
                temporaryPassword);
    }
}
