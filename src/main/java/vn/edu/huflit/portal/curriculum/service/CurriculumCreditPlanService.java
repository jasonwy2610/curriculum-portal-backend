package vn.edu.huflit.portal.curriculum.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import java.util.*;
import java.util.stream.Collectors;

/** Calculates curriculum credit requirements from the actual catalogue and group quotas.
 * A draft may be incomplete; callers must not publish without full validation.
 */
@Service
@RequiredArgsConstructor
public class CurriculumCreditPlanService {
    private final CourseRepository courseRepository;

    public record CreditPlan(int total, int mandatory, int elective, Map<String,Integer> totalsByTrack, Map<String,Integer> mandatoryByTrack) {}

    public Optional<CreditPlan> calculate(Curriculum curriculum) {
        List<Curriculum.RequirementGroupItem> groups = curriculum.getRequirementGroups();
        if (groups == null || groups.isEmpty()) return Optional.empty();
        Map<String,Integer> credits = courseRepository.findAll().stream()
            .filter(c -> c.getCourseCode() != null && c.getCredits() != null && c.getCredits() > 0)
            .collect(Collectors.toMap(Course::getCourseCode, Course::getCredits, (a,b)->a));
        Set<String> tracks = new TreeSet<>();
        if (curriculum.getSpecializations() != null) for (Curriculum.SpecializationItem s : curriculum.getSpecializations())
            if (s != null && s.getCode() != null && !s.getCode().isBlank()) tracks.add(s.getCode());
        if (tracks.isEmpty()) tracks.add("COMMON");
        Map<String,Integer> totals = new LinkedHashMap<>();
        Integer uniformMandatory = null, uniformElective = null;
        Map<String,Integer> mandatoryByTrack = new LinkedHashMap<>();
        for (String track : tracks) {
            int mandatory = 0, elective = 0;
            Set<String> mandatoryCodes = new HashSet<>();
            for (Curriculum.RequirementGroupItem group : groups) {
                if (group == null || group.getKind() == null) return Optional.empty();
                if (group.getSpecializationCodes() != null && !group.getSpecializationCodes().isEmpty()
                    && !group.getSpecializationCodes().contains(track)) continue;
                if ("CONDITIONAL".equals(group.getKind()) || Boolean.FALSE.equals(group.getCountsTowardGraduation())) continue;
                if (group.getKind().startsWith("MANDATORY")) {
                    if (group.getEligibleCourseCodes() == null || group.getEligibleCourseCodes().isEmpty()) return Optional.empty();
                    for (String code : group.getEligibleCourseCodes()) {
                        Integer credit = credits.get(code);
                        if (credit == null || !mandatoryCodes.add(code)) return Optional.empty();
                        mandatory += credit;
                    }
                } else if (group.getKind().startsWith("ELECTIVE") || "GRADUATION".equals(group.getKind())) {
                    if (group.getRequiredCredits() == null || group.getRequiredCredits() <= 0) return Optional.empty();
                    elective += group.getRequiredCredits();
                } else return Optional.empty();
            }
            totals.put(track, mandatory + elective);
            mandatoryByTrack.put(track, mandatory);
            if (uniformMandatory == null) { uniformMandatory = mandatory; uniformElective = elective; }
            // Track-specific requirements are stored separately; root values are compatibility defaults.
        }
        if (uniformMandatory == null) return Optional.empty();
        return Optional.of(new CreditPlan(uniformMandatory + uniformElective,
            uniformMandatory, uniformElective, Collections.unmodifiableMap(totals), Collections.unmodifiableMap(mandatoryByTrack)));
    }
}
