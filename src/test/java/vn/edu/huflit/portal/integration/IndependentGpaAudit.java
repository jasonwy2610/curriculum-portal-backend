package vn.edu.huflit.portal.integration;

import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.entity.*;
import java.util.*;
import java.time.Instant;

/** Test oracle: no evaluator, applicability or grade-conversion service is called. */
final class IndependentGpaAudit {
    private int matched, mismatched, withReplacement;
    private final List<Map<String,Object>> differences = new ArrayList<>();

    void compare(User student, Curriculum program, List<AcademicRecord> records, Map<String,Course> catalog,
                 List<ReplacementRule> rules, Map<String,Object> actual) {
        var tree = program.getSpecializationTrees().get(student.getChosenSpecializationCode());
        if (tree == null) throw new IllegalArgumentException("Independent V2 oracle requires an explicit assigned tree");
        Set<String> eligible = new HashSet<>(), excluded = new HashSet<>(), elective = new HashSet<>();
        for (var group : tree.getRequirementGroups()) {
            List<String> codes = new ArrayList<>(Optional.ofNullable(group.getEligibleCourseCodes()).orElse(List.of()));
            if (group.getCompletionOptions() != null) for (var option : group.getCompletionOptions()) codes.addAll(option.getCourseCodes());
            eligible.addAll(codes);
            if (Boolean.FALSE.equals(group.getCountsTowardGpa())) excluded.addAll(codes);
            if (group.getKind().startsWith("ELECTIVE")) elective.addAll(codes);
        }
        Set<String> treeCodes = new HashSet<>();
        for (var semester : tree.getSemesters()) for (var course : semester.getCourses()) {
            treeCodes.add(course.getCourseCode());
            if (Boolean.FALSE.equals(course.getCountsTowardGpa()) || "PASS_FAIL".equalsIgnoreCase(course.getCompletionType())
                || "DAT_KHONG_DAT".equalsIgnoreCase(course.getCompletionType())) excluded.add(course.getCourseCode());
        }
        eligible.removeAll(excluded);
        Map<String,Double> highest = new HashMap<>();
        Map<String,List<AcademicRecord.AttemptItem>> attempts = new HashMap<>();
        for (var record : records) for (var attempt : Optional.ofNullable(record.getAttempts()).orElse(List.of()))
            if (attempt.getScore10() != null && Double.isFinite(attempt.getScore10()) && attempt.getScore10() >= 0 && attempt.getScore10() <= 10) {
                highest.merge(record.getCourseCode(), attempt.getScore10(), Math::max);
                attempts.computeIfAbsent(record.getCourseCode(), k -> new ArrayList<>()).add(attempt);
            }
        Map<String,Double> substitutes = new HashMap<>(); Set<String> usedSources = new HashSet<>();
        Instant now = Instant.now();
        for (var rule : rules.stream().sorted(Comparator.comparing(r -> r.getId().toHexString())).toList()) {
            if (!"PUBLISHED".equals(rule.getLifecycleStatus()) || rule.getPublishedAt() == null || rule.getEffectiveDate() == null
                || !Objects.equals(rule.getMajorId(), student.getMajorId()) || !rule.getApplicableCohorts().contains(student.getCohort())) continue;
            var targets = rule.getOldCourseCodes(); var sources = rule.getNewCourseCodes();
            if (!treeCodes.containsAll(targets) || targets.stream().anyMatch(c -> highest.getOrDefault(c, -1.0) >= 4 || substitutes.containsKey(c))
                || sources.stream().anyMatch(c -> elective.contains(c) || usedSources.contains(c))) continue;
            Instant start = later(rule.getPublishedAt().toInstant(), rule.getEffectiveDate().toInstant());
            Instant end = rule.getEndsAt() == null ? Instant.MAX : rule.getEndsAt().toInstant();
            Object family = rule.getRuleFamilyId() == null ? rule.getId() : rule.getRuleFamilyId();
            for (var successor : rules) if ("PUBLISHED".equals(successor.getLifecycleStatus()) && successor.getPublishedAt() != null
                && successor.getEffectiveDate() != null && Objects.equals(family, successor.getRuleFamilyId() == null ? successor.getId() : successor.getRuleFamilyId())
                && successor.getVersionNumber() != null && rule.getVersionNumber() != null && successor.getVersionNumber() > rule.getVersionNumber()) {
                Instant transition = later(successor.getPublishedAt().toInstant(), successor.getEffectiveDate().toInstant());
                if (transition.isBefore(end)) end = transition;
            }
            final Instant until = end;
            boolean valid = sources.stream().allMatch(c -> attempts.getOrDefault(c, List.of()).stream().anyMatch(a -> a.getScore10() >= 4
                && a.getAttemptId() != null && a.getCompletedAt() != null && !a.getCompletedAt().toInstant().isBefore(start)
                && a.getCompletedAt().toInstant().isBefore(until) && !a.getCompletedAt().toInstant().isAfter(now)));
            if (!valid) continue;
            double numerator = 0; int denominator = 0;
            for (String source : sources) { int credits = catalog.get(source).getCredits(); denominator += credits; numerator += points(highest.get(source)) * credits; }
            for (String target : targets) substitutes.put(target, numerator / denominator);
            usedSources.addAll(sources);
        }
        if (!usedSources.isEmpty()) withReplacement++;
        eligible.removeAll(usedSources);
        double weighted = 0; int credits = 0;
        for (String code : eligible) {
            Double grade = substitutes.get(code);
            if (grade == null && highest.containsKey(code)) grade = points(highest.get(code));
            if (grade != null) { int credit = catalog.get(code).getCredits(); weighted += grade * credit; credits += credit; }
        }
        Map<String,double[]> semesterSums = new TreeMap<>();
        for (var record : records) if (eligible.contains(record.getCourseCode()) || (usedSources.contains(record.getCourseCode()) && !excluded.contains(record.getCourseCode())))
            for (var attempt : attempts.getOrDefault(record.getCourseCode(), List.of())) {
                if (attempt.getSemesterCode() == null) continue;
                var sum = semesterSums.computeIfAbsent(attempt.getSemesterCode(), k -> new double[2]);
                int credit = catalog.get(record.getCourseCode()).getCredits(); sum[0] += points(attempt.getScore10()) * credit; sum[1] += credit;
            }
        Map<String,Double> semesters = new TreeMap<>(); semesterSums.forEach((k,v) -> semesters.put(k, rounded(v[0] / v[1])));
        Double cumulative = credits == 0 ? null : rounded(weighted / credits);
        boolean equal = Objects.equals(cumulative, actual.get("cumulativeGpa4")) && Objects.equals(credits, actual.get("gpaCredits"))
            && semesters.equals(actual.get("semesterGpa4"));
        if (equal) matched++; else { mismatched++; if (differences.size() < 10) differences.add(Map.of("studentId", student.getId().toHexString(),
            "expectedCumulative", String.valueOf(cumulative), "actualCumulative", String.valueOf(actual.get("cumulativeGpa4")),
            "expectedCredits", credits, "actualCredits", actual.get("gpaCredits"), "expectedSemester", semesters, "actualSemester", actual.get("semesterGpa4"))); }
    }
    Map<String,Object> report() { return Map.of("matched", matched, "mismatched", mismatched, "studentsWithReplacement", withReplacement, "differences", differences); }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static double rounded(double n) { return Math.round(n * 100.0) / 100.0; }
    // Independent threshold table specified by the academic grading policy.
    private static double points(double n) { double[] thresholds = {8.5,8,7,6,5.5,5,4}; double[] grades = {4,3.5,3,2.5,2,1.5,1};
        for (int i = 0; i < thresholds.length; i++) if (n >= thresholds[i]) return grades[i]; return 0; }
}
