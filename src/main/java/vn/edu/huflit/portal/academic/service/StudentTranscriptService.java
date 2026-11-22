package vn.edu.huflit.portal.academic.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.service.CurriculumEvaluationService;
import vn.edu.huflit.portal.curriculum.service.CurriculumTracks;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
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
    private final CurriculumRepository curricula;
    private static final Pattern TERM = Pattern.compile("(20\\d{2})-([123])");

    public StudentTranscriptResponse get(User student) {
        List<Issue> issues = new ArrayList<>();
        Curriculum curriculum = assignedTree(student, issues);
        var history = records.findByStudentId(student.getId());
        var codes = history.stream().map(r -> r.getCourseCode()).filter(Objects::nonNull).collect(Collectors.toSet());
        if(curriculum!=null) for(var semester:curriculum.getSemesters())
            for(var course:CurriculumTracks.safe(semester.getCourses())) codes.add(course.getCourseCode());
        Map<String,Course> catalog = courses.findByCourseCodeIn(new ArrayList<>(codes)).stream()
            .collect(Collectors.toMap(Course::getCourseCode, c -> c, (a,b) -> a));
        List<Attempt> rows = new ArrayList<>();
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
        CurriculumPlan plan=planFor(student,curriculum,catalog,history,rows,official,issues);
        return new StudentTranscriptResponse(summary, semesters, rows, issues.stream().distinct().toList(), plan);
    }
    private Curriculum assignedTree(User student,List<Issue> issues) {
        if(student.getCurriculumId()==null){
            issues.add(new Issue(null,null,"curriculumPlan","Hồ sơ sinh viên thiếu liên kết CTĐT; giáo vụ cần kiểm tra dữ liệu nguồn."));
            return null;
        }
        try {
            var selected=CurriculumTracks.forStudent(curricula.findById(student.getCurriculumId()).orElse(null),student);
            Set<Integer> terms=new HashSet<>(); Set<String> codes=new HashSet<>();
            if(selected.getSemesters().isEmpty())throw new IllegalStateException("Cây chuyên ngành đã gán chưa có học kỳ dự kiến");
            for(var s:selected.getSemesters()) {
                if(s.getSemesterNumber()==null || s.getSemesterNumber()<1 || !terms.add(s.getSemesterNumber()))
                    throw new IllegalStateException("Cây chuyên ngành có số học kỳ dự kiến bị thiếu, không hợp lệ hoặc trùng lặp");
                for(var c:CurriculumTracks.safe(s.getCourses()))
                    if(c.getCourseCode()==null || c.getCourseCode().isBlank() || !codes.add(c.getCourseCode()))
                        throw new IllegalStateException("Cây chuyên ngành có mã học phần bị thiếu hoặc trùng lặp: " + c.getCourseCode());
            }
            return selected;
        } catch(IllegalArgumentException | IllegalStateException unavailable) {
            issues.add(new Issue(null,null,"curriculumPlan","Không thể đọc cây CTĐT: " + unavailable.getMessage() + ". Giáo vụ cần kiểm tra dữ liệu nguồn; lịch sử điểm vẫn được giữ nguyên."));
            return null;
        }
    }
    @SuppressWarnings("unchecked")
    private CurriculumPlan planFor(User student,Curriculum curriculum,Map<String,Course> catalog,
            List<vn.edu.huflit.portal.entity.AcademicRecord> history,List<Attempt> rows,Map<String,Object> official,List<Issue> issues) {
        if(curriculum==null)return new CurriculumPlan(false,issues.stream().filter(i->"curriculumPlan".equals(i.field()))
            .map(Issue::message).findFirst().orElse("Chưa có cây CTĐT hợp lệ để hiển thị học kỳ dự kiến. Không tự suy đoán số kỳ hoặc năm học."),
            id(student.getCurriculumId()),null,student.getChosenSpecializationCode(),List.of());
        var progress=(List<Map<String,Object>>)official.getOrDefault("groupProgress",List.of());
        Set<String> recognized=progress.stream().flatMap(g->((List<String>)g.getOrDefault("recognizedCourseCodes",List.of())).stream()).collect(Collectors.toSet());
        var replacements=(Map<String,List<String>>)official.getOrDefault("recognizedReplacements",Map.of());
        Map<Integer,String> calendar;
        try { calendar=TranscriptCalendar.resolve(curriculum,student); }
        catch(IllegalStateException invalid) {
            calendar=Map.of();
            issues.add(new Issue(null,null,"academicCalendar",invalid.getMessage()+". Vui lòng liên hệ giáo vụ xác minh lịch học."));
        }
        List<PlannedSemester> semesters=new ArrayList<>();
        for(var s:curriculum.getSemesters().stream().sorted(Comparator.comparing(Curriculum.SemesterItem::getSemesterNumber)).toList()) {
            List<PlannedCourse> planned=new ArrayList<>();
            for(var c:CurriculumTracks.safe(s.getCourses())) {
                String code=c.getCourseCode(); var course=catalog.get(code);
                String name=course==null?c.getCourseName():course.getCourseName();
                Integer credit=course==null||course.getCredits()==null||course.getCredits()<=0?null:course.getCredits();
                if(name==null || name.isBlank())issues.add(new Issue(code,"PLAN:"+s.getSemesterNumber(),"courseName","Thiếu tên học phần dự kiến trong danh mục."));
                if(credit==null)issues.add(new Issue(code,"PLAN:"+s.getSemesterNumber(),"credits","Thiếu tín chỉ học phần dự kiến trong danh mục; không cộng vào tín chỉ đã học."));
                List<String> actualTerms=rows.stream().filter(a->code.equals(a.courseCode())).map(Attempt::semesterCode).distinct().sorted().toList();
                boolean independent=curriculum.getRequirementGroups().stream().anyMatch(g->g.getKind()!=null&&g.getKind().startsWith("MANDATORY")
                    &&!Boolean.FALSE.equals(g.getCountsTowardGraduation())&&CurriculumTracks.safe(g.getEligibleCourseCodes()).contains(code));
                var optionalGroups=curriculum.getRequirementGroups().stream().filter(g->!Boolean.FALSE.equals(g.getCountsTowardGraduation())&&
                    (g.getKind()!=null&&g.getKind().startsWith("ELECTIVE")&&CurriculumTracks.safe(g.getEligibleCourseCodes()).contains(code)
                     ||"GRADUATION".equals(g.getKind())&&CurriculumTracks.safe(g.getCompletionOptions()).stream().anyMatch(o->CurriculumTracks.safe(o.getCourseCodes()).contains(code)))).toList();
                boolean optionalComplete=!independent && !optionalGroups.isEmpty() && optionalGroups.stream().allMatch(g->
                    progress.stream().anyMatch(p->Objects.equals(g.getGroupCode(),p.get("groupCode"))&&Boolean.TRUE.equals(p.get("complete"))));
                boolean replaced=recognized.contains(code)&&replacements.containsKey(code);
                String state=replaced?"RECOGNIZED_REPLACEMENT":!actualTerms.isEmpty()?"HAS_HISTORY":optionalComplete?"GROUP_COMPLETE":
                    history.stream().anyMatch(r->code.equals(r.getCourseCode()))?"HISTORY_UNVERIFIED":"NOT_STUDIED";
                planned.add(new PlannedCourse(code,name,credit,state,actualTerms,replaced?replacements.get(code):List.of()));
            }
            semesters.add(new PlannedSemester("PLAN:"+s.getSemesterNumber(),s.getSemesterNumber(),planned,calendar.get(s.getSemesterNumber())));
        }
        return new CurriculumPlan(true,calendar.isEmpty()?"Chưa có lịch học được xác nhận cho CTĐT, phiên bản, chuyên ngành và khóa này; vui lòng liên hệ giáo vụ.":"",
            id(curriculum.getId()),curriculum.getVersion(),student.getChosenSpecializationCode(),semesters);
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
