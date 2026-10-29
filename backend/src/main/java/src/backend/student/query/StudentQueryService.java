package src.backend.student.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.audit.service.AuditRecorder;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.LikeEscape;
import src.backend.global.request.PageParams;
import src.backend.global.request.SortParam;
import src.backend.global.response.PageResponse;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.student.dto.StudentDetailResponse;
import src.backend.student.dto.StudentListRequest;
import src.backend.student.dto.StudentListResponse;
import src.backend.student.dto.StudentSummaryResponse;
import src.backend.student.dto.WeeklyAddressResponse;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 관계자 웹의 학생 목록·검색·상세 조회(STU-01, API_SPEC §5.11).
 *
 * <p>목록과 상세가 <b>같은 보호자 연락처 조회</b>를 공유한다 — 연락처는 {@code student} 에 복제하지
 * 않고 매번 조인해 얻는 값이라(A-10), 두 경로가 따로 조립하면 한쪽만 {@code unlinked_at} 조건을
 * 빠뜨리는 식으로 같은 학생의 번호가 화면마다 달라진다.
 *
 * <p>클래스에 {@code @Transactional} 을 두지 않는다 — 감사 기록({@code AuditRecorder}, {@code REQUIRES_NEW})이 읽기
 * 트랜잭션 안에서 돌면 요청 하나가 DB 연결을 둘 쥐고, 동시 요청이 풀 크기에 닿으면 서로의 두 번째 연결을 기다려
 * 풀리지 않는다. 저장소 호출마다 짧은 읽기 트랜잭션이 돌고, 감사는 조회가 끝난 뒤에 기록한다 —
 * 조회가 예외로 끝나면 감사 호출에 닿지 않는다.
 */
@Service
@RequiredArgsConstructor
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

    /** {@code filter} 쿼리가 받는 값(§5.11, Ruling 815). */
    private static final Set<String> FILTERS = Set.of("guardian_unlinked", "address_missing");

    /** 검색어를 주지 않은 요청 — 빈 문자열이 {@code LIKE '%%'} 가 되어 전건과 같아진다. */
    private static final String NO_KEYWORD = "";

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final AuditRecorder auditRecorder;

    private final StudentListReader listReader;

    /**
     * 학생 목록·검색(STU-01) — 소속 학원의 재학생만 나온다.
     *
     * <p>{@code guardian_phone}(보호자 연락처 원본)이 L3 라, 그 값이 실린 학생마다 감사 {@code read} 1행을 남긴다
     * (Ruling 333 · BR-060) — 목록 한 번이 상세 100건과 같은 양을 내보내는데 상세만 감사하면 노출량이 큰 쪽이
     * 추적되지 않는다.
     */
    public StudentListResponse list(AuthUser requester, StudentListRequest request) {
        Long academyId = academyOf(requester);
        Page<Student> page = listReader.searchInNaturalOrder(academyId, keyword(request.q()), classNameOf(request.className()),
                filterOf(request.filter()), pageable(request));
        StudentListReader.GuardianLinks links = listReader.guardianLinksOf(academyId, page.getContent());
        Map<Long, WeeklyAddressStatus> addressStatuses = listReader.addressStatusesOf(academyId, page.getContent());

        List<StudentSummaryResponse> items = page.getContent().stream()
                .map(student -> StudentSummaryResponse.of(student, links.phones().get(student.getId()),
                        links.counts().getOrDefault(student.getId(), 0),
                        addressStatuses.getOrDefault(student.getId(), WeeklyAddressStatus.NONE).code()))
                .toList();
        // 실린 학생마다 1행(Ruling 333)이되 한 트랜잭션으로 — 학생마다 열면 페이지 100건이 트랜잭션 100개다(BR-213).
        Map<Long, Map<String, Object>> audited = new LinkedHashMap<>();
        items.stream().filter(item -> item.guardianPhone() != null)
                .forEach(item -> audited.put(Long.valueOf(item.studentId()),
                        Map.of("student_ids", List.of(item.studentId()), "fields", List.of("guardian_phone"))));
        auditRecorder.recordDataAccessReads(academyId, requester.accountId(), "student", audited);
        return StudentListResponse.of(PageResponse.of(page, items), listReader.summaryOf(academyId));
    }

    /**
     * 학생 상세(STU-01) — 남의 학원 학생과 퇴원생은 모두 {@code 404 STUDENT_NOT_FOUND} 다.
     *
     * <p>{@code photo_url}·{@code note}·{@code guardian_phone} 이 L3 다(FEATURE_SPEC §6.3) — 조회가
     * 성공한 뒤에만 감사를 남긴다(예외로 끝난 요청은 L3 를 아무것도 응답에 싣지 않았으므로
     * "L3 필드가 실린 응답을 실제로 읽었을 때" 조건에 들지 않는다).
     */
    public StudentDetailResponse detail(AuthUser requester, Long studentId) {
        Long academyId = academyOf(requester);
        Student student = studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(studentId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));

        StudentDetailResponse response = StudentDetailResponse.of(student,
                guardianStudentRepository.findLinkedGuardians(academyId, studentId));
        auditRecorder.recordDataAccessRead(academyId, requester.accountId(), "student", studentId,
                Map.of("student_ids", List.of(String.valueOf(studentId)), "fields",
                        List.of("photo_url", "note", "guardians")));
        return response;
    }

    /**
     * 학생의 요일별 승하차 주소 조회(STU-06, §5.11 · Ruling 498) — 입력은 학부모 몫(P-05)이고 관계자는 읽기만 한다.
     *
     * <p>주소 원문·좌표는 L3 라 조회가 성공한 뒤에만 감사 {@code read} 를 남긴다(상세와 같은 판단). 같은 행위자가 같은 학생을
     * 10분 안에 다시 조회하면 새 행을 쓰지 않는 묶기는 {@code AuditRecorder} 가 학생 단위로 판단한다(Ruling 445) — 여기서 따로
     * 묶지 않는다. 남의 학원 학생과 퇴원생은 {@code 404 STUDENT_NOT_FOUND} 다.
     */
    public WeeklyAddressResponse weeklyAddresses(AuthUser requester, Long studentId) {
        Long academyId = academyOf(requester);
        Student student = studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(studentId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));

        WeeklyAddressResponse response = WeeklyAddressResponse
                .from(weeklyAddressRepository.findAllByStudentIdAndAcademyId(student.getId(), academyId));
        auditRecorder.recordDataAccessRead(academyId, requester.accountId(), "student", studentId,
                Map.of("student_ids", List.of(String.valueOf(studentId)), "fields", List.of("weekly_address")));
        return response;
    }

    private Pageable pageable(StudentListRequest request) {
        return PageParams.of(request.page(), request.size())
                .toPageable(SortParam.parse(request.sort(), SORTABLE_FIELDS, DEFAULT_SORT).and(TIE_BREAKER));
    }

    /** 반 이름 필터 — 주지 않았거나 공백이면 전체를 뜻하는 빈 문자열이다(쿼리의 {@code :className = ''} 분기). */
    private String classNameOf(String className) {
        return className == null || className.isBlank() ? NO_KEYWORD : className;
    }

    /** {@code filter} 는 두 값만 받는다 — 그 밖의 값은 오타를 전체 목록으로 둔갑시키지 않도록 {@code 422} 다(§5.11, Ruling 815). */
    private String filterOf(String filter) {
        if (filter == null || filter.isBlank()) {
            return NO_KEYWORD;
        }
        if (!FILTERS.contains(filter)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "filter 값이 올바르지 않습니다: " + filter);
        }
        return filter;
    }

    private String keyword(String q) {
        return q == null || q.isBlank() ? NO_KEYWORD : LikeEscape.escape(q.trim());
    }

    /** 요청 주체의 소속 학원 — 관계자 웹에는 "전 학원 명단" 이라는 화면이 부재하므로 특정하지 못하면 거부한다. */
    private Long academyOf(AuthUser requester) {
        return AcademyScope.resolveListScope(requester, null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }
}
