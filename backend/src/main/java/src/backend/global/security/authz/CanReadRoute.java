package src.backend.global.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 자기 회차로 좁혀진 노선·내비 조회(API_SPEC §4.3·§4.16)에 붙는 메타 애너테이션.
 *
 * <p>{@link Permissions#ROUTE_READ} 는 학부모·학생·기사·동승자까지 <b>전 역할</b>이 보유한다
 * ({@link RolePermissions}, FEATURE_SPEC §6.2). 이 권한만으로는 "그 회차에 배치됐는가"를 답하지
 * 못한다 — 그 자원 범위 검증(FEATURE_SPEC §6.4 "매니저↔회차")은 서비스 계층이 별도로 한다
 * ({@code NavigationQueryService.assertAssigned} 등). {@link CanManageRoute} 와 다른 권한을 쓰는
 * 이유는 그쪽이 학원 전체 편성(§5.9)이고 이쪽은 한 회차로 좁혀진 조회라서다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('" + Permissions.ROUTE_READ + "')")
public @interface CanReadRoute {
}
