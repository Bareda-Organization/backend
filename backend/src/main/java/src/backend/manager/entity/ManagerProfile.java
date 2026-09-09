package src.backend.manager.entity;

import src.backend.global.common.enums.ManagerRole;

/**
 * 매니저의 수정 가능한 정보 묶음(API_SPEC §5.13) — {@code accountId} 와 {@code deletedAt} 은 여기 없다.
 *
 * <p>{@code accountId} 는 가입 승인(§5.2)이 채우는 값이라 관리 화면의 수정 대상이 아니고,
 * {@code deletedAt} 은 값 수정이 아니라 삭제라 별도 메서드({@link Manager#delete})가 받는다.
 *
 * <p><b>{@code null} 은 "바꾸지 않음" 이다</b>({@code AcademyProfile} 과 같은 규약) — PATCH 는 보낸
 * 필드만 반영한다.
 */
public record ManagerProfile(String name, String phone, ManagerRole role, WorkHours workHours) {
}
