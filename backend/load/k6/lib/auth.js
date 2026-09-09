// 로그인 헬퍼 — API_SPEC §2.5. 요청·응답 필드는 전역 SNAKE_CASE 전략(Ruling 104)을 따르므로
// login_id/password 로 보내고 access_token 을 그대로 돌려준다.
import http from 'k6/http';
import { BASE_URL } from './config.js';

export function login(loginId, password) {
    const res = http.post(
        `${BASE_URL}/auth/login`,
        JSON.stringify({ login_id: loginId, password: password }),
        { headers: { 'Content-Type': 'application/json', 'X-Client-Type': 'app' } },
    );
    if (res.status !== 200) {
        throw new Error(`로그인 실패 login_id=${loginId} status=${res.status} body=${res.body}`);
    }
    return res.json().data.access_token;
}
