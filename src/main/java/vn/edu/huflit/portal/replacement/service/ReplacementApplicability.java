package vn.edu.huflit.portal.replacement.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;

import java.time.Instant;
import vn.edu.huflit.portal.curriculum.service.CurriculumTracks;
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
        if (student == null || curriculum == null || student.getCurriculumId() == null) return List.of();
        Curriculum selected = CurriculumTracks.forStudent(curriculum, student);
        Set<String> originals = curriculumCourses(selected, student);
        Set<String> electives = new HashSet<>(), mandatory = new HashSet<>();
        for (var group : safe(selected.getRequirementGroups())) {
            if (group.getKind() != null && group.getKind().startsWith("ELECTIVE"))
                electives.addAll(safe(group.getEligibleCourseCodes()));
            if (group.getKind() != null && group.getKind().startsWith("MANDATORY"))
                mandatory.addAll(safe(group.getEligibleCourseCodes()));
        }
        for (var semester : safe(selected.getSemesters())) for (var course : safe(semester.getCourses())) {
            if ("ELECTIVE".equals(course.getType())) electives.add(course.getCourseCode());
            if ("MANDATORY".equals(course.getType())) mandatory.add(course.getCourseCode());
        }
        return timeline(repository.findAll()).stream().filter(window -> {
            ReplacementRule rule = window.rule();
            return supportedShape(rule) && !window.start().isAfter(asOf) && student.getMajorId().equals(rule.getMajorId())
                && safe(rule.getApplicableCohorts()).contains(student.getCohort())
                && originals.containsAll(safe(rule.getOldCourseCodes()))
                && (Collections.disjoint(rule.getOldCourseCodes(), mandatory)
                    || Collections.disjoint(rule.getNewCourseCodes(), electives));
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
            .filter(window -> window.verifies(now)).map(Window::rule).toList();
    }

    private static boolean supportedShape(ReplacementRule rule) {
        List<String> olds = safe(rule.getOldCourseCodes()), news = safe(rule.getNewCourseCodes());
        if (olds.stream().anyMatch(c -> c == null || c.isBlank()) || news.stream().anyMatch(c -> c == null || c.isBlank())
            || new HashSet<>(olds).size() != olds.size() || new HashSet<>(news).size() != news.size()
            || !Collections.disjoint(olds, news)) return false;
        return ("1-1".equals(rule.getRuleType()) && olds.size() == 1 && news.size() == 1)
            || ("1-N".equals(rule.getRuleType()) && olds.size() == 1 && news.size() > 1)
            || ("N-1".equals(rule.getRuleType()) && olds.size() > 1 && news.size() == 1);
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
