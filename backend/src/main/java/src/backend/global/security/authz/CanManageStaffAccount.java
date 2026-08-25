package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 관계자 계정 조회·관리(API_SPEC §6.6·§6.7 {@code /admin/staff-accounts})에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#STAFF_APPROVE} 를 쓴다 — FEATURE_SPEC §6.2 권한 카탈로그에 ACAD-06 전용
 * 권한이 <b>부재</b>하고, 관계자 승인(ACAD-05)과 계정 관리(ACAD-06)를 O-02 한 기능으로 묶어 그
 * 권한 하나에 얹어 두었기 때문이다. 카탈로그에 없는 권한 상수를 새로 만들면 부여표와 사양이 갈린다.
 *
 * <p>{@link CanManageAcademy}({@code ACADEMY_MANAGE})를 쓰지 않은 이유는 그 권한의 뜻이 "학원 등록 ·
 * 수정 · 비활성화" 로 §6.2 에 못박혀 있어서다 — 관계자 개인 계정을 만지는 것은 그 문장 밖이다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.STAFF_APPROVE + "')")
public @interface CanManageStaffAccount {
}
