package src.backend.academy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.BaseTimeEntity;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 학원별 임계값 — 정책 상수 중 유일하게 "학원별 설정"으로 규정된 미승차 대기 시간을 담는다.
 * 나머지 정책 상수는 전역 값이라 컬럼이 없다(ERD §3.1 · FEATURE_SPEC §2.1 · M-13).
 *
 * <p>{@code academy.id} 를 그대로 PK 로 쓰는 1:1 확장 테이블이라 {@code @GeneratedValue} 를 두지
 * 않는다 — 값은 {@link Academy} 생성 시점에 호출자가 직접 넣는다(엔티티 작성 규약 §4).
 *
 * <p>이런 PK=FK 엔티티를 {@code JpaRepository.save()} 로 저장하면 식별자가 이미 채워져 있어
 * {@code SimpleJpaRepository.isNew()} 가 {@code false} 로 판정, {@code persist} 가 아니라
 * {@code merge}(행이 없으면 SELECT 후 INSERT) 경로를 탄다 — 동작은 하지만 {@code persist} 만
 * 가정하고 읽으면 오해한다. {@code Persistable<Long>} 구현 여부는 이 태스크 범위 밖이다.
 */
@Entity
@Table(name = "academy_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AcademySetting extends BaseTimeEntity {

    /** DB 컬럼 기본값과 같은 미승차 대기 기본 시간(분) — EXC-01. */
    public static final int DEFAULT_NO_SHOW_WAIT_MINUTES = 3;

    /** 미승차 대기 시간(분) 상한 — X-06, Ruling 257. 회차 확정 창(①/② 구간 경계, 출발 30분 전)과 같다. */
    public static final int MAX_NO_SHOW_WAIT_MINUTES = 30;

    @Id
    @Column(name = "academy_id")
    private Long academyId;

    @Column(name = "no_show_wait_minutes", nullable = false)
    private int noShowWaitMinutes;

    private AcademySetting(Long academyId, int noShowWaitMinutes) {
        this.academyId = academyId;
        this.noShowWaitMinutes = noShowWaitMinutes;
    }

    /** 학원 등록과 같은 트랜잭션에서 기본 대기 시간({@value #DEFAULT_NO_SHOW_WAIT_MINUTES}분)으로 생성한다. */
    public static AcademySetting forAcademy(Long academyId) {
        return new AcademySetting(academyId, DEFAULT_NO_SHOW_WAIT_MINUTES);
    }

    /**
     * 미승차 대기 시간(분)을 학원 담당자가 바꾼다(Phase 11 목표 4, API_SPEC §5.21
     * {@code PATCH /staff/academy-settings}). 하한·상한 둘 다 여기서 먼저 재확인한다 —
     * {@code CHECK (no_show_wait_minutes > 0 AND no_show_wait_minutes <= 30)}(V11, X-06,
     * Ruling 257)이 최후 방어선이고, 이 메서드는 그보다 먼저 422 로 끊어 사용자에게 원인을 알리는 자리다.
     */
    public void changeNoShowWaitMinutes(int noShowWaitMinutes) {
        if (noShowWaitMinutes <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "no_show_wait_minutes 는 1 이상이어야 합니다");
        }
        if (noShowWaitMinutes > MAX_NO_SHOW_WAIT_MINUTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "no_show_wait_minutes 는 " + MAX_NO_SHOW_WAIT_MINUTES + " 이하여야 합니다");
        }
        this.noShowWaitMinutes = noShowWaitMinutes;
    }
}
