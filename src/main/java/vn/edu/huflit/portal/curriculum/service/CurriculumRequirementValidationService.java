package vn.edu.huflit.portal.curriculum.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.major.repository.MajorRepository;
import java.util.*;
import java.util.stream.Collectors;

/** P0 validator: does not mutate MongoDB. Legacy curricula must be migrated explicitly. */
@Service
@RequiredArgsConstructor
public class CurriculumRequirementValidationService {
    private final CurriculumRepository curriculumRepository;
    private final CourseRepository courseRepository;
    private final MajorRepository majorRepository;

    public ValidationResult validate(String id) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("ID CTDT khong hop le");
        Curriculum curriculum = curriculumRepository.findById(new ObjectId(id))
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay CTDT"));
        return validate(curriculum);
    }

    public ValidationResult validate(Curriculum curriculum) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String,Integer> totals = new LinkedHashMap<>();
        Map<String,Map<String,Integer>> creditSummaries = new LinkedHashMap<>();
        var major = curriculum.getMajorId() == null ? Optional.<vn.edu.huflit.portal.major.model.Major>empty()
            : majorRepository.findById(curriculum.getMajorId());
        if (major.isEmpty() || !"ACTIVE".equalsIgnoreCase(major.get().getStatus()))
            errors.add("Ngành đào tạo không tồn tại hoặc đã ngừng áp dụng");
        if (!"EARLY".equals(curriculum.getSpecializationSelectionTiming())
            || !Integer.valueOf(1).equals(curriculum.getSpecializationSelectionSemester()))
            errors.add("Chuyên ngành phải được gán cố định từ học kỳ 1 (EARLY)");
        if (curriculum.getCohort() == null || !curriculum.getCohort().matches("K20[0-9]{2}"))
            errors.add("Khóa tuyển sinh phải có dạng K2026");
        Set<String> tracks = new LinkedHashSet<>();
        for (var spec : safe(curriculum.getSpecializations())) {
            if (spec == null || spec.getCode() == null || !spec.getCode().matches("[A-Z0-9_-]{2,20}")
                || blank(spec.getName()) || !tracks.add(spec.getCode())) {
                errors.add("Mã chuyên ngành phải hợp lệ, duy nhất và có tên");
                continue;
            }
            if (major.isPresent() && safe(major.get().getSpecializations()).stream().noneMatch(item ->
                item != null && spec.getCode().equals(item.getCode()) && "ACTIVE".equalsIgnoreCase(item.getStatus())))
                errors.add("Chuyên ngành không thuộc danh mục đang áp dụng của ngành: " + spec.getCode());
        }
        if (tracks.isEmpty()) errors.add("CTĐT phải có ít nhất một chuyên ngành");
        for (String key : CurriculumTracks.trees(curriculum).keySet())
            if (!tracks.contains(key)) errors.add("Cây không thuộc chuyên ngành của CTĐT: " + key);
        for (String track : tracks) {
            try {
                var rawTree = CurriculumTracks.trees(curriculum).get(track);
                List<Curriculum.SemesterItem> rawSemesters = rawTree == null
                    ? curriculum.getSemesters() : rawTree.getSemesters();
                List<Curriculum.RequirementGroupItem> rawGroups = rawTree == null
                    ? curriculum.getRequirementGroups() : rawTree.getRequirementGroups();
                for (var semester : safe(rawSemesters)) {
                    if (semester == null) { errors.add("[" + track + "] Học kỳ rỗng"); continue; }
                    for (var course : safe(semester.getCourses())) {
                        if (course == null) { errors.add("[" + track + "] Học phần rỗng"); continue; }
                        validateScope(course.getSpecializationCodes(), tracks, rawTree == null ? null : track, errors);
                    }
                }
                for (var group : safe(rawGroups)) {
                    if (group == null) { errors.add("[" + track + "] Nhóm yêu cầu rỗng"); continue; }
                    validateScope(group.getSpecializationCodes(), tracks, rawTree == null ? null : track, errors);
                    if (safe(group.getEligibleCourseCodes()).size() != new HashSet<>(safe(group.getEligibleCourseCodes())).size())
                        errors.add("[" + track + "] Mã học phần lặp trong nhóm: " + group.getGroupCode());
                    for (var option : safe(group.getCompletionOptions())) {
                        if (option == null) errors.add("[" + track + "] Phương án tốt nghiệp rỗng");
                        else {
                            Set<String> seenCourses = new HashSet<>();
                            for (String code : safe(option.getCourseCodes()))
                                if (!seenCourses.add(code)) errors.add("[" + track + "] Phương án " + option.getOptionCode()
                                    + " có mã học phần lặp: " + code);
                        }
                    }
                }
                Curriculum selected = CurriculumTracks.select(curriculum, track);
                selected.setSpecializations(List.of(new Curriculum.SpecializationItem(track, track)));
                validateTreeStructure(selected, track, errors);
                ValidationResult result = validateRequirements(selected);
                result.errors().forEach(error -> errors.add("[" + track + "] " + error));
                warnings.addAll(result.warnings());
                totals.put(track, result.computedCreditsBySpecialization().getOrDefault(track, 0));
                if (result.valid()) creditSummaries.putAll(result.creditSummariesBySpecialization());
            } catch (IllegalArgumentException | IllegalStateException ex) {
                errors.add("[" + track + "] " + ex.getMessage());
            }
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings, totals, creditSummaries);
    }

    private void validateScope(List<String> scope, Set<String> tracks, String explicit, List<String> errors) {
        for (String code : safe(scope))
            if (!tracks.contains(code) || (explicit != null && !explicit.equals(code)))
                errors.add("Phạm vi chuyên ngành không thuộc cây: " + code);
    }

    private void validateTreeStructure(Curriculum tree, String track, List<String> errors) {
        Set<Integer> semesterNumbers = new HashSet<>();
        Map<String,Integer> planned = new LinkedHashMap<>();
        Map<String,Curriculum.CurriculumCourseItem> items = new LinkedHashMap<>();
        Set<String> presentKinds = new HashSet<>();
        long electiveGroups = safe(tree.getRequirementGroups()).stream()
            .filter(group -> group.getKind() != null && group.getKind().startsWith("ELECTIVE")).count();
        if (electiveGroups != 1)
            errors.add("[" + track + "] Mỗi cây phải có đúng một nhóm tự chọn chung");
        for (var group : safe(tree.getRequirementGroups())) {
            if (group.getKind() != null && !Boolean.FALSE.equals(group.getCountsTowardGraduation()))
                presentKinds.add(group.getKind());
        }
        if (!presentKinds.contains("MANDATORY_COMMON") || !presentKinds.contains("MANDATORY_SPECIALIZATION")
            || presentKinds.stream().noneMatch(kind -> kind.startsWith("ELECTIVE")))
            errors.add("[" + track + "] Cây phải có nhóm bắt buộc chung, bắt buộc chuyên ngành và tự chọn");
        for (var semester : safe(tree.getSemesters())) {
            Integer number = semester.getSemesterNumber();
            if (number == null || number < 1 || !semesterNumbers.add(number))
                errors.add("[" + track + "] Học kỳ phải là số dương và không trùng");
            for (var course : safe(semester.getCourses())) {
                String code = course.getCourseCode();
                if (blank(code) || items.putIfAbsent(code, course) != null)
                    errors.add("[" + track + "] Mã học phần trống hoặc lặp trong cùng cây: " + code);
                planned.put(code, number);
                if (!List.of("MANDATORY", "ELECTIVE").contains(course.getType() == null ? "" : course.getType()))
                    errors.add("[" + track + "] Loại học phần không hợp lệ: " + code);
            }
        }
        if (planned.isEmpty()) errors.add("[" + track + "] Cây chưa có học phần theo học kỳ");
        Set<String> required = new HashSet<>();
        for (var group : safe(tree.getRequirementGroups())) {
            required.addAll(safe(group.getEligibleCourseCodes()));
            Set<String> optionCodes = new HashSet<>();
            for (var option : safe(group.getCompletionOptions()))
                if (option != null) {
                    if (blank(option.getOptionCode()) || !optionCodes.add(option.getOptionCode()))
                        errors.add("[" + track + "] Mã phương án tốt nghiệp trống hoặc trùng");
                    required.addAll(safe(option.getCourseCodes()));
                }
        }
        for (String code : required)
            if (!planned.containsKey(code)) errors.add("[" + track + "] Môn yêu cầu chưa được phân học kỳ: " + code);
        for (String code : planned.keySet())
            if (!required.contains(code)) errors.add("[" + track + "] Môn chưa thuộc nhóm yêu cầu: " + code);
        Set<String> electives = new HashSet<>();
        Set<String> nonElectives = new HashSet<>();
        for (var group : safe(tree.getRequirementGroups())) {
            if (group.getKind() != null && group.getKind().startsWith("ELECTIVE"))
                electives.addAll(safe(group.getEligibleCourseCodes()));
            else if (!"CONDITIONAL".equals(group.getKind())) {
                nonElectives.addAll(safe(group.getEligibleCourseCodes()));
                for (var option : safe(group.getCompletionOptions()))
                    if (option != null) nonElectives.addAll(safe(option.getCourseCodes()));
            }
        }
        for (String code : electives) {
            if (nonElectives.contains(code)) errors.add("[" + track + "] Môn tự chọn trùng nhóm khác: " + code);
            if (items.containsKey(code) && !"ELECTIVE".equals(items.get(code).getType()))
                errors.add("[" + track + "] Môn tự chọn phải có loại ELECTIVE: " + code);
        }
        for (var item : items.values())
            if ("ELECTIVE".equals(item.getType()) && !electives.contains(item.getCourseCode()))
                errors.add("[" + track + "] Môn tự chọn chưa thuộc nhóm tự chọn chung: " + item.getCourseCode());
        for (var course : items.values()) {
            for (String pre : safe(course.getPrerequisiteCourseCodes())) {
                Integer before = planned.get(pre), after = planned.get(course.getCourseCode());
                if (before == null || after == null || before >= after)
                    errors.add("[" + track + "] Tiên quyết phải ở học kỳ trước trong cùng cây: " + pre);
            }
        }
    }

    private ValidationResult validateRequirements(Curriculum curriculum) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Curriculum.RequirementGroupItem> groups = curriculum.getRequirementGroups();
        if (groups == null || groups.isEmpty()) {
            errors.add("CTDT chua khai bao requirementGroups; can migrate du lieu cu truoc khi cong bo");
            return new ValidationResult(false, errors, warnings, Collections.emptyMap());
        }
        Set<String> specCodes = curriculum.getSpecializations() == null ? Collections.emptySet()
                : curriculum.getSpecializations().stream().filter(Objects::nonNull)
                    .map(Curriculum.SpecializationItem::getCode).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> codes = new HashSet<>();
        Set<String> allCourses = new HashSet<>();
        for (Curriculum.RequirementGroupItem group : groups) {
            if (group == null || blank(group.getGroupCode()) || !codes.add(group.getGroupCode())) {
                errors.add("Ma nhom yeu cau bi trong hoac trung"); continue;
            }
            if (group.getKind() == null || !Set.of("MANDATORY_COMMON", "MANDATORY_SPECIALIZATION", "ELECTIVE_SPECIALIZATION",
                    "ELECTIVE_FREE", "GRADUATION", "CONDITIONAL").contains(group.getKind())) {
                errors.add("Loai nhom khong hop le: " + group.getGroupCode()); continue;
            }
            List<String> specs = safe(group.getSpecializationCodes());
            for (String spec : specs) if (!specCodes.contains(spec)) errors.add("Chuyen nganh khong ton tai: " + spec);
            if (group.getKind().equals("MANDATORY_SPECIALIZATION") || group.getKind().equals("ELECTIVE_SPECIALIZATION")) {
                if (specs.isEmpty() && specCodes.size() != 1)
                    errors.add("Nhom chuyen nganh thieu specializationCodes: " + group.getGroupCode());
            }
            if (group.getKind().startsWith("ELECTIVE") || group.getKind().equals("GRADUATION")) {
                if (group.getRequiredCredits() == null || group.getRequiredCredits() <= 0)
                    errors.add("Chi tieu tin chi phai > 0: " + group.getGroupCode());
            }
            if (group.getKind().startsWith("MANDATORY") && safe(group.getEligibleCourseCodes()).isEmpty())
                errors.add("Nhóm bắt buộc chưa có môn: " + group.getGroupCode());
            if (group.getKind().equals("GRADUATION") && safe(group.getCompletionOptions()).isEmpty())
                errors.add("Nhóm tốt nghiệp chưa có phương án: " + group.getGroupCode());
            allCourses.addAll(safe(group.getEligibleCourseCodes()));
            for (Curriculum.CompletionOptionItem option : safe(group.getCompletionOptions()))
                if (option != null) allCourses.addAll(safe(option.getCourseCodes()));
        }
        if (curriculum.getSemesters() != null) for (Curriculum.SemesterItem semester : curriculum.getSemesters())
            if (semester != null) for (Curriculum.CurriculumCourseItem item : safe(semester.getCourses()))
                if (item != null && !blank(item.getCourseCode())) allCourses.add(item.getCourseCode());
        Map<String, Integer> credits = courseRepository.findByCourseCodeIn(new ArrayList<>(allCourses)).stream()
                .filter(c -> c.getCourseCode() != null && c.getCredits() != null && c.getCredits() > 0)
                .collect(Collectors.toMap(Course::getCourseCode, Course::getCredits, (a,b)->a));
        for (String code : allCourses) if (!credits.containsKey(code)) errors.add("Mon khong co trong danh muc/so tin chi khong hop le: " + code);
        for (var semester : safe(curriculum.getSemesters())) for (var item : safe(semester.getCourses()))
            if (item.getCredits() != null && !Objects.equals(item.getCredits(), credits.get(item.getCourseCode())))
                errors.add("Tín chỉ học phần phải khớp danh mục: " + item.getCourseCode());
        // Validate prerequisite graph across planned courses. A prerequisite can be
        // scheduled in a different semester, but cannot be self-referential or cyclic.
        Map<String, List<String>> prerequisiteGraph = new HashMap<>();
        for (Curriculum.SemesterItem semester : safe(curriculum.getSemesters())) {
            if (semester == null) continue;
            for (Curriculum.CurriculumCourseItem item : safe(semester.getCourses())) {
                if (item == null || blank(item.getCourseCode())) continue;
                String code = item.getCourseCode();
                List<String> prerequisites = safe(item.getPrerequisiteCourseCodes());
                if (prerequisites.contains(code)) errors.add("Mon tien quyet trung voi chinh no: " + code);
                for (String pre : prerequisites)
                    if (!credits.containsKey(pre)) errors.add("Mon tien quyet khong ton tai: " + code + " -> " + pre);
                prerequisiteGraph.computeIfAbsent(code, k -> new ArrayList<>()).addAll(prerequisites);
            }
        }
        Map<String, Integer> visitState = new HashMap<>();
        for (String code : prerequisiteGraph.keySet())
            checkPrerequisiteCycles(code, prerequisiteGraph, visitState, errors);
        Map<String,Integer> totals = new LinkedHashMap<>();
        Set<String> tracks = new LinkedHashSet<>(specCodes);
        Map<String,Map<String,Integer>> summaries = new LinkedHashMap<>();
        if (tracks.isEmpty()) tracks.add("COMMON");
        for (String track : tracks) {
            int total = 0, mandatory = 0, elective = 0, graduation = 0;
            Set<String> usedMandatory = new HashSet<>();
            for (Curriculum.RequirementGroupItem group : groups) {
                if (group == null || blank(group.getGroupCode()) || group.getKind() == null) continue;
                if (!safe(group.getSpecializationCodes()).isEmpty() && !safe(group.getSpecializationCodes()).contains(track)) continue;
                String kind = group.getKind();
                if (kind.equals("CONDITIONAL") || Boolean.FALSE.equals(group.getCountsTowardGraduation())) continue;
                if (kind.startsWith("MANDATORY")) {
                    int mandatoryTotal = safe(group.getEligibleCourseCodes()).stream()
                        .mapToInt(code -> credits.getOrDefault(code, 0)).sum();
                    mandatory += mandatoryTotal;
                    if (!Objects.equals(group.getRequiredCredits(), mandatoryTotal) || mandatoryTotal <= 0)
                        errors.add("Chỉ tiêu bắt buộc không khớp danh sách môn: " + group.getGroupCode()
                            + " (đã cấu hình " + group.getRequiredCredits() + ", Backend xác định " + mandatoryTotal + " tín chỉ)");
                    for (String code : safe(group.getEligibleCourseCodes())) {
                        if (!usedMandatory.add(code)) errors.add("Mon bat buoc tinh trung trong " + track + ": " + code);
                        total += credits.getOrDefault(code, 0);
                    }
                } else {
                    int quota = group.getRequiredCredits() == null ? 0 : group.getRequiredCredits();
                    total += quota;
                    if (kind.startsWith("ELECTIVE")) {
                        elective += quota;
                        List<Integer> available = safe(group.getEligibleCourseCodes()).stream()
                            .map(code -> credits.getOrDefault(code, 0)).filter(c -> c > 0).toList();
                        if (!canMeetQuota(available, quota)) errors.add("Khong co to hop mon dat quota nhom " + group.getGroupCode());
                    } else if (kind.equals("GRADUATION")) {
                        graduation += quota;
                        if (safe(group.getCompletionOptions()).isEmpty()) errors.add("Thieu phuong an tot nghiep: " + group.getGroupCode());
                        for (Curriculum.CompletionOptionItem option : safe(group.getCompletionOptions())) {
                            if (option == null || safe(option.getCourseCodes()).isEmpty()) { errors.add("Phuong an tot nghiep rong"); continue; }
                            int optionCredits = safe(option.getCourseCodes()).stream().mapToInt(code -> credits.getOrDefault(code, 0)).sum();
                            if (optionCredits != quota) errors.add("Phuong an " + option.getOptionCode() + " khong du " + quota + " TC");
                        }
                    }
                }
            }
            // The common elective pool is disjoint from other groups. Reserve it
            // as a whole; no subset optimization or student registration is needed.
            List<List<Set<String>>> alternativesByGroup = new ArrayList<>();
            Set<String> reserved = new HashSet<>(usedMandatory);
            for (Curriculum.RequirementGroupItem group : groups)
                if (group != null && group.getKind() != null && group.getKind().startsWith("ELECTIVE"))
                    reserved.addAll(safe(group.getEligibleCourseCodes()));
            for (Curriculum.RequirementGroupItem group : groups) {
                if (group == null || group.getKind() == null || Boolean.FALSE.equals(group.getCountsTowardGraduation()) ||
                    (!safe(group.getSpecializationCodes()).isEmpty() &&
                     !safe(group.getSpecializationCodes()).contains(track))) continue;
                if ("GRADUATION".equals(group.getKind())) {
                    List<Set<String>> options = new ArrayList<>();
                    for (Curriculum.CompletionOptionItem option : safe(group.getCompletionOptions())) {
                        if (option == null) continue;
                        Set<String> selected = new LinkedHashSet<>(safe(option.getCourseCodes()));
                        if (!selected.isEmpty() && selected.size() == safe(option.getCourseCodes()).size() &&
                            Collections.disjoint(selected, reserved) &&
                            Objects.equals(selected.stream().mapToInt(c -> credits.getOrDefault(c, 0)).sum(), group.getRequiredCredits()))
                            options.add(selected);
                    }
                    alternativesByGroup.add(options);
                }
            }
            if (!jointlyFeasible(alternativesByGroup, 0, reserved))
                errors.add("Khong the phan bo tin chi dong thoi giua cac nhom cua chuyen nganh: " + track);
            totals.put(track, total);
            summaries.put(track, Map.of("requiredTotal", total, "mandatoryCredits", mandatory,
                "electiveCredits", elective, "graduationCredits", graduation));
            // Derived totals are authoritative; legacy manually-entered totals are not publication constraints.
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings, totals, summaries);
    }

    private static boolean jointlyFeasible(List<List<Set<String>>> groups, int index, Set<String> used) {
        if (index == groups.size()) return true;
        for (Set<String> option : groups.get(index)) {
            if (!Collections.disjoint(option, used)) continue;
            used.addAll(option);
            boolean feasible = jointlyFeasible(groups, index + 1, used);
            used.removeAll(option);
            if (feasible) return true;
        }
        return false;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static <T> List<T> safe(List<T> list) { return list == null ? Collections.emptyList() : list; }
    // Surplus is allowed; catalogue credit totals establish publication feasibility.
    private static boolean canMeetQuota(List<Integer> credits, int quota) {
        return quota > 0 && credits.stream().mapToInt(Integer::intValue).sum() >= quota;
    }
    private static void checkPrerequisiteCycles(String code, Map<String, List<String>> graph,
                                                Map<String, Integer> states, List<String> errors) {
        int state = states.getOrDefault(code, 0);
        if (state == 2) return;
        if (state == 1) {
            errors.add("Phat hien chu trinh tien quyet tai mon: " + code);
            return;
        }
        states.put(code, 1);
        for (String prerequisite : graph.getOrDefault(code, Collections.emptyList()))
            checkPrerequisiteCycles(prerequisite, graph, states, errors);
        states.put(code, 2);
    }
    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings,
                                   Map<String,Integer> computedCreditsBySpecialization,
                                   Map<String,Map<String,Integer>> creditSummariesBySpecialization) {
        public ValidationResult(boolean valid, List<String> errors, List<String> warnings, Map<String,Integer> totals) {
            this(valid, errors, warnings, totals, Map.of());
        }
    }
}
