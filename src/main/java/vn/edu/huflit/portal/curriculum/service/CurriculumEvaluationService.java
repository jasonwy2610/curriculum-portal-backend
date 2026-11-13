package vn.edu.huflit.portal.curriculum.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.entity.AcademicRecord;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.academic.service.GradeConversionService;

import java.util.*;
import java.util.stream.Collectors;

/** P0.3 foundation: deterministic group-based credit recognition.
 * Replacement mappings and GPA are deliberately NOT inferred by this first stage.
 */
@Service
@RequiredArgsConstructor
public class CurriculumEvaluationService {
    private final CurriculumRepository curricula;
    private final CourseRepository courses;
    private final AcademicRecordRepository records;
    private final ReplacementRuleRepository replacementRules;
    private final GradeConversionService gradeConversion;
    private final RecognitionEvidenceRepository evidenceRepository;

    /** Read-only evaluation; never writes evidence. */
    public Map<String, Object> evaluate(User student) { return evaluateInternal(student, false); }

    /** Official evaluation records newly verified replacement entitlements. */
    public Map<String, Object> evaluateOfficial(User student) { return evaluateInternal(student, true); }

    private Map<String, Object> evaluateInternal(User student, boolean persistEvidence) {
        if (student == null || student.getCurriculumId() == null)
            throw new IllegalArgumentException("Sinh viên chưa được gán CTĐT");
        Curriculum curriculum = curricula.findById(student.getCurriculumId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy CTĐT"));
        if (curriculum.getRequirementGroups() == null || curriculum.getRequirementGroups().isEmpty())
            throw new IllegalStateException("CTĐT chưa cấu hình requirementGroups; cần chuyển đổi dữ liệu trước khi xét tiến độ");
        String specialization = student.getChosenSpecializationCode();
        if (curriculum.getSpecializations() != null && !curriculum.getSpecializations().isEmpty()
            && (specialization == null || specialization.isBlank()))
            throw new IllegalStateException("Sinh viên chưa có chuyên ngành cố định");
        Map<String, Integer> creditByCode = courses.findAll().stream()
            .filter(c -> c.getCourseCode() != null && c.getCredits() != null && c.getCredits() > 0)
            .collect(Collectors.toMap(Course::getCourseCode, Course::getCredits, (a,b) -> a));
        List<AcademicRecord> studentRecords = records.findByStudentId(student.getId());
        Map<String, Double> bestScore = new HashMap<>();
        // A pass/fail course can be completed without a numeric grade. Never
        // manufacture a GPA score for it.
        Set<String> nonNumericPassed = new HashSet<>();
        Map<String, Date> bestCompletedAt = new HashMap<>();
        Map<String, String> bestAttemptId = new HashMap<>();
        for (AcademicRecord record : studentRecords) {
            if (record.getCourseCode() != null && "PASSED".equalsIgnoreCase(record.getStatus()))
                nonNumericPassed.add(record.getCourseCode());
            if (record.getCourseCode() == null || record.getAttempts() == null) continue;
            for (AcademicRecord.AttemptItem attempt : record.getAttempts()) {
                if (attempt.getScore10() == null) continue;
                if (!Double.isFinite(attempt.getScore10()) || attempt.getScore10() < 0 || attempt.getScore10() > 10) continue;
                String code = record.getCourseCode();
                Double previous = bestScore.get(code);
                if (previous == null || attempt.getScore10() > previous) {
                    bestScore.put(code, attempt.getScore10());
                    bestCompletedAt.put(code, attempt.getCompletedAt());
                    bestAttemptId.put(code, attempt.getAttemptId());
                }
            }
        }
        // PASS_FAIL records may have no numeric attempt. Only explicit PASSED is
        // acceptable; an absent score is never interpreted as a passing grade.
        Set<String> passFailConfigured = new HashSet<>();
        for (Curriculum.SemesterItem semester : curriculum.getSemesters() == null
                ? List.<Curriculum.SemesterItem>of() : curriculum.getSemesters()) {
            if (semester == null || semester.getCourses() == null) continue;
            for (Curriculum.CurriculumCourseItem item : semester.getCourses()) {
                if (item == null || item.getCourseCode() == null) continue;
                if (item.getSpecializationCodes() != null && !item.getSpecializationCodes().isEmpty()
                    && !item.getSpecializationCodes().contains(specialization)) continue;
                if ("PASS_FAIL".equalsIgnoreCase(item.getCompletionType())
                    || "DAT_KHONG_DAT".equalsIgnoreCase(item.getCompletionType()))
                    passFailConfigured.add(item.getCourseCode());
            }
        }
        Set<String> passedWithoutScore = studentRecords.stream()
            .filter(r -> passFailConfigured.contains(r.getCourseCode())
                && "PASSED".equalsIgnoreCase(r.getStatus()))
            .map(AcademicRecord::getCourseCode).collect(Collectors.toSet());
        // Replacement recognition preview. Never infer a historical entitlement from
        // a rule whose effective date or scope cannot be established.
        Map<String, List<String>> replacementForOld = new TreeMap<>();
        Map<String, Double> replacementGrade4ForOld = new HashMap<>();
        Set<String> claimedReplacementCourses = new HashSet<>();
        List<String> skippedComplexRules = new ArrayList<>();
        List<String> historyVerificationRequired = new ArrayList<>();
        List<Map<String,Object>> recognitionEvidence = new ArrayList<>();
        List<RecognitionEvidence> historicalEvidence = evidenceRepository.findByStudentIdAndCurriculumId(
            student.getId(), curriculum.getId());
        // Historical entitlements are append-only, but malformed or conflicting records
        // must never silently inflate graduation progress. Validate the whole mapping
        // before consuming any of its courses (particularly for N-1 rules).
        Set<String> historicalOriginals = new HashSet<>();
        Set<ObjectId> historicalRuleIds = new HashSet<>();
        for (RecognitionEvidence item : historicalEvidence) {
            List<String> olds = unique(item.getOriginalCourseCodes());
            List<String> news = unique(item.getReplacementCourseCodes());
            String evidenceId = item.getId() == null ? "unknown" : item.getId().toHexString();
            if (olds.isEmpty() || news.isEmpty() || item.getRuleId() == null
                || item.getGrade4() == null || !Double.isFinite(item.getGrade4())
                || item.getGrade4() < 0 || item.getGrade4() > 4
                || item.getAttemptIds() == null || !item.getAttemptIds().keySet().containsAll(news)
                || news.stream().anyMatch(c -> item.getAttemptIds().get(c) == null
                    || item.getAttemptIds().get(c).isBlank())
                || olds.stream().anyMatch(news::contains)
                || !historicalRuleIds.add(item.getRuleId())
                || olds.stream().anyMatch(historicalOriginals::contains)
                || news.stream().anyMatch(claimedReplacementCourses::contains)) {
                historyVerificationRequired.add("EVIDENCE:" + evidenceId);
                continue;
            }
            historicalOriginals.addAll(olds);
            claimedReplacementCourses.addAll(news);
            for (String old : olds) {
                // Direct completion always wins, but evidence remains unchanged.
                if (bestScore.getOrDefault(old, -1.0) >= 4.0 || nonNumericPassed.contains(old)) continue;
                replacementForOld.put(old, news);
                replacementGrade4ForOld.put(old, item.getGrade4());
            }
        }
        List<ReplacementRule> scopedRules = replacementRules.findAll().stream()
            .filter(r -> "ACTIVE".equals(r.getStatus()))
            .filter(r -> "PUBLISHED".equals(r.getLifecycleStatus())
                || (r.getLifecycleStatus() == null && "ACTIVE".equals(r.getStatus())))
            .filter(r -> r.getEffectiveDate() != null && !r.getEffectiveDate().after(new Date()))
            .filter(r -> r.getMajorId() == null || r.getMajorId().equals(student.getMajorId()))
            .filter(r -> r.getApplicableCohorts() == null || r.getApplicableCohorts().isEmpty()
                || r.getApplicableCohorts().contains(student.getCohort()))
            .sorted(Comparator.comparing(ReplacementRule::getEffectiveDate)
                .thenComparing(r -> r.getId() == null ? "" : r.getId().toHexString()))
            .collect(Collectors.toList());
        // Consider all published versions, newest first, but allow an older version
        // when the newer one was not applicable at the student's completion date.
        // A family may contribute at most one successful recognition.
        Map<ObjectId, List<ReplacementRule>> versionsByFamily = new LinkedHashMap<>();
        for (ReplacementRule rule : scopedRules) {
            ObjectId family = rule.getRuleFamilyId() == null ? rule.getId() : rule.getRuleFamilyId();
            if (family == null) continue;
            versionsByFamily.computeIfAbsent(family, ignored -> new ArrayList<>()).add(rule);
        }
        Comparator<ReplacementRule> newestFirst = Comparator
            .comparing((ReplacementRule r) -> r.getVersionNumber() == null ? 1 : r.getVersionNumber())
            .thenComparing(ReplacementRule::getEffectiveDate)
            .thenComparing(r -> r.getId() == null ? "" : r.getId().toHexString())
            .reversed();
        for (List<ReplacementRule> familyVersions : versionsByFamily.values())
            familyVersions.sort(newestFirst);
        for (List<ReplacementRule> familyVersions : versionsByFamily.values()) {
            List<String> familyUnverified = new ArrayList<>();
            boolean familyRecognized = false;
            for (ReplacementRule rule : familyVersions) {
            List<String> olds = unique(rule.getOldCourseCodes());
            List<String> news = unique(rule.getNewCourseCodes());
            String ruleId = rule.getId() == null ? "unknown" : rule.getId().toHexString();
            boolean shapeValid = ("1-1".equals(rule.getRuleType()) && olds.size() == 1 && news.size() == 1)
                || ("1-N".equals(rule.getRuleType()) && olds.size() == 1 && news.size() > 1)
                || ("N-1".equals(rule.getRuleType()) && olds.size() > 1 && news.size() == 1);
            if (!shapeValid || olds.stream().anyMatch(news::contains)
                || olds.stream().anyMatch(c -> !creditByCode.containsKey(c))
                || news.stream().anyMatch(c -> !creditByCode.containsKey(c))) {
                skippedComplexRules.add(ruleId);
                continue;
            }
            // The old courses already passed are always recognized directly.
            // N-1 is treated atomically: do not replace only a subset of its old requirements.
            if (olds.stream().anyMatch(c -> bestScore.getOrDefault(c, -1.0) >= 4.0)
                || olds.stream().anyMatch(nonNumericPassed::contains)
                || olds.stream().anyMatch(historicalOriginals::contains)
                || olds.stream().anyMatch(replacementForOld::containsKey)
                || news.stream().anyMatch(claimedReplacementCourses::contains)
                || news.stream().anyMatch(c -> bestScore.getOrDefault(c, -1.0) < 4.0)) continue;
            // Publication must predate completion. Old data with no completedAt or
            // no publishedAt cannot establish a historical entitlement.
            boolean historicallyVerified = rule.getPublishedAt() != null
                && news.stream().allMatch(c -> bestAttemptId.get(c) != null
                    && !bestAttemptId.get(c).isBlank()
                    && bestCompletedAt.get(c) != null
                    && !bestCompletedAt.get(c).before(rule.getEffectiveDate())
                    && !bestCompletedAt.get(c).before(rule.getPublishedAt()));
            if (!historicallyVerified) {
                familyUnverified.add(ruleId);
                continue; // try an older published version if it is historically eligible
            }
            Map<String,Object> evidence = new LinkedHashMap<>();
            evidence.put("ruleId", ruleId);
            evidence.put("ruleFamilyId", rule.getRuleFamilyId() == null ? null : rule.getRuleFamilyId().toHexString());
            evidence.put("versionNumber", rule.getVersionNumber());
            evidence.put("oldCourseCodes", olds);
            evidence.put("newCourseCodes", news);
            evidence.put("effectiveDate", rule.getEffectiveDate());
            evidence.put("publishedAt", rule.getPublishedAt());
            evidence.put("attemptIds", news.stream().collect(Collectors.toMap(c -> c,
                c -> bestAttemptId.get(c) == null ? "MISSING" : bestAttemptId.get(c))));
            evidence.put("completedAt", news.stream().collect(Collectors.toMap(c -> c, bestCompletedAt::get)));
            recognitionEvidence.add(evidence);
            double points = news.stream().mapToDouble(c ->
                gradeConversion.convert(bestScore.get(c)).score4() * creditByCode.get(c)).sum();
            int totalCredits = news.stream().mapToInt(creditByCode::get).sum();
            double representativeGrade4 = points / totalCredits;
            RecognitionEvidence persisted = RecognitionEvidence.builder()
                .studentId(student.getId()).curriculumId(curriculum.getId())
                .ruleId(rule.getId()).ruleFamilyId(rule.getRuleFamilyId())
                .versionNumber(rule.getVersionNumber()).originalCourseCodes(new ArrayList<>(olds))
                .replacementCourseCodes(new ArrayList<>(news))
                .attemptIds(new LinkedHashMap<>((Map<String,String>) evidence.get("attemptIds")))
                .grade4(representativeGrade4).recognizedAt(new Date()).build();
            // Append-only evidence. Unique rule/student/curriculum index avoids duplicate recognition.
            boolean verifiedForCurrentEvaluation = true;
            try {
                if (persistEvidence) evidenceRepository.insert(persisted);
            } catch (org.springframework.dao.DuplicateKeyException alreadyRecognized) {
                // The winning write may contain different attempts or grades. Fail closed
                // for this evaluation; a fresh request will read the persisted evidence.
                historyVerificationRequired.add("CONCURRENT_EVIDENCE:" + ruleId);
                verifiedForCurrentEvaluation = false;
            }
            if (verifiedForCurrentEvaluation) {
                for (String old : olds) {
                    replacementForOld.put(old, news);
                    replacementGrade4ForOld.put(old, representativeGrade4);
                }
                claimedReplacementCourses.addAll(news);
            } else {
                recognitionEvidence.remove(evidence);
            }
            if (verifiedForCurrentEvaluation) {
                familyRecognized = true;
                break; // one recognized version per family
            }
            }
            if (!familyRecognized) historyVerificationRequired.addAll(familyUnverified);
        }
        Set<String> passed = bestScore.entrySet().stream().filter(e -> e.getValue() >= 4.0)
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        // Non-numeric pass status is only valid for courses explicitly configured
        // as PASS_FAIL; it is added after collecting curriculum metadata below.
        passed.addAll(replacementForOld.keySet());
        passed.addAll(passedWithoutScore);
        Set<String> configuredPassFail = new HashSet<>();
        if (curriculum.getSemesters() != null) for (Curriculum.SemesterItem semester : curriculum.getSemesters()) {
            if (semester == null || semester.getCourses() == null) continue;
            for (Curriculum.CurriculumCourseItem item : semester.getCourses()) {
                if (item == null || item.getCourseCode() == null) continue;
                if (item.getSpecializationCodes() != null && !item.getSpecializationCodes().isEmpty()
                    && !item.getSpecializationCodes().contains(specialization)) continue;
                if ("PASS_FAIL".equalsIgnoreCase(item.getCompletionType())
                    || "DAT_KHONG_DAT".equalsIgnoreCase(item.getCompletionType()))
                    configuredPassFail.add(item.getCourseCode());
            }
        }
        for (String code : configuredPassFail)
            if (nonNumericPassed.contains(code)) passed.add(code);
        // Reserve all mandatory requirements first, even when a course has not passed.
        // Otherwise an elective could consume a mandatory course and inflate progress.
        List<Curriculum.RequirementGroupItem> active = new ArrayList<>();
        Set<String> reservedMandatory = new HashSet<>();
        for (Curriculum.RequirementGroupItem group : curriculum.getRequirementGroups()) {
            if (group.getSpecializationCodes() != null && !group.getSpecializationCodes().isEmpty()
                && !group.getSpecializationCodes().contains(specialization)) continue;
            String kind = group.getKind() == null ? "" : group.getKind();
            if ("CONDITIONAL".equals(kind) || Boolean.FALSE.equals(group.getCountsTowardGraduation())) continue;
            int quota = group.getRequiredCredits() == null ? 0 : group.getRequiredCredits();
            if (quota <= 0) throw new IllegalStateException("Nhóm thiếu requiredCredits hợp lệ: " + group.getGroupCode());
            if (kind.startsWith("MANDATORY")) {
                List<String> codes = unique(group.getEligibleCourseCodes());
                if (codes.stream().mapToInt(c -> credits(c, creditByCode)).sum() != quota)
                    throw new IllegalStateException("Chỉ tiêu bắt buộc không khớp: " + group.getGroupCode());
                for (String code : codes) if (!reservedMandatory.add(code))
                    throw new IllegalStateException("Môn bắt buộc xuất hiện trong nhiều nhóm: " + code);
            } else if (!kind.startsWith("ELECTIVE") && !"GRADUATION".equals(kind)) {
                throw new IllegalStateException("Loại nhóm không được hỗ trợ: " + kind);
            }
            active.add(group);
        }
        // A replacement's actual code cannot simultaneously earn elective credits.
        Set<String> unavailableForOtherGroups = new HashSet<>(claimedReplacementCourses);
        List<List<List<String>>> choices = new ArrayList<>();
        for (Curriculum.RequirementGroupItem group : active) {
            String kind = group.getKind();
            int quota = group.getRequiredCredits();
            if (kind.startsWith("MANDATORY")) {
                choices.add(List.of(unique(group.getEligibleCourseCodes()).stream()
                    .filter(passed::contains).collect(Collectors.toList())));
            } else if ("GRADUATION".equals(kind)) {
                List<List<String>> alternatives = new ArrayList<>();
                alternatives.add(List.of());
                if (group.getCompletionOptions() != null) {
                    for (Curriculum.CompletionOptionItem option : group.getCompletionOptions()) {
                        List<String> codes = unique(option.getCourseCodes());
                        if (codes.isEmpty() || !passed.containsAll(codes) ||
                            codes.stream().anyMatch(c -> reservedMandatory.contains(c) || unavailableForOtherGroups.contains(c))) continue;
                        if (codes.stream().mapToInt(c -> credits(c, creditByCode)).sum() == quota)
                            alternatives.add(codes);
                    }
                }
                choices.add(alternatives);
            } else {
                List<String> eligible = unique(group.getEligibleCourseCodes()).stream()
                    .filter(c -> passed.contains(c) && !reservedMandatory.contains(c) && !unavailableForOtherGroups.contains(c))
                    .collect(Collectors.toList());
                choices.add(electiveOptions(eligible, creditByCode, quota));
            }
        }
        // Joint optimization prevents overlapping electives and graduation alternatives
        // from recognizing the same course in two groups.
        Allocation best = new Allocation();
        allocate(0, active, choices, new HashSet<>(), new ArrayList<>(), 0, creditByCode, best);
        if (best.selection == null) throw new IllegalStateException("Không tìm được phương án xét tín chỉ");
        List<Map<String,Object>> groupResults = new ArrayList<>();
        int requiredTotal = 0, recognizedTotal = 0;
        boolean allComplete = true;
        for (int i = 0; i < active.size(); i++) {
            Curriculum.RequirementGroupItem group = active.get(i);
            List<String> selected = best.selection.get(i);
            int quota = group.getRequiredCredits();
            int actualEarned = selected.stream().mapToInt(c -> credits(c, creditByCode)).sum();
            int earned = Math.min(quota, actualEarned);
            requiredTotal += quota;
            recognizedTotal += earned;
            boolean complete = actualEarned >= quota;
            allComplete &= complete;
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("groupCode", group.getGroupCode()); row.put("kind", group.getKind());
            row.put("requiredCredits", quota); row.put("recognizedCredits", earned);
            row.put("actualSelectedCredits", actualEarned); row.put("excessCredits", Math.max(0, actualEarned - quota));
            row.put("complete", complete); row.put("recognizedCourseCodes", selected);
            groupResults.add(row);
        }
        // GPA is calculated from actual graded attempts, not only the courses selected
        // for graduation credit. Failed electives and retakes remain in academic history.
        // Course-level exclusions take precedence over group-level defaults.
        Map<String, Boolean> gpaEligibility = new HashMap<>();
        Set<String> passFailCourses = new HashSet<>(passFailConfigured);
        for (Curriculum.RequirementGroupItem group : active) {
            boolean counts = !Boolean.FALSE.equals(group.getCountsTowardGpa());
            for (String code : unique(group.getEligibleCourseCodes()))
                gpaEligibility.merge(code, counts, (oldValue, newValue) -> oldValue && newValue);
            if (group.getCompletionOptions() != null)
                for (Curriculum.CompletionOptionItem option : group.getCompletionOptions())
                    for (String code : unique(option.getCourseCodes()))
                        gpaEligibility.merge(code, counts, (oldValue, newValue) -> oldValue && newValue);
        }
        for (Curriculum.SemesterItem semester : curriculum.getSemesters() == null
                ? List.<Curriculum.SemesterItem>of() : curriculum.getSemesters()) {
            if (semester == null || semester.getCourses() == null) continue;
            for (Curriculum.CurriculumCourseItem item : semester.getCourses()) {
                if (item == null || item.getCourseCode() == null) continue;
                if (item.getSpecializationCodes() != null && !item.getSpecializationCodes().isEmpty()
                        && !item.getSpecializationCodes().contains(specialization)) continue;
                if (Boolean.FALSE.equals(item.getCountsTowardGpa()))
                    gpaEligibility.put(item.getCourseCode(), false);
                if (item.getCompletionType() != null &&
                        ("PASS_FAIL".equalsIgnoreCase(item.getCompletionType()) ||
                         "DAT_KHONG_DAT".equalsIgnoreCase(item.getCompletionType()))) {
                    passFailCourses.add(item.getCourseCode());
                    gpaEligibility.put(item.getCourseCode(), false);
                }
            }
        }
        double weightedGradePoints = 0;
        int gpaCredits = 0;
        Set<String> gpaCodes = gpaEligibility.entrySet().stream()
            .filter(Map.Entry::getValue).map(Map.Entry::getKey).collect(Collectors.toSet());
        // Replacement grades are weighted by the original curriculum credits.
        // A replacement course must not be counted again as a standalone GPA course.
        gpaCodes.removeAll(claimedReplacementCourses);
        for (String code : gpaCodes) {
            Double score = bestScore.get(code);
            Double replacementGrade = replacementGrade4ForOld.get(code);
            if (score == null && replacementGrade == null) continue;
            if (score != null && score >= 4.0) replacementGrade = null;
            int credit = credits(code, creditByCode);
            weightedGradePoints += (replacementGrade == null
                ? gradeConversion.convert(score).score4() : replacementGrade) * credit;
            gpaCredits += credit;
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("curriculumId", curriculum.getId().toHexString());
        result.put("specializationCode", specialization);
        result.put("requiredCredits", requiredTotal);
        result.put("recognizedCredits", recognizedTotal);
        result.put("groupProgress", groupResults);
        result.put("requirementsComplete", allComplete);
        result.put("evaluationScope", "CURRICULUM_REQUIREMENT_GROUPS");
        result.put("replacementRecognitionIncluded", !replacementForOld.isEmpty());
        result.put("replacementRecognitionScope", "PUBLISHED_RULES_AND_PERSISTED_EVIDENCE");
        result.put("historicalEligibilityUnverifiedRuleIds", historyVerificationRequired);
        result.put("recognizedReplacements", replacementForOld);
        result.put("recognizedReplacementRuleIds", recognitionEvidence.stream()
            .map(e -> e.get("ruleId")).toList());
        result.put("replacementRecognitionEvidencePreview", recognitionEvidence);
        result.put("historicalRecognitionSnapshotsPersisted", persistEvidence && historyVerificationRequired.isEmpty());
        result.put("unsupportedComplexRuleIds", skippedComplexRules);
        result.put("gpaIncluded", true);
        result.put("gpaIncludesFailedAttempts", true);
        result.put("gpaExcludedPassFailCourseCodes", new TreeSet<>(passFailCourses));
        result.put("missingHistoricalEvidenceCount", historyVerificationRequired.size());
        // A configured pass/fail course with an explicit PASSED status is complete;
        // only courses without verifiable completion must block official graduation.
        Set<String> unresolvedPassFail = new TreeSet<>();
        for (Curriculum.RequirementGroupItem group : active) {
            if (!group.getKind().startsWith("MANDATORY")) continue;
            for (String code : unique(group.getEligibleCourseCodes()))
                if (passFailCourses.contains(code) && !passed.contains(code))
                    unresolvedPassFail.add(code);
        }
        result.put("officialGraduationEligible", persistEvidence && allComplete
            && historyVerificationRequired.isEmpty() && skippedComplexRules.isEmpty()
            && unresolvedPassFail.isEmpty());
        result.put("passFailVerificationRequired", !unresolvedPassFail.isEmpty());
        result.put("unverifiedPassFailCourseCodes", unresolvedPassFail);
        result.put("historicalEvidenceIssues", historyVerificationRequired);
        result.put("recognizedPassedCoursesGpaPreview", gpaCredits == 0 ? null :
            Math.round((weightedGradePoints / gpaCredits) * 100.0) / 100.0);
        result.put("note", persistEvidence ? "Kết quả xét chính thức theo nhóm và bằng chứng đã lưu." : "Xem thử; không ghi dữ liệu công nhận.");
        result.put("cumulativeGpa4", gpaCredits == 0 ? null : Math.round(weightedGradePoints / gpaCredits * 100.0) / 100.0);
        result.put("gpaCredits", gpaCredits);
        Map<String, double[]> semesterTotals = new TreeMap<>();
        for (AcademicRecord record : studentRecords) {
            // Semester GPA is based on the *actual enrolled course* and every
            // graded attempt, not on credits of the original course it replaces.
            // Original-curriculum credit weighting is used only for cumulative GPA.
            String actualCourseCode = record.getCourseCode();
            if (actualCourseCode == null || record.getAttempts() == null) continue;
            int actualCourseCredits = creditByCode.getOrDefault(actualCourseCode, 0);
            if (actualCourseCredits <= 0) continue;
            for (AcademicRecord.AttemptItem attempt : record.getAttempts()) {
                if (attempt.getScore10() == null || attempt.getSemesterCode() == null ||
                    !Double.isFinite(attempt.getScore10()) || attempt.getScore10() < 0 || attempt.getScore10() > 10 || !(gpaCodes.contains(record.getCourseCode())
                        || (claimedReplacementCourses.contains(record.getCourseCode())
                            && !passFailCourses.contains(record.getCourseCode())))) continue;
                double[] totals = semesterTotals.computeIfAbsent(attempt.getSemesterCode(), k -> new double[2]);
                totals[0] += gradeConversion.convert(attempt.getScore10()).score4() * actualCourseCredits;
                totals[1] += actualCourseCredits;
            }
        }
        Map<String, Double> semesterGpa = new TreeMap<>();
        semesterTotals.forEach((semester, totals) -> semesterGpa.put(semester,
            totals[1] == 0 ? null : Math.round(totals[0] / totals[1] * 100.0) / 100.0));
        result.put("semesterGpa4", semesterGpa);
        return result;
    }

    private static int credits(String code, Map<String,Integer> catalog) {
        Integer value = catalog.get(code);
        if (value == null) throw new IllegalStateException("Không tìm thấy tín chỉ môn: " + code);
        return value;
    }
    private static List<String> unique(List<String> codes) {
        return codes == null ? List.of() : codes.stream().filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());
    }
    /** All feasible subsets (including empty), sorted for reproducible tie-breaking. */
    private static List<List<String>> electiveOptions(List<String> eligible, Map<String,Integer> catalog, int quota) {
        List<List<String>> options = new ArrayList<>();
        enumerateElectives(eligible, 0, quota, 0, catalog, new ArrayList<>(), options);
        options.sort(Comparator.comparing(list -> String.join("|", list)));
        return options;
    }

    private static void enumerateElectives(List<String> codes, int index, int quota, int sum,
        Map<String,Integer> catalog, List<String> current, List<List<String>> output) {
        if (index == codes.size()) { output.add(new ArrayList<>(current)); return; }
        enumerateElectives(codes, index + 1, quota, sum, catalog, current, output);
        String code = codes.get(index);
        int credit = credits(code, catalog);
        if (sum < quota) {
            current.add(code);
            enumerateElectives(codes, index + 1, quota, sum + credit, catalog, current, output);
            current.remove(current.size() - 1);
        }
    }

    private static class Allocation {
        int credits = -1;
        int excess = Integer.MAX_VALUE;
        String key;
        List<List<String>> selection;
    }

    private static void allocate(int index, List<Curriculum.RequirementGroupItem> groups,
        List<List<List<String>>> choices, Set<String> used, List<List<String>> chosen,
        int total, Map<String,Integer> catalog, Allocation best) {
        if (index == groups.size()) {
            String key = chosen.stream().map(c -> String.join("|", c)).collect(Collectors.joining(";"));
            int capped = 0;
            int excess = 0;
            for (int i = 0; i < groups.size(); i++) {
                int raw = chosen.get(i).stream().mapToInt(c -> credits(c, catalog)).sum();
                int quota = groups.get(i).getRequiredCredits();
                capped += Math.min(quota, raw);
                excess += Math.max(0, raw - quota);
            }
            if (capped > best.credits || (capped == best.credits &&
                (excess < best.excess || (excess == best.excess && (best.key == null || key.compareTo(best.key) < 0))))) {
                best.credits = capped;
                best.excess = excess;
                best.key = key;
                best.selection = chosen.stream().map(ArrayList::new).collect(Collectors.toList());
            }
            return;
        }
        for (List<String> option : choices.get(index)) {
            if (option.stream().anyMatch(used::contains)) continue;
            int credits = option.stream().mapToInt(c -> credits(c, catalog)).sum();
            used.addAll(option);
            chosen.add(option);
            allocate(index + 1, groups, choices, used, chosen, total + credits, catalog, best);
            chosen.remove(chosen.size() - 1);
            used.removeAll(option);
        }
    }
}
