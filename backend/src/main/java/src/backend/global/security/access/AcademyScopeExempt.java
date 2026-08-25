package src.backend.global.security.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 학원으로 좁히지 <b>않는 것이 옳은</b> 저장소 조회임을 밝힌다(API_SPEC §1.5 예외).
 *
 * <p>주석 대신 애너테이션인 이유는 강제력이다 — {@code AcademyScopeRepositoryConventionTest} 가
 * {@code academy_id} 직접 보유 엔티티(ERD §6.1)의 저장소 메서드에 대해 "학원 조건" 아니면 이
 * 애너테이션 중 하나를 요구한다. 주석은 지워도 아무도 모르지만, 이 애너테이션이 없는 새 조회는
 * 테스트가 실패시킨다. 즉 예외가 조용히 생겨날 수 없다.
 *
 * <p>반대 방향도 막는다 — 로그인·재발급·복구 조회는 학원 소속을 <b>아직 모르는 상태에서 시작</b>해
 * 좁힐 수단 자체가 없다. 다음 사람이 "격리를 빠뜨렸다" 로 오인해 학원 조건을 넣으면 로그인이
 * 통째로 실패하므로, 같은 테스트가 그 4개 조회에 대해 애너테이션 유지와 조건 부재를 함께 고정한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AcademyScopeExempt {

    /** 좁히지 않는 근거 — 사양 절 번호를 포함해 적는다. 근거 없는 예외는 격리 누락과 구별되지 않는다. */
    String reason();
}
