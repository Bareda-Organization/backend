package src.backend.global.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;


/**
 * Swagger UI(springdoc-openapi) 문서 메타데이터.
 * JWT Bearer 인증 스킴을 등록해 Swagger UI의 "Authorize" 버튼으로 액세스 토큰을 넣으면
 * 이후 모든 요청에 {@code Authorization: Bearer <token>} 헤더가 자동으로 붙는다.
 *
 * <p>{@link #openApi()} 가 등록하는 태그 5개는 `docs/planning/API_SPEC.md` §2~§6 도메인 절과 1:1 대응한다.
 * 태그 목록·순서·설명을 이 클래스 한 곳에서만 관리하고, 컨트롤러는 {@code @Tag(name = "...")} 로
 * 소속만 선언한다(조율자 확정 Ruling 36). {@code X-Client-Type} 헤더 자동 부착
 * ({@code OperationCustomizer})은 이 클래스의 책임이 아니다 — 로그인 컨트롤러 메서드가
 * {@code @Parameter} 로 직접 붙인다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * springdoc 이 스키마를 만들 때 {@code SNAKE_CASE} 필드명(전역 네이밍 전략, Ruling 104)을 쓰게
     * 한다(BR-113). 이 빈이 없으면 swagger-core 가 자기 기본 {@code ObjectMapper} 로 스키마 필드명을
     * 만들어 실제 응답이 {@code login_id} 를 내려줘도 문서에는 {@code loginId} 로 실린다 —
     * Swagger UI 가 보여주는 그대로 요청하면 {@code 422 VALIDATION_FAILED} 로 끝나는 진짜 원인이었다
     * (규칙 9가 막으려던 "Try it out 실패" 그 자체).
     *
     * <p>앱이 실제로 쓰는 {@code ObjectMapper} 빈을 그대로 주입할 수 없다 — Boot 4 는 웹 직렬화에
     * Jackson 3(패키지 {@code tools.jackson.*})를 쓰지만, springdoc 이 물고 온 swagger-core 는 아직
     * Jackson 2({@code com.fasterxml.jackson.*})만 받는다. 두 세대가 타입부터 달라 공유할 수 없으므로
     * {@link ModelResolver} 전용으로 같은 네이밍 전략만 옮긴 새 Jackson 2 인스턴스를 만든다.
     */
    @Bean
    public ModelResolver modelResolver() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new ModelResolver(mapper);
    }

    // ── 태그 — docs/planning/API_SPEC.md §2~§6 도메인 절과 1:1 대응. 숫자 접두사는 정렬 고정용
    //    (application.yml 공통 섹션의 springdoc.swagger-ui.tags-sorter: alpha 때문에 필요) ──

    private static final String TAG_AUTH = ApiTags.AUTH;
    private static final String TAG_PARENT_STUDENT = ApiTags.PARENT_STUDENT;
    private static final String TAG_MANAGER = ApiTags.MANAGER;
    private static final String TAG_STAFF = ApiTags.STAFF;
    private static final String TAG_ADMIN = ApiTags.ADMIN;

    /**
     * Swagger UI 최상단에 그대로 렌더링되는 안내문(Markdown). {@code %s} 자리는 태그 이름이다. 데모 계정표는
     * {@code db/fixture/V2__seed_data.sql} 의 시드 계정과 같은 값을 적은 것이라, 시드 계정을 바꾸면 이 표도 함께 고친다.
     */
    private static final String DESCRIPTION_TEMPLATE = """
            바래다 — 학원 통원버스 운행·등하원 관리 멀티 테넌트 플랫폼 백엔드 API.

            **테스트 순서** — `%s` 태그의 로그인 API 로 토큰을 받고, 우측 상단 **Authorize** 에
            `access_token` 을 넣는다. 이후 모든 요청에 `Authorization: Bearer <token>` 이 자동으로 붙는다.

            ⚠ **비밀번호** — `local` 프로파일에서는 아래 계정 전부 평문 `password` 로 고정돼 있다.
            **`demo`(배포) 환경은 다르다** — SSM 에서 주입한 값이라 `password` 로 로그인되지 않는다.

            ### 데모 계정 — 역할 6종 × 계정 상태 4종

            | 역할 | 로그인 아이디 | 상태 | 소속 학원 | 시험 포인트 |
            |---|---|---|---|---|
            | system_admin | `sysadmin` | active | 없음(전 학원 범위) | 학원 미소속 — `%s` 콘솔, 학원 생성·목록 |
            | staff | `staffA` | active | `BARAEDA-A` | 학원 A 관계자 정상 시나리오, `%s` |
            | staff | `staffB` | active | `BARAEDA-B` | 학원 B 관계자 — 격리 검증(학원 A 데이터 비노출 확인) |
            | staff | `staffPending` | pending | `BARAEDA-A` | 가입 승인 대기 — 로그인은 성공하고 접근 범위만 축소(§1.4) |
            | staff | `staffC` | active | `BARAEDA-C` | 학원이 `inactive` 여도 계정은 로그인 유지(O-01 시연) |
            | parent | `parentA1` | active | `BARAEDA-A` | 형제 학생 2명의 보호자 — `%s` |
            | parent | `parentA2` | active | `BARAEDA-A` | 계정 미연결 학생의 보호자 |
            | parent | `parentA3` | active | `BARAEDA-A` | 학생 A4 의 보호자 |
            | parent | `parentPending` | pending | `BARAEDA-A` | 가입 승인 대기 |
            | parent | `parentB1` | active | `BARAEDA-B` | 학원 B — 격리 검증 |
            | student | `studentA4` | active | `BARAEDA-A` | 본인 계정이 학생 레코드에 연결됨 |
            | student | `studentRejected` | rejected | `BARAEDA-A` | 가입 거절 — 로그인은 성공하고 접근 범위만 축소(§1.4) |
            | student | `studentB1` | active | `BARAEDA-B` | 학원 B — 격리 검증 |
            | driver | `driverA1` | active | `BARAEDA-A` | 정상 기사 — `%s` |
            | driver | `driverA2` | active | `BARAEDA-A` | 보조 기사 |
            | driver | `driverBlocked` | blocked | `BARAEDA-A` | 로그인 실패 누적 차단 — 로그인 거부 확인 |
            | driver | `driverB1` | active | `BARAEDA-B` | 학원 B — 격리 검증 |
            | escort | `escortA1` | active | `BARAEDA-A` | 동승자 — 승하차 기록 |
            | escort | `escortA2` | active | `BARAEDA-A` | 보조 동승자 |
            | escort | `escortB1` | active | `BARAEDA-B` | 학원 B — 격리 검증 |

            ### 태그 구성

            API 는 `docs/planning/API_SPEC.md` §2~§6 도메인 절에 대응해 `%s` · `%s` · `%s` · `%s` · `%s`
            5개 태그로 묶인다.
            """;

    /** OpenAPI 문서 빈. 안내문과 태그 5개를 등록한다. */
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("바래다 API")
                        .description(seedDescription())
                        .version("v0.0.1"))
                .tags(apiTags())
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /** {@link #DESCRIPTION_TEMPLATE} 의 {@code %s} 자리를 태그 이름으로 채운다. */
    private String seedDescription() {
        return DESCRIPTION_TEMPLATE.formatted(
                TAG_AUTH, TAG_ADMIN, TAG_STAFF, TAG_PARENT_STUDENT, TAG_MANAGER, TAG_AUTH, TAG_PARENT_STUDENT, TAG_MANAGER, TAG_STAFF, TAG_ADMIN);
    }

    /** Swagger 태그 6개(도메인 5 + local 전용 dev) — 설명은 API_SPEC 도메인 절 대응. */
    private List<Tag> apiTags() {
        return List.of(
                new Tag().name(TAG_AUTH)
                        .description("로그인·가입 신청·학원 검색 (docs/planning/API_SPEC.md §2). 일부는 비인증 허용."),
                new Tag().name(TAG_PARENT_STUDENT)
                        .description("학부모·학생 앱 — 자녀 조회, 실시간 위치, 승하차지 변경 요청 (§3)."),
                new Tag().name(TAG_MANAGER)
                        .description("매니저 앱(버스기사·동승자) — 담당 회차, 승하차 처리, 운행 시작·종료 (§4)."),
                new Tag().name(TAG_STAFF)
                        .description("관계자 웹 — 학원 단위 가입 승인·구성원 관리, 노선·배차 (§5)."),
                new Tag().name(TAG_ADMIN)
                        .description("메인 관리자 콘솔 — 전 학원 범위, 학원 생성·현황 (§6)."),
                // local 기동에서만 오퍼레이션이 채워진다 — 배포물에는 이 태그 아래 아무것도 없다.
                new Tag().name(ApiTags.DEV)
                        .description("개발 도구 — DB 를 시드 상태로 되돌린다. local 프로파일 전용."));
    }
}
