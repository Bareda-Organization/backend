package src.backend.global.sms.spec;

/**
 * 문자 발송 포트(§7 규칙 12 교체 축 · Ruling 512) — 업체(알리고·솔라피 등)가 정해지면 구현체만 더한다.
 *
 * <p><b>이 빈이 없으면 문자를 쓰는 기능이 닫힌다</b> — 전화번호 복구({@code POST /auth/recover})는 이 빈이 없을 때
 * {@code 503 RECOVERY_UNAVAILABLE} 이다(Ruling 329). 구현체 선택은 {@code app.sms.sender} 한 곳이 정하고, 값이
 * 없으면 어떤 구현체도 뜨지 않는다 — 발송 없는 전화번호 인증은 정상 사용자에겐 불능이고 공격자에겐 대입 경로다.
 */
public interface SmsSender {

    /**
     * 한 건을 보낸다 — 본문에 인증 코드·임시 비밀번호가 실리므로 <b>호출부도 구현체도 번호·본문을 로그에 그대로
     * 남기지 않는다.</b>
     *
     * <p>성공을 반환값이 아니라 <b>예외 부재</b>로 표현한다({@code PushSender} 와 같은 계약) — 호출부는 이 호출을
     * 트랜잭션의 마지막에 두어, 실패하면 코드 발급·비밀번호 교체가 함께 되돌려진다.
     *
     * <p><b>구현체는 연결·읽기 시간 상한을 둔다(예: 3초)</b> — 이 호출은 트랜잭션·연결을 쥔 채 일어나므로 업체가 응답하지
     * 않을 때 상한이 없으면 연결 풀이 그 시간만큼 묶인다. 상한을 넘기면 예외로 알린다.
     *
     * @param phone 수신 번호(계정에 등록된 값 그대로)
     * @param text  본문
     * @throws RuntimeException 발송 실패
     */
    void send(String phone, String text);
}
