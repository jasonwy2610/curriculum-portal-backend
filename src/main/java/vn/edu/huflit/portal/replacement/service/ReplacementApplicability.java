package vn.edu.huflit.portal.replacement.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;

import java.time.Instant;
import java.util.*;

@Component
@RequiredArgsConstructor
public class ReplacementApplicability {
    private final ReplacementRuleRepository repository;

    public static boolean published(ReplacementRule rule) {
        return "PUBLISHED".equalsIgnoreCase(rule.getLifecycleStatus())
            || (rule.getLifecycleStatus() == null && "ACTIVE".equalsIgnoreCase(rule.getStatus()));
    }

    public static ObjectId family(ReplacementRule rule) {
        return rule.getRuleFamilyId() == null ? rule.getId() : rule.getRuleFamilyId();
    }

    public record Window(ReplacementRule rule, Instant start, Instant end) {
        public boolean contains(Instant date) {
            return !date.isBefore(start) && (end == null || date.isBefore(end));
        }

        public boolean verifies(Instant date) {
            return rule.getPublishedAt() != null && contains(date);
        }
    }

    public List<Window> windows(User student, Curriculum curriculum, Instant asOf) {
        if (student == null || student.getMajorId() == null
            || student.getCohort() == null || student.getCohort().isBlank()) return List.of();
        Set<String> originals = curriculum == null ? Set.of() : curriculumCourses(curriculum, student);
        return timeline(repository.findAll()).stream().filter(window -> {
            ReplacementRule rule = window.rule();
            return !window.start().isAfter(asOf) && student.getMajorId().equals(rule.getMajorId())
                && safe(rule.getApplicableCohorts()).contains(student.getCohort())
                && (curriculum == null || originals.containsAll(safe(rule.getOldCourseCodes())));
        }).toList();
    }

    /** Builds version windows before applying cohort, curriculum or evaluation date filters. */
    public static List<Window> timeline(List<ReplacementRule> rules) {
        Map<ObjectId, List<ReplacementRule>> families = new TreeMap<>();
        for (ReplacementRule rule : rules) {
            if (rule.getId() == null || !published(rule) || rule.getEffectiveDate() == null) continue;
            // A scheduled publication cancelled before it starts never supersedes its predecessor.
            if (rule.getEndsAt() != null && !rule.getEndsAt().toInstant().isAfter(startsAt(rule))) continue;
            families.computeIfAbsent(family(rule), ignored -> new ArrayList<>()).add(rule);
        }
        Comparator<ReplacementRule> newestFirst = Comparator
            .comparingInt((ReplacementRule rule) -> rule.getVersionNumber() == null ? 1 : rule.getVersionNumber())
            .thenComparing(ReplacementApplicability::startsAt)
            .thenComparing(ReplacementRule::getId).reversed();
        List<Window> result = new ArrayList<>();
        for (var versions : families.values()) {
            versions.sort(newestFirst);
            Instant nextStart = null;
            for (ReplacementRule rule : versions) {
                Instant start = startsAt(rule);
                // Once a successor starts, ending it never reactivates an older version.
                if (nextStart != null && !start.isBefore(nextStart)) continue;
                Instant end = nextStart;
                if (rule.getEndsAt() != null && (end == null || rule.getEndsAt().toInstant().isBefore(end)))
                    end = rule.getEndsAt().toInstant();
                if ("ACTIVE".equalsIgnoreCase(rule.getStatus())) {
                    result.add(new Window(rule, start, end));
                }
                nextStart = start;
            }
        }
        return result;
    }

    public List<ReplacementRule> current(User student, Curriculum curriculum, Instant now) {
        return windows(student, curriculum, now).stream()
            .filter(window -> window.contains(now)).map(Window::rule).toList();
    }

    private static Instant startsAt(ReplacementRule rule) {
        Date effective = rule.getEffectiveDate();
        Date published = rule.getPublishedAt();
        return (published != null && published.after(effective) ? published : effective).toInstant();
    }

    private static Set<String> curriculumCourses(Curriculum curriculum, User student) {
        Set<String> codes = new HashSet<>();
        for (var semester : safe(curriculum.getSemesters())) {
            for (var course : safe(semester.getCourses())) {
                if (appliesToSpecialization(course.getSpecializationCodes(), student)) codes.add(course.getCourseCode());
            }
        }
        for (var group : safe(curriculum.getRequirementGroups())) {
            if (!appliesToSpecialization(group.getSpecializationCodes(), student)) continue;
            codes.addAll(safe(group.getEligibleCourseCodes()));
            for (var option : safe(group.getCompletionOptions())) codes.addAll(safe(option.getCourseCodes()));
        }
        return codes;
    }

    private static boolean appliesToSpecialization(List<String> codes, User student) {
        return codes == null || codes.isEmpty()
            || (student.getChosenSpecializationCode() != null && codes.contains(student.getChosenSpecializationCode()));
    }

    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}
