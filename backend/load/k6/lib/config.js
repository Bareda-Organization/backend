// 부하 시험 3종(시나리오 2·3·4) 공통 접속 설정 — bootRun 이 application-load.yml 로 뜬 인스턴스.
// 값을 바꿔야 하면 -e BASE_URL=... 로 넘긴다(파일을 고치지 않기 위함, application-load.yml 의
// 관례와 동일).
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
export const WS_URL = __ENV.WS_URL || 'ws://localhost:18080/ws/location';

// db/migration-local/V2__seed_data.sql · scenario2_prep.sql · scenario4_prep.sql 이 공통으로 쓰는
// 시드 비밀번호 평문. 해시(BCrypt)는 SQL 쪽에만 있고 여기는 로그인에 쓸 평문만 필요하다.
export const SEED_PASSWORD = 'password';
