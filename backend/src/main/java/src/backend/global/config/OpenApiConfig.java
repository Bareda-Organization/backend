package src.backend.global.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * Swagger UI 최상단에 그대로 렌더링되는 안내문(Markdown).
     * <p>데모 계정표를 여기 두는 이유 — 이걸 모르면 "Try it out" 을 눌러도 401/403 만 보게 된다.
     * 값은 로컬 데모 시드({@code db/migration-local/V2__seed_data.sql}) 기준이다.
     */
    private static final String DESCRIPTION = """
            학원 통학버스 통합관리 시스템 백엔드 API.

            **테스트 순서** — `01. 인증(Auth)` 의 `POST /api/auth/login` 으로 토큰을 받고,
            우측 상단 **Authorize** 에 `accessToken` 을 넣는다. 이후 모든 요청에 `Authorization: Bearer <token>` 이 자동으로 붙는다.
            각 API 의 예시값(example)은 아래 시드 데이터 기준이라 **그대로 Try it out 하면 성공**한다.

            ### 데모 계정 — 비밀번호는 전부 `password`

            | id | 이메일 | 이름 | 역할 | 소속 | 이 계정으로 테스트하는 것 |
            |---|---|---|---|---|---|
            | 1 | `student@school.com` | 김민준 | STUDENT | 한빛학원 | 본인 위치 보고 · 본인 승하차 조회 · SOS |
            | 2 | `parent@school.com` | 이부모 | PARENT | 한빛학원 | 자녀 버스 실시간 위치 · 등하원 위치 변경 신청 · 알림함 (자녀 = 학생 1·2) |
            | 3 | `driver@school.com` | 박기사 | DRIVER | 한빛학원 | 운행 시작/종료 · 버스 위치 보고(3호차) · 명단 조회. **승하차는 기록하지 못한다(조회만)** |
            | 4 | `admin@school.com` | 한빛관리자 | ACADEMY_ADMIN | 한빛학원 | 구성원·학생 관리, 버스 상세, 배차 시뮬레이션, 노선 승인·배포, 관제 |
            | 5 | `platform@school.com` | 플랫폼관리자 | PLATFORM_ADMIN | 없음 | 학원 생성·전체 목록. 소속 학원이 없어 `tenantId` 를 **항상 직접 넣어야 한다** |
            | 6 | `attendant3@school.com` | 최선탑 | ATTENDANT | 한빛학원 | **승하차 기록(3호차)** · 담당 명단 조회 |
            | 7 | `attendant1@school.com` | 윤선탑 | ATTENDANT | 한빛학원 | 1호차 선탑자 |
            | 8 | `gaon.attendant@school.com` | 가온선탑 | ATTENDANT | 가온에듀 | 학원 격리 확인용(다른 학원 데이터가 안 보이는지) |

            ### 시드 주요 id

            - **학원(tenant)** — 1 한빛학원 · 2 가온에듀 · 3 미래코딩
            - **버스** — 1 3호차(한빛 / 기사 3 · 선탑자 6 · 노선 1 · 정원 25) · 2 1호차(한빛 / 선탑자 7 · 노선 2) · 3 2호차(가온 / 선탑자 8)
            - **학생** — 버스 1 : 1 김민준 · 2 이서연 · 3 박도윤 / 버스 2 : 4 최지우 · 5 정하율 · 6 강서준 (전부 활성)
            - **노선(route)** — 1 하원 A노선(정원 25) · 2 하원 B노선(정원 2) · 3 가온 1노선
            - **정류장(stop)** — 1 정류장 A(37.5010, 127.0275) · 2 정류장 B(37.5045, 127.0310) · 3 학원(37.5075, 127.0355)
            - **보호자 연결** — 학생 1·2 ↔ 학부모 user 2

            ### 자주 겪는 오해

            - **승하차 기록은 기사가 아니라 선탑자(ATTENDANT)** 다. 기사 토큰으로 `POST /api/ride-events` 를 부르면 403 이다.
            - **`tenantId` 파라미터는 학원 관리자면 생략**해도 본인 학원으로 해석된다. 플랫폼 관리자는 소속이 없어 반드시 넣어야 한다.
            - **삭제 계열은 물리 삭제가 아니다.** 구성원은 학원 멤버십만 해제되고 계정은 남으며, 학생은 `active=false` 로 비활성화될 뿐이다.
            """;

    /**
     * "00. MVP 사용 API" 태그는 여러 컨트롤러(Auth·Location·RideEvent·Routing·Notification)에 걸쳐
     * 메서드 단위로 {@code @Operation(tags = {...})}에 추가되므로, 클래스 레벨 {@code @Tag}만으로는
     * 설명(description)이 등록되지 않는다 — 여기 {@link OpenAPI#tags}에 명시적으로 등록해야 Swagger UI
     * 그룹 헤더에 설명이 뜬다.
     */
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("School-Bus API")
                        .description(DESCRIPTION)
                        .version("v0.0.1"))
                .tags(List.of(new Tag().name("00. MVP 사용 API")
                        .description("프론트(기사 앱·선탑자 앱·학부모 앱·관리자 웹)가 실제로 호출하는 API만 모은 묶음. "
                                + "기존 MVP 5개 기능(로그인·버스 실시간위치·승하차·배차 최적화·노선배포 알림)에 "
                                + "2026-08-02 확장분(선탑자 승하차 이관·학부모 실시간 관제/위치변경 신청·"
                                + "배차 변경안 시뮬레이션·버스 종합 상세·구성원/학생 관리와 매칭)이 더해져 있다. "
                                + "각 API는 원래 속한 카테고리에도 그대로 나온다.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
