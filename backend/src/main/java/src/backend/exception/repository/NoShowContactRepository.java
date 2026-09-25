package src.backend.exception.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.exception.entity.NoShowContact;

/**
 * {@link NoShowContact} 영속성 접근(Phase 11 목표 3) — {@code no_show_contact} 는 {@code no_show_case}
 * 를 부모로 두는 부모 경유 자원이다(ERD §6.1). 쓰기({@code save})만 쓴다 — 저장할 케이스는 호출부
 * ({@code NoShowContactCommandService})가 이미 학원 범위로 확인한 것이다.
 */
public interface NoShowContactRepository extends JpaRepository<NoShowContact, Long> {
}
