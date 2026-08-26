package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 학부모 앱의 자녀 목록 조회(API_SPEC §3.1 {@code GET /me/students})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#STUDENT_READ_BASIC} 을 요구한다 — 이 응답이 담는 것이 이름 · 반뿐이고,
 * 카탈로그가 그 등급의 보유 역할에 "학부모(자녀)" 를 명시한다(FEATURE_SPEC §6.2).
 * {@link CanReadStudentRecord}({@code STUDENT_READ_SENSITIVE})와 갈라 둔 이유가 그것이다 — 그쪽으로
 * 열면 사진 · 특이사항 · 연락처 원문 등급의 권한을 학부모에게 부여해야 한다.
 *
 * <p><b>이 권한만으로는 "어느 자녀인가" 가 좁혀지지 않는다.</b> 기사 · 동승자 · 관계자도 같은 권한을
 * 보유하므로(§6.2), 요청 주체를 보호자로 좁히고 연결된 자녀만 남기는 것은
 * {@code student.access.GuardianChildAccess} 의 몫이다. 인가 애너테이션은 "무엇을 할 수 있는가" 만
 * 답하고 "어느 자원인가" 는 답하지 않는다 — 두 물음을 하나로 겸하면 자원 판정이 애너테이션 표현식에
 * 섞여 들어가 다시 흩어진다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.STUDENT_READ_BASIC + "')")
public @interface CanReadLinkedChild {
}
