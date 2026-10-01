package src.backend.global.security.gate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 임시 비밀번호 강제 변경 표식(Ruling 540)이 켜진 계정도 호출할 수 있는 핸들러 표시(API_SPEC §1.4 — 비밀번호 변경 ·
 * 본인 조회 · 로그아웃 3개). 표식이 켜진 동안 이 표시가 없는 핸들러는 {@code 403 PASSWORD_CHANGE_REQUIRED} 다.
 *
 * <p>{@link AllowedWhenPending} 과 따로 둔 이유는 두 게이트가 서로 다른 질문이어서다 — 그쪽은 "승인을 받았는가",
 * 이쪽은 "임시 비밀번호를 바꿨는가". 한 표시로 합치면 승인 대기 화면용 핸들러(기기 등록 등)까지 임시 비밀번호
 * 계정에 열린다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedWhenPasswordChange {
}
