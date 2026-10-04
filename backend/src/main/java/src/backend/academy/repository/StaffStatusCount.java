package src.backend.academy.repository;

import src.backend.academy.entity.StaffStatus;

/** 재직 상태별 집계 1행 — {@code GROUP BY academy_staff.status} 결과를 담는 조회 전용 투영이다(API_SPEC §6.6 {@code counts}). */
public interface StaffStatusCount {

    /** 집계한 재직 상태. */
    StaffStatus getStatus();

    /** 그 상태의 계정 수. */
    long getTotal();
}
