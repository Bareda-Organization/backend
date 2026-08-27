package src.backend.student.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.security.AuthUser;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.dto.WeeklyAddressResponse;
import src.backend.student.entity.Student;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 학부모의 요일별 주소 조회(P-05, API_SPEC §3.7 {@code GET}) — 설정 화면의 초기값이다.
 *
 * <p>응답 형태가 {@code PATCH} 와 같은 것은 사양이다("응답은 PATCH 요청과 동일 구조") — 화면이 조회
 * 결과를 그대로 고쳐 되돌려 보내므로, 두 형태가 갈리면 클라이언트가 변환기를 하나 더 들게 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WeeklyAddressQueryService {

    private final LinkedChildLookup linkedChildLookup;

    private final WeeklyAddressRepository weeklyAddressRepository;

    /** 연결된 자녀의 요일 × 방향 주소 전부 — 아직 등록하지 않았으면 빈 목록이다. */
    public WeeklyAddressResponse list(AuthUser requester, Long studentId) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        return WeeklyAddressResponse.from(weeklyAddressRepository
                .findAllByStudentIdAndAcademyId(student.getId(), student.getAcademyId()));
    }
}
