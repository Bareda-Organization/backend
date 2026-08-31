package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 관계자 웹의 회차 명단 조회(API_SPEC §5.4 {@code GET /staff/runs/{runId}/roster}, RST-03·A-04)에
 * 붙는 메타 애너테이션.
 *
 * <p>{@link CanReadRoster}({@code ROSTER_READ}) 가 아니라 {@link Permissions#STUDENT_READ_SENSITIVE}
 * 를 요구한다 — 이 응답의 보호자 연락처는 <b>마스킹 대상 밖</b>(§5.4 "관계자 웹은 마스킹 대상 밖")이라
 * 매니저 앱(§4.2, {@code ROSTER_READ})과 같은 권한으로 열면 기사 · 동승자 토큰이 원문 연락처에 닿는다
 * ({@link CanReadStudentRecord} 와 같은 판단 — 둘 다 §1.12 L3 민감정보를 담는다는 것이 근거이지만,
 * 대상 자원이 학생 레코드(§5.11)와 회차 명단(§5.4)으로 달라 애너테이션을 나눈다).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.STUDENT_READ_SENSITIVE + "')")
public @interface CanReadStaffRoster {
}
