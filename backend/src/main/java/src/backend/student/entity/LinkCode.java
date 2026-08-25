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
}
