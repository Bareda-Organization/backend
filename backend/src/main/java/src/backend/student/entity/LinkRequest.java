package src.backend.student.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자녀 연결 요청 — 연결은 요청(보호자) → 코드 생성(학생) → 코드 입력(보호자) 3단계이며, 이
 * 첫 단계가 자체 식별자와 만료 시각을 반환하므로 {@code link_code} 와 별개 레코드가 필요하다
 * (ERD §3.2 · P-02 · S-05 · API_SPEC §3.2).
 *
 * <p>{@code created_at}/{@code updated_at} 쌍이 없어({@code requested_at}·{@code expires_at})
 * {@code BaseTimeEntity} 를 상속하지 않는다.
 */
@Entity
@Table(name = "link_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "guardian_id", nullable = false)
    private Long guardianId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Convert(converter = LinkRequestStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    private LinkRequestStatus status;

    private LinkRequest(Long guardianId, Long studentId, OffsetDateTime requestedAt, OffsetDateTime expiresAt) {
        this.guardianId = guardianId;
        this.studentId = studentId;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
        this.status = LinkRequestStatus.PENDING;
    }

    /** 보호자가 자녀 로그인 아이디로 연결을 신청하는 시점에 생성한다(P-02 · S-05). */
    public static LinkRequest uponRequest(Long guardianId, Long studentId, OffsetDateTime requestedAt,
            OffsetDateTime expiresAt) {
        return new LinkRequest(guardianId, studentId, requestedAt, expiresAt);
    }
}
