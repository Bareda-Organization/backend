// 부하 시험 3종(시나리오 2·3·4) 공통 접속 설정 — bootRun 이 application-load.yml 로 뜬 인스턴스.
// 값을 바꿔야 하면 -e BASE_URL=... 로 넘긴다(파일을 고치지 않기 위함, application-load.yml 의
// 관례와 동일).
//
// ⚠ ApiPathPrefixConfig.API_PREFIX("/api/v1")가 컨트롤러 핸들러 등록 시점에 모든 REST 경로 앞에
// 자동으로 붙는다(SecurityConfig 의 permitAll 매처도 이 접두사를 붙여서 비교한다). BASE_URL 에 이
// 접두사를 안 넣으면 로그인(POST /auth/login)이 SecurityConfig 의 permitAll 목록(접두사 포함
// "/api/v1/auth/login")과 매치되지 않아 anyRequest().authenticated() 로 떨어져 항상 401 이 난다 —
// staffA 처럼 실재하는 계정도 예외 없이 막힌다(2026-09-05 시나리오 2 실행에서 실제로 재현·확인).
// WS_URL 은 별개다 — SecurityConfig 가 "/ws/**" 를 접두사 없이 그대로 permitAll 해 둬서(WebSocket
// 핸드셰이크엔 아직 토큰이 없으므로 인증은 STOMP CONNECT 프레임에서 따로 검증) 접두사를 붙이면 안 된다.
const API_PREFIX = '/api/v1';
export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:18080') + API_PREFIX;
export const WS_URL = __ENV.WS_URL || 'ws://localhost:18080/ws/location';

// db/migration-local/V2__seed_data.sql · scenario2_prep.sql · scenario4_prep.sql 이 공통으로 쓰는
// 시드 비밀번호 평문. 해시(BCrypt)는 SQL 쪽에만 있고 여기는 로그인에 쓸 평문만 필요하다.
export const SEED_PASSWORD = 'password';

// F5 S2 목표 2 재정정(2026-09-05) — academy live 채널(/topic/academy/{id}/live)은
// StompAuthChannelInterceptor#authorizeAcademyChannel 이 role=STAFF(또는 platform scope)만
// 통과시킨다(API_SPEC §7 권한 열 "해당 학원 관계자"). 시나리오 2의 VU 는 role=driver 라 그 계정으로
// 직접 구독하면 SUBSCRIBE 가 FORBIDDEN 으로 막혀 소켓이 4403 으로 즉시 닫힌다(실측 —
// ws_session_duration 평균 13ms, ws_connect_failures=40=VU 수×2). 그래서 "올리는 사람"과 "받는
// 사람"을 분리한다 — 위치는 기사 계정으로 REST POST 하고, 방송 관측은 STAFF 계정(academy 1 고정,
// db/migration-local 시드 계정)으로 WS 구독한다. scenario4_prep.sql 이 이미 같은 계정을 쓴다.
export const STAFF_OBSERVER_LOGIN_ID = 'staffA';
