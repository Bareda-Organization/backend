package src.backend.global.security.gate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 상태 게이트 검증 전용 컨트롤러 — 테스트 소스에만 존재한다(프로덕션 컨트롤러가 아직 0개라
 * 실제 핸들러로 시험할 수 없다, Task 2 브리프 §3). API_SPEC §1.4 의 pending 허용 2개
 * (signup-status·logout)·rejected 추가 1개(reapply)를 흉내 낸 매핑과, 애너테이션이 없으면
 * 기본이 차단임을 보여줄 무표시 매핑 1개를 둔다.
 */
@RestController
class GateTestController {

    @AllowedWhenPending
    @GetMapping("/gate-test/signup-status")
    public String signupStatus() {
        return "ok";
    }

    @AllowedWhenPending
    @PostMapping("/gate-test/logout")
    public String logout() {
        return "ok";
    }

    @AllowedWhenRejected
    @PostMapping("/gate-test/reapply")
    public String reapply() {
        return "ok";
    }

    @GetMapping("/gate-test/protected")
    public String protectedEndpoint() {
        return "ok";
    }
}
