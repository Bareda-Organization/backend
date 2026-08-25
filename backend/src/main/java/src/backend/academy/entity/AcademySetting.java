package src.backend.academy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.BaseTimeEntity;

/**
 * 학원별 임계값 — 정책 상수 중 유일하게 "학원별 설정"으로 규정된 미승차 대기 시간을 담는다.
 * 나머지 정책 상수는 전역 값이라 컬럼이 없다(ERD §3.1 · FEATURE_SPEC §2.1 · M-13).
 *
 * <p>{@code academy.id} 를 그대로 PK 로 쓰는 1:1 확장 테이블이라 {@code @GeneratedValue} 를 두지
 * 않는다 — 값은 {@link Academy} 생성 시점에 호출자가 직접 넣는다(엔티티 작성 규약 §4).
 */
@Entity
@Table(name = "academy_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AcademySetting extends BaseTimeEntity {

    /** DB 컬럼 기본값과 같은 미승차 대기 기본 시간(분) — EXC-01. */
    public static final int DEFAULT_NO_SHOW_WAIT_MINUTES = 3;

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
}
