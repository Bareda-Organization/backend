package src.backend.student.query;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.student.dto.StudentListResponse;
import src.backend.student.entity.Student;
import src.backend.student.query.WeeklyAddressStatus.WeeklySlot;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.StudentRepository.NameRow;
import src.backend.student.repository.StudentRepository.StudentCounts;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 학생 목록({@link StudentQueryService#list}) 한 페이지를 채우는 읽기 조각 — 이름 자연 정렬 검색 · 보호자 연락처와 연결 수 · 요일별 주소 상태 · 학원 전체 지표.
 * 서비스와 마찬가지로 트랜잭션을 열지 않는다(저장소 호출마다 짧은 읽기 트랜잭션) — 감사 기록이 같은 요청 안에서 두 번째 연결을 요구하기 때문이다.
 */
@Component
@RequiredArgsConstructor
class StudentListReader {

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    /**
     * 한 페이지분 보호자 연락처·연결 수를 한 번에 모은다 — 학생마다 질의를 붙이면 100건짜리 페이지가
     * 질의 100건이 된다(횡단 규칙 4). {@code guardian_count}(§5.11) 도 <b>같은 조회 결과에서</b> 센다 —
     * 별도 COUNT 질의를 하나 더 붙이면 이 메서드가 막으려던 질의 수 문제가 그대로 재발한다.
     *
     * <p>학생 1명에 보호자가 여럿이면 대표 연락처는 <b>먼저 연결된 쪽</b>이 남는다 — 쿼리의 정렬과
     * {@code putIfAbsent} 가 함께 그 순서를 정한다. 사양의 {@code items[].guardian_phone} 이 단수라
     * 어느 하나를 골라야 하고, 고르는 규칙이 없으면 같은 화면이 새로고침마다 다른 번호를 보인다.
     */
    GuardianLinks guardianLinksOf(Long academyId, List<Student> students) {
        if (students.isEmpty()) {
            return new GuardianLinks(Map.of(), Map.of());
        }
        Map<Long, String> phones = new LinkedHashMap<>();
        Map<Long, Integer> counts = new LinkedHashMap<>();
        guardianStudentRepository
                .findGuardianPhonesByAcademyId(academyId, students.stream().map(Student::getId).toList())
                .forEach(row -> {
                    phones.putIfAbsent(row.getStudentId(), row.getPhone());
                    counts.merge(row.getStudentId(), 1, Integer::sum);
                });
        return new GuardianLinks(phones, counts);
    }

    /** {@link #guardianLinksOf} 결과 — 대표 연락처(단수)와 연결 수(§5.11 {@code guardian_count})를 함께 담는다. */
    record GuardianLinks(Map<Long, String> phones, Map<Long, Integer> counts) {}

    /**
     * 이름 자연 정렬 — DB 정렬은 문자열 순이라 "학생10" 이 "학생2" 앞에 온다. 조건에 맞는 학생의 id·이름만 읽어 자연
     * 순서로 줄 세우고, 요청한 쪽의 id 만 본 행으로 읽는다(보호자 연락처를 싣는 본 행은 한 쪽 분량만).
     *
     * <p>ponytail: 요청마다 그 학원의 일치 학생 전건(id·이름)을 읽는다 — 학원 하나가 수천 명이어도 ms 단위다. 수만 명을
     * 넘기면 ICU 숫자 정렬 collation + 표현식 인덱스로 DB 에 맡긴다.
     */
    Page<Student> searchInNaturalOrder(Long academyId, String q, String className, String filter,
            Pageable pageable) {
        Comparator<NameRow> byName = Comparator.comparing(NameRow::getName, NaturalNameOrder.INSTANCE);
        boolean descending = pageable.getSort().getOrderFor("name").isDescending();
        List<NameRow> rows = studentRepository.findNamesByAcademyId(academyId, q, className, filter).stream()
                .sorted((descending ? byName.reversed() : byName).thenComparing(NameRow::getId))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        List<Long> pageIds = rows.subList(from, to).stream().map(NameRow::getId).toList();
        Map<Long, Student> byId = studentRepository.findAllByAcademyIdAndIdIn(academyId, pageIds).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        List<Student> content = pageIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        return new PageImpl<>(content, pageable, rows.size());
    }

    /** 이 쪽 학생들의 요일별 주소 등록 상태 — 칸(요일·방향)만 한 번에 읽어 학생 수에 비례해 질의가 늘지 않는다. */
    Map<Long, WeeklyAddressStatus> addressStatusesOf(Long academyId, List<Student> students) {
        if (students.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<WeeklySlot>> slotsByStudent = weeklyAddressRepository
                .findSlotsByStudentIds(academyId, students.stream().map(Student::getId).toList()).stream()
                .collect(Collectors.groupingBy(slot -> slot.getStudentId(),
                        Collectors.mapping(slot -> new WeeklySlot(slot.getWeekday(), slot.getDirection()),
                                Collectors.toList())));
        Map<Long, WeeklyAddressStatus> statuses = new LinkedHashMap<>();
        slotsByStudent.forEach((studentId, slots) -> statuses.put(studentId, WeeklyAddressStatus.of(slots)));
        return statuses;
    }

    /** 학원 전체 지표(§5.11 {@code summary}) — 쿼리·쪽과 무관하다. */
    StudentListResponse.Summary summaryOf(Long academyId) {
        StudentCounts counts = studentRepository.summarizeByAcademyId(academyId);
        List<String> classNames = studentRepository.findDistinctClassNamesByAcademyId(academyId).stream()
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted(NaturalNameOrder.INSTANCE)
                .toList();
        return new StudentListResponse.Summary(counts.getTotal(), counts.getClassCount(),
                counts.getGuardianUnlinked(), counts.getAddressMissing(), counts.getCanGoAlone(), classNames);
    }
}
