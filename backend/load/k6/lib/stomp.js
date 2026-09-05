// 최소 STOMP 1.2 프레임 조립·해석 — k6 는 STOMP 클라이언트를 내장하지 않으므로(k6/ws 는 raw
// WebSocket 만 준다) 이 시나리오들이 필요로 하는 세 프레임(CONNECT·SUBSCRIBE·수신 파싱)만 직접
// 만든다. API_SPEC §7 — 연결은 raw WebSocket 위 STOMP, 인증은 STOMP CONNECT 프레임의 네이티브
// Authorization 헤더, 구독 실패(권한 없음)는 4403 으로 소켓이 닫힌다.

// STOMP 프레임 구분자는 NUL(\0). 헤더 줄은 LF, 커맨드와 헤더 사이 빈 줄 하나.
export function connectFrame(token) {
    return 'CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\n\n\0';
}

export function subscribeFrame(id, destination) {
    return 'SUBSCRIBE\nid:' + id + '\ndestination:' + destination + '\n\n\0';
}

export function disconnectFrame() {
    return 'DISCONNECT\n\n\0';
}

// 서버가 한 WS 메시지에 프레임 여러 개를 이어 보낼 수 있어(하트비트 개행 포함) NUL 로 나눈다.
// 순수 개행(하트비트)만 있는 조각은 건너뛴다.
export function parseFrames(raw) {
    return raw
        .split('\0')
        .map((chunk) => chunk.replace(/^\n+/, ''))
        .filter((chunk) => chunk.trim().length > 0)
        .map(parseFrame);
}

function parseFrame(frame) {
    const lines = frame.split('\n');
    const command = lines[0];
    const headers = {};
    let i = 1;
    for (; i < lines.length; i++) {
        if (lines[i] === '') {
            i++;
            break;
        }
        const idx = lines[i].indexOf(':');
        if (idx > -1) {
            headers[lines[i].slice(0, idx)] = lines[i].slice(idx + 1);
        }
    }
    const body = lines.slice(i).join('\n');
    return { command, headers, body };
}
