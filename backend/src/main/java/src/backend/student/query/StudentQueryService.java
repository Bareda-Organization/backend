package src.backend.student.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.student.dto.StudentDetailResponse;
import src.backend.student.dto.StudentListRequest;
import src.backend.student.dto.StudentSummaryResponse;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 관계자 웹의 학생 목록·검색·상세 조회(STU-01, API_SPEC §5.11).
 *
 * <p>목록과 상세가 <b>같은 보호자 연락처 조회</b>를 공유한다 — 연락처는 {@code student} 에 복제하지
 * 않고 매번 조인해 얻는 값이라(A-10), 두 경로가 따로 조립하면 한쪽만 {@code unlinked_at} 조건을
 * 빠뜨리는 식으로 같은 학생의 번호가 화면마다 달라진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentQueryService {

    /**
     * {@code sort} 가 받는 필드(§1.8) — 이름 하나다.
     *
     * <p>{@code ix_student_academy_name}(부분 인덱스)이 이 축으로 서 있어(ERD §5.3), 다른 축을 열면
     * 목록 조회가 정렬 때문에 전건을 훑게 된다. 특이사항·연락처는 애초에 줄 세울 값이 아니다.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of("name", "name");

    /** 관계자가 명단에서 학생을 찾는 화면이라 <b>이름 오름차순</b>이 기본이다(§5.11 · ERD §5.3). */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    /**
     * 어떤 정렬에도 마지막으로 붙는 결정적 순서 — 동명이인의 순서를 DB 가 정하면 페이지를 넘길 때
     * 같은 학생이 두 번 나오거나 한 번도 안 나온다.
     */
    private static final Sort TIE_BREAKER = Sort.by(Sort.Direction.ASC, "id");

    /** 검색어를 주지 않은 요청 — 빈 문자열이 {@code LIKE '%%'} 가 되어 전건과 같아진다. */
    private static final String NO_KEYWORD = "";

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    /** 학생 목록·검색(STU-01) — 소속 학원의 재학생만 나온다. */
    public PageResponse<StudentSummaryResponse> list(AuthUser requester, StudentListRequest request) {
        Long academyId = academyOf(requester);
        Page<Student> page = studentRepository.searchByAcademyId(academyId, keyword(request.q()),
                pageable(request));
        Map<Long, String> phones = guardianPhonesOf(academyId, page.getContent());

        return PageResponse.of(page, page.getContent().stream()
                .map(student -> StudentSummaryResponse.of(student, phones.get(student.getId())))
                .toList());
    }

    /** 학생 상세(STU-01) — 남의 학원 학생과 퇴원생은 모두 {@code 404 STUDENT_NOT_FOUND} 다. */
    public StudentDetailResponse detail(AuthUser requester, Long studentId) {
        Long academyId = academyOf(requester);
        Student student = studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(studentId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));

        return StudentDetailResponse.of(student,
                guardianPhonesOf(academyId, List.of(student)).get(studentId));
    }

    /**
     * 한 페이지분 보호자 연락처를 한 번에 모은다 — 학생마다 질의를 붙이면 100건짜리 페이지가 질의
     * 100건이 된다(횡단 규칙 4).
     *
     * <p>학생 1명에 보호자가 여럿이면 <b>먼저 연결된 쪽</b>이 대표로 남는다 — 쿼리의 정렬과
     * {@code putIfAbsent} 가 함께 그 순서를 정한다. 사양의 {@code items[].guardian_phone} 이 단수라
     * 어느 하나를 골라야 하고, 고르는 규칙이 없으면 같은 화면이 새로고침마다 다른 번호를 보인다.
     */
    private Map<Long, String> guardianPhonesOf(Long academyId, List<Student> students) {
        if (students.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> phones = new LinkedHashMap<>();
        guardianStudentRepository
                .findGuardianPhonesByAcademyId(academyId, students.stream().map(Student::getId).toList())
                .forEach(row -> phones.putIfAbsent(row.getStudentId(), row.getPhone()));
        return phones;
    }

    private Pageable pageable(StudentListRequest request) {
        return PageParams.of(request.page(), request.size())
                .toPageable(SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT).and(TIE_BREAKER));
    }

    private String keyword(String q) {
        return q == null || q.isBlank() ? NO_KEYWORD : q.trim();
    }

    /** 요청 주체의 소속 학원 — 관계자 웹에는 "전 학원 명단" 이라는 화면이 부재하므로 특정하지 못하면 거부한다. */
    private Long academyOf(AuthUser requester) {
        return AcademyScope.resolveListScope(requester, null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }
}
