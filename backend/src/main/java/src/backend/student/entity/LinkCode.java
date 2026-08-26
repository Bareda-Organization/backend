package src.backend.student.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자녀 연결 인증 코드 — 코드 대조를 서버가 수행하는 전제라 발급분을 서버가 보관한다. 만료·
 * 불일치는 {@code 403 LINK_CODE_INVALID} 로 판정한다(ERD §3.2 · P-02 · S-05 · API_SPEC §3.3).
 *
 * <p>{@code created_at} 만 있고 {@code updated_at} 이 없어 {@code BaseTimeEntity} 를 상속하지
 * 않는다. DB 에 {@code DEFAULT} 가 없어 호출자가 값을 반드시 직접 넘겨야 한다.
 */
@Entity
@Table(name = "link_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "link_request_id", nullable = false)
    private Long linkRequestId;

    @Column(name = "code", length = 10, nullable = false)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    private LinkCode(Long linkRequestId, String code, OffsetDateTime expiresAt, OffsetDateTime createdAt) {
        this.linkRequestId = linkRequestId;
        this.code = code;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** 학생 앱이 연결 요청에 대해 인증 코드를 생성하는 시점에 생성한다(S-05). */
    public static LinkCode forRequest(Long linkRequestId, String code, OffsetDateTime expiresAt,
            OffsetDateTime createdAt) {
        return new LinkCode(linkRequestId, code, expiresAt, createdAt);
    }

    /**
     * 이 코드를 지금 쓸 수 있는지 판정한다 — 아직 쓰지 않았고 만료 시각을 넘기지 않았을 때만 참이다.
     *
     * <p>{@code used_at} 과 {@code expires_at} 을 <b>한 메서드에서</b> 본다. 호출부가 둘을 따로
     * 물으면 응답을 갈라 답할 자리가 생기는데, {@code §3.4} 는 만료·불일치·재사용을 같은
     * {@code 403 LINK_CODE_INVALID} 로 답하도록 규정한다.
     *
     * <p>만료 경계는 <b>만료 시각 그 순간까지 유효</b>다({@code !now.isAfter(expiresAt)}) —
     * {@code VerificationCode#verify} 와 같은 형태로 두어, 같은 시스템 안에서 "만료" 의 뜻이 두 갈래로
     * 갈리지 않게 한다.
     *
     * @param now 주입된 {@code Clock} 에서 얻은 현재 시각(횡단 규칙 1) — 시스템 시계를 이 안에서
     *            직접 부르면 만료 단언을 고정할 수단이 사라진다
     */
    public boolean isUsable(OffsetDateTime now) {
        return usedAt == null && !now.isAfter(expiresAt);
    }

    /** 연결이 성립해 이 코드를 소비 처리한다 — 재사용 차단의 유일한 근거가 이 값이다(ERD §3.2). */
    public void markUsed(OffsetDateTime now) {
        this.usedAt = now;
    }
}
