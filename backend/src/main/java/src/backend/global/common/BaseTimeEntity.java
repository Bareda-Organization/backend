package src.backend.global.common;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * 생성/수정 시각을 자동으로 채워주는 공통 상위 클래스 — 시각 컬럼은 {@code timestamptz} 이므로
 * 오프셋을 보존하는 {@link OffsetDateTime} 을 쓴다(오프셋 없는 시각 타입은 이를 버린다, ERD §2).
 * 각 엔티티가 이 클래스를 상속하면 createdAt/updatedAt 을 직접 관리하지 않아도 된다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    private OffsetDateTime updatedAt;
}
