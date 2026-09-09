package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 자녀의 요일별 등하원 주소 조회·설정(API_SPEC §3.7 {@code GET}·{@code PATCH})에 붙는 메타 애너테이션.
 *
 * <p>{@code hasAuthority(...)} 가 아니라 {@code isAuthenticated()} 인 이유는 <b>권한 카탈로그
 * (FEATURE_SPEC §6.2)에 요일별 주소에 대응하는 항목이 부재</b>하기 때문이다({@link CanLinkChild} 와
 * 같은 형태의 판단이다). 카탈로그는 사양 정본이고 {@code RolePermissionsTest} 가 역할별 집합을 그 표와
 * 정확히 대조하므로, 없는 권한을 여기서 지어내면 코드와 정본이 갈린다.
 *
 * <p><b>{@link Permissions#STUDENT_READ_SENSITIVE} 를 요구하지 않는다.</b> 응답이 담는 주소 원문·좌표는
 * 카탈로그상 L3 이고 그 등급의 보유 역할은 관계자·메인관리자뿐이라, 그것을 요구하면 <b>학부모 본인이
 * 방금 적어 낸 자기 자녀의 주소</b>가 {@code 403} 이 된다. 반대로 학부모에게 그 권한을 부여하면 사진 ·
 * 특이사항 · 연락처 원문이 함께 열린다 — 등급이 아니라 <b>자원 소속</b>으로 좁히는 것이 맞는 자리다.
 *
 * <p>좁히는 것은 {@code student.access.LinkedChildLookup} 이다 — 토큰의 계정으로 보호자를 찾고,
 * 연결된 자녀가 아니면 {@code 403 FORBIDDEN} 이다(API_SPEC §3.7). 인가 애너테이션은 "무엇을 할 수 있는가" 만
 * 답하고 "어느 자원인가" 는 답하지 않는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface CanManageWeeklyAddress {
}
