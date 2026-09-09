package src.backend.global.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link JwtAuthenticationFilter} 검증 전용 컨트롤러 — 테스트 소스에만 존재한다(프로덕션 컨트롤러가
 * 아직 0개라 실제 핸들러로 시험할 수 없다, {@code GateTestController} 선례를 따른다).
 * 인증이 필요한 임의 자원 하나만 있으면 되므로 매핑은 1개다.
 */
@RestController
class JwtAuthFilterTestController {

    @GetMapping("/jwt-filter-test/protected")
    public String protectedEndpoint() {
        return "ok";
    }
}
