package src.backend.academy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 관계자 계정 관리 요청(API_SPEC §6.7) — 다섯 항목 전부 선택이며 준 것만 바꾼다.
 *
 * <p>{@code status} 는 <b>{@code academy_staff.status}</b>(재직 상태 2종)다. {@code account.status}
 * 가 아니라는 근거는 셋이다. ① {@code ck_account_status} 는 {@code pending}·{@code active}·
 * {@code rejected}·{@code blocked} 라 {@code inactive} 라는 값 자체가 부재하다. ② §6.7 이 규정한
 * {@code 409 STAFF_QUOTA_EXCEEDED} 는 {@code uk_academy_staff_academy_active} 인덱스가 정의하는
 * 판정이라 그 컬럼이 아니면 성립하지 않는다. ③ ERD §7 이 {@code academy_staff} = 비활성화
 * ({@code status='inactive'}), {@code account} = 상태 전이({@code blocked}·{@code rejected} 만)로
 * 두 컬럼의 역할을 갈라 적는다.
 *
 * <p>{@code resetPassword} 가 {@code Boolean} 인 것은 "주지 않음" 과 "false 를 줌" 을 갈라야 하기
 * 때문이 아니라, {@code boolean} 으로 받으면 준 적 없는 요청이 {@code false} 와 구별되지 않아
 * <b>부분 수정 규약이 필드마다 갈리기</b> 때문이다 — 나머지 넷과 같은 형태로 맞춘다.
 */
public record StaffAccountUpdateRequest(
        @Size(max = 50) String name,
        @Size(max = 30) String phone,
        @Email @Size(max = 120) String email,
        Boolean resetPassword,
        @Pattern(regexp = "active|inactive") String status) {

    /** 초기화를 <b>명시적으로 요청</b>했을 때만 참이다 — 필드 부재를 요청으로 읽으면 매번 초기화된다. */
    public boolean wantsPasswordReset() {
        return Boolean.TRUE.equals(resetPassword);
    }
}
