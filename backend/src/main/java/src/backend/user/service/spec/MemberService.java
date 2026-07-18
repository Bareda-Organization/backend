package src.backend.user.service.spec;

import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.user.dto.CreateMemberRequest;
import src.backend.user.dto.MemberResponse;
import src.backend.user.entity.Role;

/**
 * 구성원(계정+학원 멤버십) 관리의 계약(인터페이스) — 등록/목록. 구현은 MemberServiceImpl.
 *
 * <p>self-service 가입(AuthService.signup)과 달리, 관리자가 자기 학원 구성원을 프로비저닝하는 경로다.
 */
public interface MemberService {

    /** 구성원 등록 — 계정 생성 + 학원·역할 부여(관리자·테넌트 격리). */
    MemberResponse register(AuthUser admin, CreateMemberRequest req);

    /** 학원 구성원 목록 — role 지정 시 해당 역할만(예: 기사 배차 드롭다운). */
    List<MemberResponse> list(AuthUser admin, Long tenantId, Role role);
}
