package vn.edu.huflit.portal.academic.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.service.CurriculumEvaluationService;
import vn.edu.huflit.portal.dto.response.StudentTranscriptResponse;
import vn.edu.huflit.portal.dto.response.StudentTranscriptResponse.*;
import vn.edu.huflit.portal.entity.User;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentTranscriptService {
    private final AcademicRecordRepository records;
    private final CourseRepository courses;
    private final CurriculumEvaluationService evaluation;
    private final GradeConversionService grades;
    private static final Pattern TERM = Pattern.compile("(20\\d{2})-([123])");

    public StudentTranscriptResponse get(User student) {
        var history = records.findByStudentId(student.getId());
        var codes = history.stream().map(r -> r.getCourseCode()).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String,Course> catalog = courses.findByCourseCodeIn(new ArrayList<>(codes)).stream()
            .collect(Collectors.toMap(Course::getCourseCode, c -> c, (a,b) -> a));
        List<Issue> issues = new ArrayList<>(); List<Attempt> rows = new ArrayList<>();
        Map<String,Object> official = evaluationFor(student, issues);
        Set<String> passFail = new HashSet<>((Collection<String>)official.getOrDefault("gpaExcludedPassFailCourseCodes", List.of()));
        Set<String> passed = new HashSet<>();
        boolean earnedIncomplete = false, missingNumericHistory = false;
        for (var record : history) {
            String code = record.getCourseCode(); Course course = catalog.get(code);
            String name = course == null ? null : course.getCourseName();
            if (name != null && name.isBlank()) name = null;
            Integer credit = course == null || course.getCredits() == null || course.getCredits() <= 0 ? null : course.getCredits();
            if (name == null || name.isBlank()) issues.add(new Issue(code, null, "courseName", "Thiếu tên học phần trong danh mục."));
            if (credit == null) issues.add(new Issue(code, null, "credits", "Thiếu số tín chỉ hợp lệ trong danh mục."));
            if (passFail.contains(code) && "PASSED".equals(record.getStatus())) passed.add(code);
            if (record.getAttempts() == null || record.getAttempts().isEmpty()) {
                issues.add(new Issue(code, null, "attempts", "Học phần chưa có lịch sử lần học; cần giáo vụ xác minh dữ liệu tổng hợp."));
                if (!passFail.contains(code)) { missingNumericHistory = true; if ("PASSED".equals(record.getStatus())) earnedIncomplete = true; }
            }
            int index = 0;
            for (var a : record.getAttempts() == null ? List.<vn.edu.huflit.portal.entity.AcademicRecord.AttemptItem>of() : record.getAttempts()) {
                index++;
                var matcher = TERM.matcher(String.valueOf(a.getSemesterCode()));
                boolean validTerm = matcher.matches();
                String termCode = validTerm ? a.getSemesterCode() : "UNKNOWN";
                String year = validTerm ? matcher.group(1) : null;
                Integer term = validTerm ? Integer.valueOf(matcher.group(2)) : null;
                if (!validTerm) issues.add(new Issue(code, termCode, "semesterCode", "Thiếu hoặc chưa xác định được năm học, học kỳ của lần học."));
                Double score = a.getScore10();
                boolean numeric = score != null && Double.isFinite(score) && score >= 0 && score <= 10;
                var grade = numeric ? grades.convert(score) : null;
                if (numeric) { if (score >= 4) passed.add(code); }
                else if (!passFail.contains(code)) issues.add(new Issue(code, termCode, "score10", "Lần học chưa có điểm số hợp lệ; không tự quy đổi điểm."));
                if (a.getCompletedAt() == null) issues.add(new Issue(code, termCode, "completedAt", "Lần học chưa có ngày hoàn thành được xác minh."));
                Integer number = a.getAttemptNumber();
                rows.add(new Attempt(id(record.getId()), a.getAttemptId(), id(record.getCurriculumId()), code, name, credit,
                    termCode, year, term, number, numeric ? score : null, grade == null ? null : grade.score4(),
                    grade == null ? null : grade.letterGrade(), grade != null ? grade.status() : passFail.contains(code)
                        && record.getAttempts().size() == 1 && Set.of("PASSED", "FAILED").contains(String.valueOf(record.getStatus())) ? record.getStatus() : "UNVERIFIED",
                    number == null ? index > 1 : number > 1, a.getCompletedAt()));
            }
        }
        int earned = 0;
        for (String code : passed) { Course c = catalog.get(code); if (c == null || c.getCredits() == null || c.getCredits() <= 0) earnedIncomplete = true; else earned += c.getCredits(); }
        @SuppressWarnings("unchecked") Map<String,Double> termGpa = (Map<String,Double>)official.getOrDefault("semesterGpa4", Map.of());
        @SuppressWarnings("unchecked") Map<String,Integer> termGpaCredits = (Map<String,Integer>)official.getOrDefault("semesterGpaCredits", Map.of());
        boolean incompleteGpa = missingNumericHistory || rows.stream().anyMatch(r -> !passFail.contains(r.courseCode()) && (r.credits() == null || r.score10() == null));
        List<Semester> semesters = new ArrayList<>();
        rows.stream().collect(Collectors.groupingBy(Attempt::semesterCode, TreeMap::new, Collectors.toList())).forEach((code, attempts) -> {
            boolean missingCredit = attempts.stream().anyMatch(a -> a.credits() == null);
            boolean missingGpaCredit = attempts.stream().anyMatch(a -> a.credits() == null && !passFail.contains(a.courseCode()));
            boolean missingGrade = attempts.stream().anyMatch(a -> a.score10() == null && !passFail.contains(a.courseCode()));
            semesters.add(new Semester(code, attempts.getFirst().academicYear(), attempts.getFirst().term(),
                missingGpaCredit || missingGrade ? null : termGpa.get(code), missingGpaCredit || missingGrade ? null : termGpaCredits.get(code),
                missingCredit ? null : attempts.stream().mapToInt(Attempt::credits).sum(),
                missingCredit ? null : attempts.stream().filter(a -> "PASSED".equals(a.status())).mapToInt(Attempt::credits).sum(), attempts.size()));
        });
        int failed = (int)rows.stream().filter(r -> "FAILED".equals(r.status()) && !passed.contains(r.courseCode())).map(Attempt::courseCode).distinct().count();
        Summary summary = new Summary(incompleteGpa ? null : (Double)official.get("cumulativeGpa4"),
            incompleteGpa ? null : (Integer)official.get("gpaCredits"), earnedIncomplete ? null : earned,
            (Integer)official.get("recognizedCredits"), failed);
        return new StudentTranscriptResponse(summary, semesters, rows, issues.stream().distinct().toList());
    }
    private Map<String,Object> evaluationFor(User student, List<Issue> issues) {
        if (student.getCurriculumId() == null) {
            issues.add(new Issue(null, null, "curriculum", "Chưa được gán CTĐT; GPA chính thức và tín chỉ công nhận chưa khả dụng."));
            return Map.of();
        }
        try { return evaluation.evaluate(student); }
        catch (IllegalArgumentException | IllegalStateException unavailable) {
            issues.add(new Issue(null, null, "curriculum", "Chưa thể đánh giá CTĐT đã gán. Vui lòng liên hệ giáo vụ để kiểm tra thông tin CTĐT/chuyên ngành."));
            return Map.of();
        }
    }
    private static String id(org.bson.types.ObjectId id) { return id == null ? null : id.toHexString(); }
}
