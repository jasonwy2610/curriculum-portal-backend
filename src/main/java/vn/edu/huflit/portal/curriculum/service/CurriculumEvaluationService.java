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
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;
import org.springframework.dao.DuplicateKeyException;
import java.time.Instant;
import vn.edu.huflit.portal.academic.service.GradeConversionService;

import java.util.*;
import java.util.stream.Collectors;

/** Evaluates curriculum requirements, grades and replacement evidence. */
@Service
@RequiredArgsConstructor
public class CurriculumEvaluationService {
    private final CurriculumRepository curricula;
    private final CourseRepository courses;
    private final AcademicRecordRepository records;
    private final ReplacementApplicability applicability;
    private final GradeConversionService gradeConversion;
    private final RecognitionEvidenceRepository evidenceRepository;

    /** Read-only evaluation; never writes evidence. */
    public Map<String, Object> evaluate(User student) { return evaluateInternal(student, false); }

    /** Official evaluation records newly verified replacement entitlements. */
    public Map<String, Object> evaluateOfficial(User student) {
        try {
            return evaluateInternal(student, true);
        } catch (DuplicateKeyException concurrentRecognition) {
            // Reload the winning snapshot before calculating credits and GPA.
            return evaluateInternal(student, true);
        }
    }

    private Map<String, Object> evaluateInternal(User student, boolean persistEvidence) {
        if (student == null || student.getCurriculumId() == null)
            throw new IllegalArgumentException("Sinh viên chưa được gán CTĐT");
        Curriculum storedCurriculum = curricula.findById(student.getCurriculumId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy CTĐT"));
        Curriculum curriculum = CurriculumTracks.forStudent(storedCurriculum, student);
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
        Map<String, List<AcademicRecord.AttemptItem>> passingAttempts = new HashMap<>();
        Instant asOf = Instant.now();
        for (AcademicRecord record : studentRecords) {
            if (record.getCourseCode() != null && "PASSED".equalsIgnoreCase(record.getStatus()))
                nonNumericPassed.add(record.getCourseCode());
            if (record.getCourseCode() == null || record.getAttempts() == null) continue;
            for (AcademicRecord.AttemptItem attempt : record.getAttempts()) {
                if (attempt.getScore10() == null) continue;
                if (!Double.isFinite(attempt.getScore10()) || attempt.getScore10() < 0 || attempt.getScore10() > 10) continue;
                String code = record.getCourseCode();
                bestScore.merge(code, attempt.getScore10(), Math::max);
                if (attempt.getScore10() >= 4.0) {
                    passingAttempts.computeIfAbsent(code, ignored -> new ArrayList<>()).add(attempt);
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
        if (curriculum.getRequirementGroups().stream()
            .filter(g -> g.getKind() != null && g.getKind().startsWith("ELECTIVE")).count() > 1)
            throw new IllegalStateException("Mỗi cây chỉ được có một nhóm tự chọn chung");
        Map<String, Instant> firstPassedAt = new HashMap<>();
        Set<String> uncertainFirstPass = new HashSet<>();
        passingAttempts.forEach((code, attempts) -> {
            if (attempts.stream().anyMatch(a -> a.getCompletedAt() == null)) uncertainFirstPass.add(code);
            attempts.stream().map(AcademicRecord.AttemptItem::getCompletedAt).filter(Objects::nonNull)
                .map(Date::toInstant).filter(at -> !at.isAfter(asOf)).min(Instant::compareTo)
                .ifPresent(at -> firstPassedAt.put(code, at));
        });
        // Replacement recognition preview. Never infer a historical entitlement from
        // a rule whose effective date or scope cannot be established.
        Map<String, List<String>> replacementForOld = new TreeMap<>();
        Map<String, Double> replacementGrade4ForOld = new HashMap<>();
        Set<String> claimedReplacementCourses = new HashSet<>();
        Set<String> claimedAttemptIds = new HashSet<>();
        List<String> skippedComplexRules = new ArrayList<>();
        List<String> historyVerificationRequired = new ArrayList<>();
        List<String> invalidHistoricalEvidenceIds = new ArrayList<>();
        List<Map<String,Object>> recognitionEvidence = new ArrayList<>();
        List<Map<String,Object>> historicalRecognitionEvidence = new ArrayList<>();
        List<RecognitionEvidence> historicalEvidence = evidenceRepository.findByStudentIdAndCurriculumId(
            student.getId(), curriculum.getId());
        // Historical entitlements are append-only, but malformed or conflicting records
        // must never silently inflate graduation progress. Validate the whole mapping
        // before consuming any of its courses (particularly for N-1 rules).
        Set<String> historicalOriginals = new HashSet<>();
        Set<ObjectId> historicalRuleIds = new LinkedHashSet<>();
        Set<ObjectId> recognizedFamilies = new HashSet<>();
        List<ReplacementApplicability.Window> applicableWindows = applicability.windows(student, curriculum, asOf);
        for (RecognitionEvidence item : historicalEvidence.stream()
                .sorted(Comparator.comparing(RecognitionEvidence::getRecognizedAt, Comparator.nullsLast(Date::compareTo))
                    .thenComparing(RecognitionEvidence::getId, Comparator.nullsLast(ObjectId::compareTo))).toList()) {
            if (historicalRuleIds.contains(item.getRuleId())) continue;
            ObjectId family = item.getRuleFamilyId() == null ? item.getRuleId() : item.getRuleFamilyId();
            List<String> olds = unique(item.getOriginalCourseCodes());
            List<String> news = unique(item.getReplacementCourseCodes());
            String evidenceId = item.getId() == null ? "unknown" : item.getId().toHexString();
            if (olds.isEmpty() || news.isEmpty() || item.getRuleId() == null
                || item.getGrade4() == null
                || item.getAttemptIds() == null || !item.getAttemptIds().keySet().containsAll(news)
                || news.stream().anyMatch(c -> item.getAttemptIds().get(c) == null
                    || item.getAttemptIds().get(c).isBlank())) {
                historyVerificationRequired.add("EVIDENCE:" + evidenceId);
                continue;
            }
            Set<String> evidenceAttemptIds = news.stream().map(item.getAttemptIds()::get).collect(Collectors.toSet());
            if (!Double.isFinite(item.getGrade4()) || item.getGrade4() < 0 || item.getGrade4() > 4
                || evidenceAttemptIds.size() != news.size() || !Collections.disjoint(evidenceAttemptIds, claimedAttemptIds)
                || olds.stream().anyMatch(news::contains)
                || olds.stream().anyMatch(historicalOriginals::contains)
                || news.stream().anyMatch(claimedReplacementCourses::contains)) {
                invalidHistoricalEvidenceIds.add(evidenceId);
                continue;
            }
            historicalRuleIds.add(item.getRuleId());
            recognizedFamilies.add(family);
            historicalOriginals.addAll(olds);
            claimedReplacementCourses.addAll(news);
            claimedAttemptIds.addAll(evidenceAttemptIds);
            Map<String,Object> snapshot = new LinkedHashMap<>();
            snapshot.put("ruleId", item.getRuleId().toHexString());
            snapshot.put("oldCourseCodes", olds); snapshot.put("newCourseCodes", news);
            snapshot.put("attemptIds", item.getAttemptIds()); snapshot.put("grade4", item.getGrade4());
            snapshot.put("versionNumber", item.getVersionNumber()); snapshot.put("recognizedAt", item.getRecognizedAt());
            historicalRecognitionEvidence.add(snapshot);
            for (String old : olds) {
                // Direct completion always wins, but evidence remains unchanged.
                if (bestScore.getOrDefault(old, -1.0) >= 4.0 || nonNumericPassed.contains(old)) continue;
                replacementForOld.put(old, news);
                replacementGrade4ForOld.put(old, item.getGrade4());
                // The stored strongest attempt does not establish the first eligible pass.
                // Reconstruct chronology only when the historical window is available.
                applicableWindows.stream().filter(w -> w.rule().getId().equals(item.getRuleId()))
                    .findFirst().ifPresent(w -> {
                        Instant first = replacementFirstPass(news, passingAttempts, w, asOf);
                        if (first != null) firstPassedAt.put(old, first);
                    });
            }
        }
        Map<ObjectId, List<ReplacementApplicability.Window>> versionsByFamily = new LinkedHashMap<>();
        for (var window : applicableWindows) {
            ObjectId family = ReplacementApplicability.family(window.rule());
            if (!recognizedFamilies.contains(family))
                versionsByFamily.computeIfAbsent(family, ignored -> new ArrayList<>()).add(window);
        }
        Comparator<AcademicRecord.AttemptItem> strongestEvidence = Comparator
            .comparing(AcademicRecord.AttemptItem::getScore10)
            .thenComparing(AcademicRecord.AttemptItem::getCompletedAt)
            .thenComparing(AcademicRecord.AttemptItem::getAttemptId);
        Set<ObjectId> recognizedRuleIds = new LinkedHashSet<>(historicalRuleIds);
        for (var familyVersions : versionsByFamily.entrySet()) {
            List<String> familyUnverified = new ArrayList<>();
            boolean familyRecognized = false;
            for (var window : familyVersions.getValue()) {
                ReplacementRule rule = window.rule();
                List<String> olds = unique(rule.getOldCourseCodes());
                List<String> news = unique(rule.getNewCourseCodes());
                String ruleId = rule.getId().toHexString();
                boolean shapeValid = ("1-1".equals(rule.getRuleType()) && olds.size() == 1 && news.size() == 1)
                    || ("1-N".equals(rule.getRuleType()) && olds.size() == 1 && news.size() > 1)
                    || ("N-1".equals(rule.getRuleType()) && olds.size() > 1 && news.size() == 1);
                if (!shapeValid || olds.stream().anyMatch(news::contains)
                    || olds.stream().anyMatch(c -> !creditByCode.containsKey(c))
                    || news.stream().anyMatch(c -> !creditByCode.containsKey(c))) {
                    skippedComplexRules.add(ruleId);
                    continue;
                }
                if (olds.stream().anyMatch(c -> bestScore.getOrDefault(c, -1.0) >= 4.0)
                    || olds.stream().anyMatch(nonNumericPassed::contains)
                    || olds.stream().anyMatch(historicalOriginals::contains)
                    || olds.stream().anyMatch(replacementForOld::containsKey)
                    || news.stream().anyMatch(claimedReplacementCourses::contains)
                    || news.stream().anyMatch(c -> !passingAttempts.containsKey(c))) continue;

                Map<String, AcademicRecord.AttemptItem> evidenceAttempts = new LinkedHashMap<>();
                for (String code : news) {
                    passingAttempts.get(code).stream()
                        .filter(attempt -> attempt.getAttemptId() != null && !attempt.getAttemptId().isBlank()
                            && !claimedAttemptIds.contains(attempt.getAttemptId())
                            && attempt.getCompletedAt() != null
                            && !attempt.getCompletedAt().toInstant().isAfter(asOf)
                            && window.verifies(attempt.getCompletedAt().toInstant()))
                        .max(strongestEvidence).ifPresent(attempt -> evidenceAttempts.put(code, attempt));
                }
                if (evidenceAttempts.size() != news.size()) {
                    // Verify only if the missing data could make this entire rule applicable.
                    boolean couldBeVerified = news.stream().filter(code -> !evidenceAttempts.containsKey(code))
                        .allMatch(code -> passingAttempts.get(code).stream()
                            .anyMatch(attempt -> needsHistoricalData(attempt, window, asOf)));
                    if (couldBeVerified) familyUnverified.add(ruleId);
                    continue;
                }
                if (evidenceAttempts.values().stream().map(AcademicRecord.AttemptItem::getAttemptId)
                    .distinct().count() != news.size()) {
                    skippedComplexRules.add(ruleId);
                    continue;
                }

                Map<String, String> attemptIds = new LinkedHashMap<>();
                Map<String, Date> completedAt = new LinkedHashMap<>();
                evidenceAttempts.forEach((code, attempt) -> {
                    attemptIds.put(code, attempt.getAttemptId());
                    completedAt.put(code, attempt.getCompletedAt());
                });
                // Keep highest-score GPA independent of the attempt proving eligibility.
                double points = news.stream().mapToDouble(c ->
                    gradeConversion.convert(bestScore.get(c)).score4() * creditByCode.get(c)).sum();
                int totalCredits = news.stream().mapToInt(creditByCode::get).sum();
                double representativeGrade4 = points / totalCredits;
                if (persistEvidence) {
                    evidenceRepository.insert(RecognitionEvidence.builder()
                        .id(RecognitionEvidence.stableId(student.getId(), curriculum.getId(), familyVersions.getKey()))
                        .studentId(student.getId()).curriculumId(curriculum.getId())
                        .ruleId(rule.getId()).ruleFamilyId(familyVersions.getKey())
                        .versionNumber(rule.getVersionNumber()).originalCourseCodes(new ArrayList<>(olds))
                        .replacementCourseCodes(new ArrayList<>(news)).attemptIds(attemptIds)
                        .grade4(representativeGrade4).recognizedAt(Date.from(asOf)).build());
                }

                Map<String,Object> evidence = new LinkedHashMap<>();
                evidence.put("ruleId", ruleId);
                evidence.put("ruleFamilyId", familyVersions.getKey().toHexString());
                evidence.put("versionNumber", rule.getVersionNumber());
                evidence.put("oldCourseCodes", olds);
                evidence.put("newCourseCodes", news);
                evidence.put("effectiveDate", rule.getEffectiveDate());
                evidence.put("publishedAt", rule.getPublishedAt());
                evidence.put("attemptIds", attemptIds);
                evidence.put("completedAt", completedAt);
                recognitionEvidence.add(evidence);
                recognizedRuleIds.add(rule.getId());
                Instant firstReplacementPass = replacementFirstPass(news, passingAttempts, window, asOf);
                for (String old : olds) {
                    replacementForOld.put(old, news);
                    replacementGrade4ForOld.put(old, representativeGrade4);
                    if (firstReplacementPass != null) firstPassedAt.put(old, firstReplacementPass);
                }
                claimedReplacementCourses.addAll(news);
                claimedAttemptIds.addAll(attemptIds.values());
                familyRecognized = true;
                break;
            }
            if (!familyRecognized) historyVerificationRequired.addAll(familyUnverified);
        }
        Set<String> passed = bestScore.entrySet().stream().filter(e -> e.getValue() >= 4.0)
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        // Non-numeric pass status is only valid for courses explicitly configured
        // as PASS_FAIL; it is added after collecting curriculum metadata below.
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
        // A directly passed mandatory course has first claim on its own credits.
        // Exclude the whole mapping when any of its sources is reserved this way.
        Set<String> directlyPassedMandatory = new HashSet<>(reservedMandatory);
        directlyPassedMandatory.retainAll(passed);
        Map<String, List<String>> graduationReplacements = new HashMap<>();
        replacementForOld.forEach((old, sources) -> {
            if (Collections.disjoint(sources, directlyPassedMandatory))
                graduationReplacements.put(old, sources);
        });
        passed.addAll(graduationReplacements.keySet());
        Set<String> mandatoryFootprint = new HashSet<>(reservedMandatory);
        reservedMandatory.forEach(code -> mandatoryFootprint.addAll(graduationReplacements.getOrDefault(code, List.of())));
        List<String> electiveHistoryIssues = new ArrayList<>();
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
                            codes.stream().anyMatch(reservedMandatory::contains)) continue;
                        if (codes.stream().mapToInt(c -> credits(c, creditByCode)).sum() == quota)
                            alternatives.add(codes);
                    }
                }
                choices.add(alternatives);
            } else {
                List<String> eligible = unique(group.getEligibleCourseCodes()).stream()
                    .filter(c -> passed.contains(c) && !mandatoryFootprint.contains(c)
                        && Collections.disjoint(graduationReplacements.getOrDefault(c, List.of()), mandatoryFootprint))
                    .collect(Collectors.toList());
                List<String> unknown = eligible.stream().filter(c -> !firstPassedAt.containsKey(c)
                    || uncertainFirstPass.contains(c)).toList();
                boolean needsOrder = eligible.stream().mapToInt(c -> credits(c, creditByCode)).sum() > quota;
                if (needsOrder) electiveHistoryIssues.addAll(unknown);
                // Dates only decide membership when surplus credit requires a cutoff.
                // Never invent that cutoff, or block a group with no surplus.
                choices.add(List.of(!needsOrder || unknown.isEmpty()
                    ? electivePrefix(eligible, creditByCode, quota, firstPassedAt, graduationReplacements) : List.of()));
            }
        }
        // Allocate original course codes and the actual courses backing them together.
        Allocation best = new Allocation();
        allocate(0, active, choices, new HashSet<>(), new ArrayList<>(), creditByCode, graduationReplacements, best);
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
        result.put("recognizedReplacementRuleIds", recognizedRuleIds.stream().map(ObjectId::toHexString).toList());
        result.put("replacementRecognitionEvidencePreview", recognitionEvidence);
        result.put("historicalRecognitionEvidence", historicalRecognitionEvidence);
        result.put("historicalRecognitionSnapshotsPersisted", persistEvidence && historyVerificationRequired.isEmpty()
            && invalidHistoricalEvidenceIds.isEmpty());
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
        result.put("officialGraduationEligible", persistEvidence && allComplete);
        result.put("passFailVerificationRequired", !unresolvedPassFail.isEmpty());
        result.put("unverifiedPassFailCourseCodes", unresolvedPassFail);
        result.put("historicalEvidenceIssues", historyVerificationRequired);
        result.put("electiveHistoryVerificationRequiredCourseCodes", electiveHistoryIssues);
        result.put("invalidHistoricalEvidenceIds", invalidHistoricalEvidenceIds);
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
        Map<String,Integer> semesterGpaCredits = new TreeMap<>();
        semesterTotals.forEach((semester, totals) -> semesterGpaCredits.put(semester, (int)totals[1]));
        result.put("semesterGpaCredits", semesterGpaCredits);
        return result;
    }

    private static boolean needsHistoricalData(AcademicRecord.AttemptItem attempt,
        ReplacementApplicability.Window window, Instant asOf) {
        Date completedAt = attempt.getCompletedAt();
        if (completedAt == null) return true;
        Instant completion = completedAt.toInstant();
        if (completion.isAfter(asOf) || !window.contains(completion)) return false;
        return window.rule().getPublishedAt() == null
            || attempt.getAttemptId() == null || attempt.getAttemptId().isBlank();
    }

    private static int credits(String code, Map<String,Integer> catalog) {
        Integer value = catalog.get(code);
        if (value == null) throw new IllegalStateException("Không tìm thấy tín chỉ môn: " + code);
        return value;
    }
    private static List<String> unique(List<String> codes) {
        return codes == null ? List.of() : codes.stream().filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());
    }
    private static List<String> electivePrefix(List<String> eligible, Map<String,Integer> catalog,
        int quota, Map<String,Instant> firstPassedAt, Map<String,List<String>> replacements) {
        List<String> selected = new ArrayList<>();
        int sum = 0;
        for (String code : eligible.stream().sorted(Comparator
                .comparing((String c) -> firstPassedAt.get(c), Comparator.nullsLast(Instant::compareTo))
                .thenComparing(Comparator.naturalOrder())).toList()) {
            if (sum >= quota) break;
            // A direct course and an alias backed by that course are alternatives.
            // Explicit N-1 originals can still be recognized together by their rule.
            if (selected.stream().anyMatch(previous -> replacements.getOrDefault(previous, List.of()).contains(code)
                || replacements.getOrDefault(code, List.of()).contains(previous))) continue;
            selected.add(code);
            sum += credits(code, catalog);
        }
        return selected;
    }

    private static Instant replacementFirstPass(List<String> sources,
        Map<String,List<AcademicRecord.AttemptItem>> attempts, ReplacementApplicability.Window window, Instant asOf) {
        Instant completed = null;
        for (String source : sources) {
            List<AcademicRecord.AttemptItem> passes = attempts.getOrDefault(source, List.of());
            if (passes.stream().anyMatch(a -> a.getCompletedAt() == null)) return null;
            Instant first = passes.stream().map(AcademicRecord.AttemptItem::getCompletedAt).filter(Objects::nonNull)
                .map(Date::toInstant).filter(at -> !at.isAfter(asOf) && window.verifies(at))
                .min(Instant::compareTo).orElse(null);
            if (first == null) return null;
            if (completed == null || first.isAfter(completed)) completed = first;
        }
        return completed;
    }

    private static class Allocation {
        int credits = -1;
        int excess = Integer.MAX_VALUE;
        String key;
        List<List<String>> selection;
    }

    private static void allocate(int index, List<Curriculum.RequirementGroupItem> groups,
        List<List<List<String>>> choices, Set<String> used, List<List<String>> chosen,
        Map<String,Integer> catalog, Map<String,List<String>> replacements, Allocation best) {
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
            used.addAll(option);
            // Each original in an explicit N-1 mapping may earn its own credits.
            // Its source cannot also be selected as a directly completed course.
            boolean reusesSource = used.stream().anyMatch(code ->
                replacements.getOrDefault(code, List.of()).stream()
                    .anyMatch(source -> used.contains(source) && !replacements.containsKey(source)));
            if (!reusesSource) {
                chosen.add(option);
                allocate(index + 1, groups, choices, used, chosen, catalog, replacements, best);
                chosen.remove(chosen.size() - 1);
            }
            used.removeAll(option);
        }
    }
}
