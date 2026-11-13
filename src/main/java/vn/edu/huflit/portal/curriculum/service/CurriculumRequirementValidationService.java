package vn.edu.huflit.portal.curriculum.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import java.util.*;
import java.util.stream.Collectors;

/** P0 validator: does not mutate MongoDB. Legacy curricula must be migrated explicitly. */
@Service
@RequiredArgsConstructor
public class CurriculumRequirementValidationService {
    private final CurriculumRepository curriculumRepository;
    private final CourseRepository courseRepository;

    public ValidationResult validate(String id) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("ID CTDT khong hop le");
        Curriculum curriculum = curriculumRepository.findById(new ObjectId(id))
                .orElseThrow(() -> new IllegalArgumentException("Khong tim thay CTDT"));
        return validate(curriculum);
    }

    public ValidationResult validate(Curriculum curriculum) {
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
                if (specs.isEmpty()) errors.add("Nhom chuyen nganh thieu specializationCodes: " + group.getGroupCode());
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
        if (tracks.isEmpty()) tracks.add("COMMON");
        for (String track : tracks) {
            int total = 0;
            Set<String> usedMandatory = new HashSet<>();
            for (Curriculum.RequirementGroupItem group : groups) {
                if (group == null || blank(group.getGroupCode()) || group.getKind() == null) continue;
                if (!safe(group.getSpecializationCodes()).isEmpty() && !safe(group.getSpecializationCodes()).contains(track)) continue;
                String kind = group.getKind();
                if (kind.equals("CONDITIONAL")) continue;
                if (kind.startsWith("MANDATORY")) {
                    for (String code : safe(group.getEligibleCourseCodes())) {
                        if (!usedMandatory.add(code)) errors.add("Mon bat buoc tinh trung trong " + track + ": " + code);
                        total += credits.getOrDefault(code, 0);
                    }
                } else {
                    int quota = group.getRequiredCredits() == null ? 0 : group.getRequiredCredits();
                    total += quota;
                    if (kind.startsWith("ELECTIVE")) {
                        List<Integer> available = safe(group.getEligibleCourseCodes()).stream()
                            .map(code -> credits.getOrDefault(code, 0)).filter(c -> c > 0).toList();
                        if (!canMeetQuota(available, quota)) errors.add("Khong co to hop mon dat quota nhom " + group.getGroupCode());
                    } else if (kind.equals("GRADUATION")) {
                        if (safe(group.getCompletionOptions()).isEmpty()) errors.add("Thieu phuong an tot nghiep: " + group.getGroupCode());
                        for (Curriculum.CompletionOptionItem option : safe(group.getCompletionOptions())) {
                            if (option == null || safe(option.getCourseCodes()).isEmpty()) { errors.add("Phuong an tot nghiep rong"); continue; }
                            int optionCredits = safe(option.getCourseCodes()).stream().mapToInt(code -> credits.getOrDefault(code, 0)).sum();
                            if (optionCredits != quota) errors.add("Phuong an " + option.getOptionCode() + " khong du " + quota + " TC");
                        }
                    }
                }
            }
            // Local subset feasibility is insufficient when groups share eligible courses.
            // A single course cannot be allocated to two elective/graduation groups.
            List<List<Set<String>>> alternativesByGroup = new ArrayList<>();
            Set<String> reserved = new HashSet<>(usedMandatory);
            for (Curriculum.RequirementGroupItem group : groups) {
                if (group == null || group.getKind() == null ||
                    (!safe(group.getSpecializationCodes()).isEmpty() &&
                     !safe(group.getSpecializationCodes()).contains(track))) continue;
                if (group.getKind().startsWith("ELECTIVE")) {
                    alternativesByGroup.add(feasibleElectiveSets(safe(group.getEligibleCourseCodes()),
                        credits, group.getRequiredCredits() == null ? 0 : group.getRequiredCredits(), reserved));
                } else if ("GRADUATION".equals(group.getKind())) {
                    List<Set<String>> options = new ArrayList<>();
                    for (Curriculum.CompletionOptionItem option : safe(group.getCompletionOptions())) {
                        if (option == null) continue;
                        Set<String> selected = new LinkedHashSet<>(safe(option.getCourseCodes()));
                        if (!selected.isEmpty() && selected.size() == safe(option.getCourseCodes()).size() &&
                            Collections.disjoint(selected, reserved) &&
                            selected.stream().mapToInt(c -> credits.getOrDefault(c, 0)).sum() == group.getRequiredCredits())
                            options.add(selected);
                    }
                    alternativesByGroup.add(options);
                }
            }
            if (!jointlyFeasible(alternativesByGroup, 0, reserved))
                errors.add("Khong the phan bo tin chi dong thoi giua cac nhom cua chuyen nganh: " + track);
            totals.put(track, total);
            // Derived totals are authoritative; legacy manually-entered totals are not publication constraints.
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings, totals);
    }

    private static List<Set<String>> feasibleElectiveSets(List<String> eligible, Map<String,Integer> credits,
            int quota, Set<String> reserved) {
        List<Set<String>> options = new ArrayList<>();
        if (quota <= 0) return options;
        List<String> codes = eligible.stream().filter(c -> !reserved.contains(c))
            .distinct().sorted().toList();
        electiveDfs(codes, credits, quota, 0, 0, new LinkedHashSet<>(), options);
        return options;
    }

    private static void electiveDfs(List<String> codes, Map<String,Integer> credits, int quota,
            int index, int sum, Set<String> selected, List<Set<String>> options) {
        if (sum >= quota) { options.add(new LinkedHashSet<>(selected)); return; }
        if (index >= codes.size()) return;
        electiveDfs(codes, credits, quota, index + 1, sum, selected, options);
        String code = codes.get(index);
        selected.add(code);
        electiveDfs(codes, credits, quota, index + 1, sum + credits.getOrDefault(code, 0), selected, options);
        selected.remove(code);
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
    // A quota is feasible if a subset reaches it; recognized credits are capped at quota.
    private static boolean canMeetQuota(List<Integer> credits, int quota) {
        if (quota <= 0) return false;
        Set<Integer> sums = new HashSet<>(); sums.add(0);
        for (int c : credits) {
            Set<Integer> next = new HashSet<>(sums);
            for (int value : sums) next.add(value + c);
            sums = next;
        }
        return sums.stream().anyMatch(sum -> sum >= quota);
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
                                   Map<String,Integer> computedCreditsBySpecialization) {}
}
