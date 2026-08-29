package src.backend.global.error;

import org.springframework.http.HttpStatus;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 전역에서 재사용하는 에러 코드.
 * HTTP 상태와 기본 메시지를 한 곳에 모아 응답 일관성을 유지한다.
 *
 * <p>{@code @RequiredArgsConstructor} 는 enum 에 {@code private} 생성자를 만든다 — 상수 선언이
 * 그대로 그 생성자를 부른다. 파라미터 순서는 <b>필드 선언 순서</b>({@code status} → {@code message})라,
 * 두 필드의 타입이 달라 순서가 뒤바뀌면 컴파일이 깨진다.
 */
@RequiredArgsConstructor
public enum ErrorCode {

    // 자격 증명이 아예 없는 접근 — 토큰 미동봉 STOMP CONNECT 등(API_SPEC §8).
    // TOKEN_EXPIRED 와 합치지 않는 이유는 클라이언트의 다음 동작이 갈리기 때문이다 —
    // 만료는 재발급을 시도할 자리이고, 부재는 로그인부터 해야 할 자리다.
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    // 이 시스템의 로그인 식별자는 login_id 이지 이메일이 아니다(API_SPEC §2.2·§2.5).
    // 옛 문구("이메일 또는...")가 남아 있어 사용자가 존재하지 않는 이메일 필드를 찾게 만드는
    // 결함이었다 — Task 4 발견·수정.
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다"),
    // pending 계정이 허용 목록 밖 API 를 호출할 때(API_SPEC §1.4·§8.1) — 401 이 아니라 403.
    AUTH_PENDING(HttpStatus.FORBIDDEN, "승인 대기 중인 계정입니다"),
    // rejected 계정이 허용 목록 밖 API 를 호출할 때(API_SPEC §8.1, 2026-08-25 신설) — AUTH_PENDING 과
    // 코드를 나눠, "승인 대기 중"과 "거절됨"을 클라이언트가 구별해 대기 화면에 거절 사유를 보여줄 수 있게 한다.
    AUTH_REJECTED(HttpStatus.FORBIDDEN, "가입이 거절된 계정입니다"),
    // blocked 계정의 로그인·API 호출(API_SPEC §1.4·§8.1) — 자격 오류(401)와 구분해야 재시도가 실패 카운터를 올리지 않는다.
    AUTH_ACCOUNT_BLOCKED(HttpStatus.FORBIDDEN, "차단된 계정입니다. 관리자에게 문의하세요"),
    // 미존재 계정 지정(API_SPEC §8.1) — 가입 상태 조회·재신청·본인 프로필 조회가 대상 계정을 못 찾을 때.
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다"),
    // 필수 필드 누락·형식 위반(API_SPEC §1.11) — Bean Validation·필수 쿼리 파라미터 부재가 공유한다.
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "입력값이 올바르지 않습니다"),
    // 회원가입(AUTH-01)에서 login_id 중복(API_SPEC §2.2).
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다"),
    // 회원가입·재신청이 존재하지 않거나 비활성인 학원을 가리킬 때(API_SPEC §2.2·§2.4).
    ACADEMY_NOT_FOUND(HttpStatus.NOT_FOUND, "학원을 찾을 수 없습니다"),
    // rejected 상태가 아닌 계정이 재신청을 시도할 때(API_SPEC §2.4).
    REAPPLY_NOT_ALLOWED(HttpStatus.CONFLICT, "재신청할 수 없는 상태입니다"),
    // access·refresh 만료, 또는 로그아웃·계정 차단으로 무효화된 토큰(API_SPEC §1.2·§2.6·§2.7·§8.1) — 재로그인 요구.
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다. 다시 로그인해 주세요"),
    // 아이디·비밀번호 복구(AUTH-08)의 SMS 인증 코드가 만료·불일치할 때(API_SPEC §2.9·§8.1).
    VERIFICATION_CODE_INVALID(HttpStatus.FORBIDDEN, "인증번호가 올바르지 않거나 만료되었습니다"),

    // ── 학원 격리 ────────────────────────────────────────────────────────────────
    // 계열별 구역 — 인증 계열은 위쪽 AUTH_* 무리에서 자란다(무리 사이에 끼워 넣으면 같은 줄 부근을
    // 여럿이 건드려 병합을 손으로 합치게 된다).
    //
    // 소속 학원 밖 자원 요청(API_SPEC §1.5·§8) — 메인 관리자 콘솔(§6)만 예외다. 404 가 아니라 403 인
    // 이유는, 존재 여부를 감추는 편이 안전해 보여도 사양이 격리 위반을 별도 코드로 구분하도록 정했기
    // 때문이다 — 클라이언트가 "없는 자원" 과 "남의 학원 자원" 을 구별해야 화면 문구가 갈린다.
    ACADEMY_SCOPE_VIOLATION(HttpStatus.FORBIDDEN, "소속 학원 밖 자원입니다"),

    // ── 학원 · 관계자 승인 · 계정 관리(Phase 3) ──────────────────────────────────
    // 이 무리는 Phase 3 의 세 태스크가 나눠 쓴다. 세 태스크가 각자 이 파일에 상수를 더하면 같은 줄
    // 부근을 셋이 건드려 병합을 손으로 합치게 되므로, 선행 태스크(T1)가 한 번에 전부 더하고 나머지는
    // 쓰기만 한다. HTTP 코드와 이름은 API_SPEC §8 에러 사전에서 그대로 옮겼다.
    //
    // 학원당 재직 관계자 1명 정원 초과(API_SPEC §6.5·§6.7·§8.5) — 승인과 status=active 전환 두 경로가 공유한다.
    STAFF_QUOTA_EXCEEDED(HttpStatus.CONFLICT, "이미 관계자가 있는 학원입니다"),
    // 이미 수락·거절된 승인 건의 재처리(API_SPEC §6.5·§8.3).
    APPROVAL_ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 처리된 요청입니다"),
    // 미존재 가입 요청 지정(API_SPEC §5.2·§6.5·§8.5).
    SIGNUP_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "가입 요청을 찾을 수 없습니다"),
    // 가입 승인 시 계정 ↔ 학생·매니저 레코드 연결 누락(AUTH-11 · API_SPEC §8.1) — 400 이 아니라 422 다.
    LINK_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "연결할 대상을 지정해야 합니다"),
    // 미존재 학생 지정(API_SPEC §8.5).
    STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다"),
    // 미존재 매니저 지정(MGR-03·04 · API_SPEC §8.5).
    MANAGER_NOT_FOUND(HttpStatus.NOT_FOUND, "매니저를 찾을 수 없습니다"),
    // 이미 연결된 자녀 재연결(P-02 · API_SPEC §8.5).
    ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연결된 대상입니다"),

    // ── 자녀 연결 3단계(Phase 5, P-02 · S-05) ────────────────────────────
    // 자녀 연결 인증 코드의 만료·불일치·재사용(API_SPEC §3.4·§8.1) — 셋을 같은 코드로 답한다.
    // 갈라 답하면 "이 코드는 실재하는데 만료됐다"·"이 코드는 이미 쓰였다" 가 미인증 응답으로 새어,
    // 6자리 숫자를 훑는 쪽이 어느 값이 실재하는지 가려낼 수 있다.
    LINK_CODE_INVALID(HttpStatus.FORBIDDEN, "인증 코드가 올바르지 않거나 만료되었습니다"),
    // 대기 중인 연결 요청 없이 학생이 코드 생성을 호출(S-05 · API_SPEC §3.3).
    // ⚠ §3.3 은 이 경우의 코드를 규정하지 않으나(고유 에러 부재) link_code.link_request_id 가 FK NN 이라
    // 요청 없이 코드를 만들 수단 자체가 부재하다. 404 를 고른 것은 지목된 자원(대기 중인 요청)이 없는
    // 형태가 SIGNUP_REQUEST_NOT_FOUND·APPROVAL_NOT_FOUND(§8.5)와 같기 때문이다(Ruling 143 — 사양의
    // 빈칸은 금지가 아니라 미완). 조율자 판정 대상으로 보고서에 신고했다.
    LINK_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "대기 중인 연결 요청이 없습니다"),
    // blocked 아닌 계정에 차단 해제 시도(AUTH-06 · API_SPEC §8.1).
    ACCOUNT_NOT_BLOCKED(HttpStatus.CONFLICT, "차단된 계정이 아닙니다"),
    // 퇴사 처리된(academy_staff.status='inactive') 관계자의 로그인(API_SPEC §2.5·§6.7·§8.1, Ruling 143).
    // refresh 무효화는 그 순간의 세션만 끊으므로, 이 코드가 없으면 비밀번호를 아는 퇴사자가 다시
    // 로그인해 role=staff 권한을 되찾는다 — 관계자는 학생 개인정보 전체에 접근한다(§6.7).
    // AUTH_ACCOUNT_BLOCKED 와 코드를 나눈 이유는 클라이언트의 다음 동작이 갈리기 때문이다 —
    // 차단은 관리자에게 해제를 요청할 자리이고, 퇴사는 요청할 대상 자체가 부재한 자리다.
    AUTH_STAFF_INACTIVE(HttpStatus.FORBIDDEN, "퇴사 처리된 계정입니다"),
    // 가입 승인 대상 계정이 blocked — 요청 주체는 정상 권한 보유(API_SPEC §5.2·§6.5·§8.1, Ruling 147).
    // AUTH_ACCOUNT_BLOCKED 를 재사용하지 않는 이유는 그 코드가 "요청 주체 자신이 차단"(§1.11)을
    // 가리키기 때문이다 — 재사용하면 승인 화면에 "차단된 계정입니다. 관리자에게 문의하세요" 가 떠
    // 정상 권한을 가진 승인자가 자신이 차단된 것으로 오해한다.
    // 403 이 아니라 409 인 것은 요청 주체가 인가돼 있고 막는 것이 대상 자원의 상태이기 때문이다 —
    // 같은 승인 경로의 APPROVAL_ALREADY_DECIDED 와 같은 형태이며, 같은 성격의 거부가 403·409 로
    // 갈리면 클라이언트가 분기를 두 벌 만들게 된다.
    SIGNUP_TARGET_BLOCKED(HttpStatus.CONFLICT, "차단된 계정은 승인할 수 없습니다"),

    // ── 차량 · 매니저 관리(Phase 5 Task 2) ───────────────────────────────────────
    // 미존재 차량 지정(BUS-03 · API_SPEC §5.12·§8.5). 다른 학원의 차량을 지목한 경우도 이 코드다 —
    // 학원 조건을 쿼리에 넣어 "없음" 과 "남의 학원" 을 같은 빈 결과로 만들면 존재 여부가 응답에서
    // 사라진다(StudentRepository#findByIdAndAcademyIdAndDeletedAtIsNull 과 같은 형태).
    BUS_NOT_FOUND(HttpStatus.NOT_FOUND, "차량을 찾을 수 없습니다"),
    // 같은 학원에 같은 호차 재등록(BUS-02·03 · API_SPEC §5.12·§8.5, Ruling 164).
    // 422 가 아니라 409 인 것은 요청 형식이 틀린 것이 아니라 자원이 충돌한 것이기 때문이다 —
    // DUPLICATE_LOGIN_ID(409)와 같은 형태이고, 클라이언트가 "입력을 고쳐라" 와 "이미 있다" 를
    // 같은 코드로 받으면 화면 문구를 가려 그릴 수 없다.
    DUPLICATE_BUS_NO(HttpStatus.CONFLICT, "이미 등록된 호차입니다"),
    // 회차에 배치된 매니저 삭제(MGR-04 · API_SPEC §5.13·§8.5).
    // 403 이 아니라 409 인 것은 요청 주체가 인가돼 있고 막는 것이 대상 자원의 상태이기 때문이다 —
    // STAFF_QUOTA_EXCEEDED·APPROVAL_ALREADY_DECIDED 와 같은 형태다.
    // ⚠ 삭제가 soft delete(deleted_at UPDATE)라 manager→assignment 의 FK RESTRICT 는 발동하지 않는다.
    // DB 가 이것을 막지 못하므로 이 코드를 던지는 선검사가 유일한 방어다.
    MANAGER_ASSIGNED(HttpStatus.CONFLICT, "회차에 배치된 매니저는 삭제할 수 없습니다"),

    // ── 스케줄 · 회차 · 배치(Phase 5 Task 5) ─────────────────────────────────────
    // 미존재 스케줄 지정(SCH-01 · API_SPEC §5.10·§8.5). 다른 학원의 스케줄을 지목한 경우도 이 코드다
    // — BUS_NOT_FOUND 와 같은 형태로, 학원 조건을 쿼리에 넣어 "없음" 과 "남의 학원" 을 같은 빈 결과로
    // 만들면 존재 여부가 응답에서 사라진다.
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "스케줄을 찾을 수 없습니다"),
    // 같은 차량·요일·방향·출발 시각 조합의 스케줄 중복(SCH-01 · API_SPEC §5.10·§8.5, Ruling 153).
    // 422 가 아니라 409 인 것은 요청 형식이 아니라 자원이 충돌한 것이기 때문이다 — DUPLICATE_BUS_NO 와
    // 같은 형태다.
    DUPLICATE_SCHEDULE(HttpStatus.CONFLICT, "이미 등록된 운행 스케줄입니다"),
    // 미존재 회차 지정(SCH-03 · API_SPEC §5.10·§5.14·§8.4). 타 학원 회차도 이 코드다.
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다"),
    // 같은 차량·날짜·방향·출발 시각의 회차를 임시 추가로 다시 만들려는 시도(SCH-03 · §5.10·§8.4).
    // ⚠ 일일 회차 생성 배치(SCH-02)는 이 코드를 내지 않는다 — 배치의 중복 실행은 재기동·수동 재실행이라는
    // 정상 동작이라 오류가 아니라 무시이고, 이미 있는 회차를 조용히 건너뛴다. 같은 UNIQUE 제약이
    // 두 경로에서 다르게 읽히는 것이 요점이다.
    DUPLICATE_RUN(HttpStatus.CONFLICT, "이미 등록된 회차입니다"),
    // 한 회차의 같은 역할을 두 요청이 동시에 채우려 함 — assignment(run_id, role) UNIQUE 위반
    // (MGR-05 · API_SPEC §5.14·§8.4). 순차 요청은 교체로 처리되므로 이 코드가 나오는 것은 경합뿐이다.
    // ⚠ 근무 시간·중복 배치 충돌과 다른 축이다 — 그쪽은 경고이고 저장되지만(MGR-06 · Ruling 152)
    // 이쪽은 저장 자체가 거부된다. 두 축을 섞으면 경고가 차단으로 굳는다.
    DUPLICATE_ASSIGNMENT(HttpStatus.CONFLICT, "이미 배치된 역할입니다"),
    // ── 요일별 주소 · 주소 검증 · 승하차지 매칭(Phase 5 Task 4) ──────────────────
    // 지오코딩이 "그런 주소는 없다"(totalCount == 0)고 답했을 때(P-05 · STU-05 · API_SPEC §3.7·§8.5).
    // 이 코드가 나오면 weekly_address 행은 남지 않는다 — 저장 보류가 사양이라 verified=false 행을
    // 적재하지 않는다(ERD weekly_address.verified).
    ADDRESS_VERIFICATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "주소를 확인할 수 없습니다"),
    // 지오코딩 공급자에 닿지 못했을 때 — 네트워크 오류 · 5xx · 서킷 개방(API_SPEC §8.5, Ruling 157).
    // ⚠ 위 ADDRESS_VERIFICATION_FAILED 와 합치지 않는다. 두 경우에 사용자가 할 일이 정반대다 —
    // 저쪽은 주소를 고쳐 다시 보낼 자리이고, 이쪽은 같은 주소를 이따가 다시 보낼 자리다. 합치면
    // 네이버가 5분 멈춘 동안 학부모 전원이 "우리 집 주소가 틀렸다" 는 안내를 받는다.
    // 422 가 아니라 503 인 것은 요청이 잘못된 것이 아니라 서버가 지금 처리할 수 없기 때문이다.
    ADDRESS_VERIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "주소 확인 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요"),
    // 한 요청의 entries[] 가 같은 (weekday, direction) 칸을 두 번 담았을 때 —
    // weekly_address(student_id, weekday, direction) UNIQUE 위반(P-05 · C-16 · API_SPEC §3.7·§8.5).
    // ⚠ 요청을 <b>다시</b> 보내는 것은 이 코드가 아니라 덮어쓰기다 — 같은 칸을 나중에 고치는 것은
    // 정상 동작(§3.7 즉시 반영, Ruling 151)이고, 막는 것은 한 요청 안의 자기모순뿐이다.
    // 422 가 아니라 409 인 것은 요청 형식이 아니라 자원이 충돌한 것이기 때문이다 — DUPLICATE_BUS_NO ·
    // DUPLICATE_SCHEDULE 과 같은 형태이며, 판정은 애플리케이션 선검사가 아니라 DB UNIQUE 다.
    DUPLICATE_WEEKLY_ADDRESS(HttpStatus.CONFLICT, "같은 요일·방향의 주소가 중복됐습니다"),

    // ── 알림 아웃박스(Phase 4) ──────────────────────────────────────────────────
    // 같은 dedup_key 의 알림이 이미 적재돼 있을 때(ERD notification_log UNIQUE) — 이벤트가 두 번
    // 배달됐다는 뜻이다. 그 거부를 옮기지 않으면 DataIntegrityViolationException 이 전역 핸들러의
    // catch-all 로 떨어져 사용자에게 500 이 나가고, "서버가 고장났다" 와 "이미 통지했다" 가
    // 구별되지 않는다(AcademyStaffQuota 의 STAFF_QUOTA_EXCEEDED 와 같은 형태).
    // 403 이 아니라 409 인 것은 막는 것이 권한이 아니라 대상 자원의 상태이기 때문이다.
    DUPLICATE_NOTIFICATION(HttpStatus.CONFLICT, "이미 적재된 알림입니다"),

    // ── 고정 노선 편성(Phase 6, RTE-01 · RTE-09 · A-08) ──────────────────────────
    // 미존재 고정 노선 지정(API_SPEC §5.9 · §8.5, Ruling 180). 다른 학원의 노선을 지목한 경우도 이
    // 코드다 — 학원 조건을 쿼리에 넣어 "없음" 과 "남의 학원" 을 같은 빈 결과로 만들면 존재 여부가
    // 응답에서 사라진다(BUS_NOT_FOUND · SCHEDULE_NOT_FOUND 와 같은 형태).
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "노선을 찾을 수 없습니다"),
    // 같은 차량·요일·방향 조합의 고정 노선 중복(RTE-01 · §5.9 · §8.5, Ruling 180) —
    // uk_route_bus_weekday_direction 위반. 422 가 아니라 409 인 것은 요청 형식이 아니라 자원이
    // 충돌한 것이기 때문이다(DUPLICATE_BUS_NO · DUPLICATE_SCHEDULE 과 같은 형태).
    // ⚠ 판정의 근거는 애플리케이션 선검사가 아니라 DB UNIQUE 다 — 동시 요청 2건은 서로의 미커밋
    // INSERT 를 못 봐 둘 다 선검사를 지나고, 그 거부를 옮기지 않으면 500 이 샌다.
    DUPLICATE_ROUTE(HttpStatus.CONFLICT, "이미 편성된 차량·요일·방향입니다"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

    private final HttpStatus status;
    private final String message;

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
