package src.backend.global.dev;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.response.ApiResponse;
import src.backend.global.security.authz.AuthenticatedOnly;

/**
 * 개발용 데이터 초기화 API — Swagger 로 어지럽힌 상태를 앱 재시작 없이 시드 상태로 되돌린다.
 *
 * <p><b>이 엔드포인트는 되돌릴 수 없는 삭제를 부른다.</b> 그래서 두 겹으로 존재 자체를 막는다.
 * <ul>
 *   <li>{@code @Profile("local")} — {@code demo}·{@code prod} 에서는 빈이 만들어지지 않는다.
 *       그 안쪽 겹(위험 프로파일 혼재·비 localhost 데이터소스 거부)은 {@code LocalFlywayCleanStrategy}
 *       가 이미 맡고 있고, {@link DevResetService} 가 그리로 위임한다</li>
 *   <li>{@code app.dev-tools.reset.enabled} — {@code build.gradle} 의 test 태스크가 {@code false} 로
 *       심어 <b>테스트 컨텍스트에는 아예 등록되지 않는다.</b> 이 저장소의 테스트는 {@code local}
 *       프로파일로 돌기 때문에 프로파일만으로는 막히지 않는다 — 열어 두면 전체 실행 도중 이 경로가
 *       불릴 때 다른 시험들이 쓰는 공유 DB 가 통째로 지워진다</li>
 * </ul>
 *
 * <p>인증은 요구한다({@code @AuthenticatedOnly}) — 초기화 후에도 액세스 토큰은 그대로 유효하다
 * (JWT 는 상태를 서버에 두지 않고, 시드가 같은 계정을 다시 만든다).
 */
@Tag(name = ApiTags.DEV)
@RestController
@RequestMapping("/dev/reset")
@Profile("local")
@ConditionalOnProperty(name = "app.dev-tools.reset.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DevResetController {

    private final DevResetService devResetService;

    /** DB 를 시드 상태로 되돌리고 위치 캐시를 비운다. */
    @AuthenticatedOnly
    @Operation(summary = "개발용 초기화 — DB 를 시드 상태로 되돌린다 (local 전용)")
    @PostMapping
    public ApiResponse<DevResetResponse> reset() {
        return ApiResponse.ok(new DevResetResponse(devResetService.reset()));
    }

    /** 초기화 결과 — 지운 위치 캐시 키 개수. */
    public record DevResetResponse(int clearedPositionKeys) {
    }
}
