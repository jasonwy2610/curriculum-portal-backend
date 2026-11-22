package vn.edu.huflit.portal.curriculum.service;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.impl.GradeConversionServiceImpl;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.entity.AcademicRecord;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CurriculumEvaluationServiceTest {
    private static final Instant START = Instant.parse("2024-03-01T00:00:00Z");
    private static final Instant REVISION = Instant.parse("2024-07-01T00:00:00Z");
    private final CurriculumRepository curricula = mock(CurriculumRepository.class);
    private final CourseRepository catalog = mock(CourseRepository.class);
    private final AcademicRecordRepository records = mock(AcademicRecordRepository.class);
    private final ReplacementRuleRepository rules = mock(ReplacementRuleRepository.class);
    private final RecognitionEvidenceRepository evidence = mock(RecognitionEvidenceRepository.class);
    private final ReplacementApplicability applicability = new ReplacementApplicability(rules);
    private final CurriculumEvaluationService service = new CurriculumEvaluationService(
        curricula, catalog, records, applicability, new GradeConversionServiceImpl(), evidence);
    private final User student = User.builder().id(new ObjectId()).curriculumId(new ObjectId())
        .majorId(new ObjectId()).cohort("K2024").build();

    @Test
    void evaluatesOnlyTheAssignedSpecializationAndDoesNotLookUpNewerCurricula() {
        Curriculum curriculum = setup(List.of("OLD"));
        curriculum.setSpecializations(List.of(new Curriculum.SpecializationItem("AA", "A"),
            new Curriculum.SpecializationItem("BB", "B")));
        curriculum.setSpecializationTrees(Map.of(
            "AA", new Curriculum.SpecializationTree(List.of(),
                List.of(Curriculum.RequirementGroupItem.builder().groupCode("A").kind("MANDATORY_COMMON")
                    .requiredCredits(3).eligibleCourseCodes(List.of("OLD")).build())),
            "BB", new Curriculum.SpecializationTree(List.of(),
                List.of(Curriculum.RequirementGroupItem.builder().groupCode("B").kind("MANDATORY_COMMON")
                    .requiredCredits(3).eligibleCourseCodes(List.of("OTHER")).build()))));
        student.setChosenSpecializationCode("AA");
        attempts(record("OLD", attempt("a", 7, START)), record("OTHER", attempt("b", 10, START)));

        Map<String,Object> result = service.evaluateOfficial(student);
        assertEquals(3, result.get("requiredCredits"));
        assertEquals(3, result.get("recognizedCredits"));
        assertEquals(3, result.get("gpaCredits"));
        assertEquals(3.0, result.get("cumulativeGpa4"));
        verify(curricula).findById(student.getCurriculumId());
        verifyNoMoreInteractions(curricula);
        student.setChosenSpecializationCode("UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(student));
    }

    @Test
    void recognizesOneToOneAtEffectiveDate() {
        setup(List.of("OLD"), rule("1-1", List.of("OLD"), List.of("NEW")));
        attempts(record("NEW", attempt("pass", 7, START)));
        assertEquals(Map.of("OLD", List.of("NEW")), service.evaluate(student).get("recognizedReplacements"));
        verify(evidence).findByStudentIdAndCurriculumId(student.getId(), student.getCurriculumId());
        verifyNoMoreInteractions(evidence);
    }

    @Test
    void oneToManyRequiresAllReplacementCourses() {
        setup(List.of("OLD"), rule("1-N", List.of("OLD"), List.of("NEW", "ALT")));
        AcademicRecord first = record("NEW", attempt("a", 7, START));
        attempts(first);
        assertEquals(Map.of(), service.evaluate(student).get("recognizedReplacements"));
        attempts(first, record("ALT", attempt("b", 8, START)));
        assertEquals(Map.of("OLD", List.of("ALT", "NEW")), service.evaluate(student).get("recognizedReplacements"));
    }

    @Test
    void manyToOneRecognizesTheOriginalsTogether() {
        setup(List.of("OLD", "OTHER"), rule("N-1", List.of("OLD", "OTHER"), List.of("NEW")));
        attempts(record("NEW", attempt("pass", 7, START)));
        Map<String, Object> result = service.evaluate(student);
        assertEquals(Map.of("OLD", List.of("NEW"), "OTHER", List.of("NEW")), result.get("recognizedReplacements"));
        assertEquals(6, result.get("recognizedCredits"));
        attempts(record("NEW", attempt("pass", 7, START)), record("OLD", attempt("direct", 8, START)));
        assertEquals(Map.of(), service.evaluate(student).get("recognizedReplacements"));
    }

    @Test
    void completionMustFollowBothPublicationAndEffectiveDate() {
        ReplacementRule rule = rule("1-1", List.of("OLD"), List.of("NEW"));
        rule.setPublishedAt(Date.from(START.plusSeconds(60)));
        setup(List.of("OLD"), rule);
        attempts(record("NEW", attempt("before", 8, START.minusSeconds(1)), attempt("early", 7, START),
            attempt("unknown", 9, null)));
        assertEquals(Map.of(), service.evaluate(student).get("recognizedReplacements"));
        attempts(record("NEW", attempt("published", 7, START.plusSeconds(60))));
        assertEquals(Map.of("OLD", List.of("NEW")), service.evaluate(student).get("recognizedReplacements"));
    }

    @Test
    void retakesChooseHighestEligibleEvidenceButRetainHighestScoreForGpa() {
        setup(List.of("OLD"), rule("1-1", List.of("OLD"), List.of("NEW")));
        attempts(record("NEW", attempt("early-highest", 9, START.minusSeconds(1)),
            attempt("eligible", 6, START), attempt("eligible-higher", 7, START.plusSeconds(60))));
        Map<String, Object> result = service.evaluate(student);
        List<?> details = (List<?>) result.get("replacementRecognitionEvidencePreview");
        assertEquals(Map.of("NEW", "eligible-higher"), ((Map<?, ?>) details.getFirst()).get("attemptIds"));
        assertEquals(4.0, result.get("cumulativeGpa4"));
    }

    @Test
    void versionAtCompletionControlsRecognitionAndRemovedCohortsDoNotSeeOldVersion() {
        ReplacementRule first = rule("1-1", List.of("OLD"), List.of("NEW"));
        ReplacementRule second = rule("1-1", List.of("OLD"), List.of("ALT"));
        second.setRuleFamilyId(first.getId());
        second.setVersionNumber(2);
        second.setApplicableCohorts(List.of("K2025"));
        second.setEffectiveDate(Date.from(REVISION));
        Curriculum curriculum = setup(List.of("OLD"), first, second);
        assertEquals(List.of(first), applicability.current(student, curriculum, REVISION.minusSeconds(1)));
        assertEquals(List.of(), applicability.current(student, curriculum, REVISION));
        attempts(record("NEW", attempt("late", 9, REVISION)));
        assertEquals(Map.of(), service.evaluate(student).get("recognizedReplacements"));
        attempts(record("NEW", attempt("early", 7, REVISION.minusSeconds(1))));
        assertEquals(Map.of("OLD", List.of("NEW")), service.evaluate(student).get("recognizedReplacements"));
    }

    @Test
    void draftDoesNotCloseThePublishedVersionEvenAfterItsProposedEffectiveDate() {
        ReplacementRule first = rule("1-1", List.of("OLD"), List.of("NEW"));
        ReplacementRule draft = revision(first);
        draft.setLifecycleStatus("DRAFT");
        Curriculum curriculum = setup(List.of("OLD"), first, draft);

        assertEquals(List.of(first), applicability.current(student, curriculum, REVISION.plusSeconds(1)));
    }

    @Test
    void scheduledRevisionSwitchesExactlyAtItsEffectiveInstant() {
        ReplacementRule first = rule("1-1", List.of("OLD"), List.of("NEW"));
        ReplacementRule scheduled = revision(first);
        Curriculum curriculum = setup(List.of("OLD"), scheduled, first);

        assertEquals(List.of(first), applicability.current(student, curriculum, REVISION.minusMillis(1)));
        assertEquals(List.of(scheduled), applicability.current(student, curriculum, REVISION));
        var windows = applicability.windows(student, curriculum, REVISION);
        assertFalse(windows.getLast().verifies(REVISION));
        assertTrue(windows.getFirst().verifies(REVISION));
    }

    @Test
    void retirementBetweenRetakesUsesPreRetirementEvidenceAndKeepsHighestScoreForGpa() {
        ReplacementRule retired = rule("1-1", List.of("OLD"), List.of("NEW"));
        retired.setEndsAt(Date.from(REVISION));
        Curriculum curriculum = setup(List.of("OLD"), retired);
        assertEquals(List.of(retired), applicability.current(student, curriculum, REVISION.minusMillis(1)));
        assertEquals(List.of(), applicability.current(student, curriculum, REVISION));

        var atEnd = attempt("at-end", 8, REVISION);
        var afterEnd = attempt("after-end", 9, REVISION.plusMillis(1));
        attempts(record("NEW", atEnd, afterEnd));
        Map<String, Object> excluded = service.evaluate(student);
        assertEquals(Map.of(), excluded.get("recognizedReplacements"));
        assertEquals(List.of(), excluded.get("historicalEvidenceIssues"));

        attempts(record("NEW", attempt("before-end", 7, REVISION.minusMillis(1)), atEnd, afterEnd));
        Map<String, Object> recognized = service.evaluate(student);
        List<?> details = (List<?>) recognized.get("replacementRecognitionEvidencePreview");
        assertEquals(Map.of("NEW", "before-end"), ((Map<?, ?>) details.getFirst()).get("attemptIds"));
        assertEquals(3, recognized.get("recognizedCredits"));
        assertEquals(4.0, recognized.get("cumulativeGpa4"));
    }

    @Test
    void endingASuccessorDoesNotReviveEarlierVersions() {
        ReplacementRule first = rule("1-1", List.of("OLD"), List.of("NEW"));
        ReplacementRule successor = revision(first);
        Instant end = REVISION.plusSeconds(60);
        successor.setEndsAt(Date.from(end));
        Curriculum curriculum = setup(List.of("OLD"), first, successor);

        assertEquals(List.of(successor), applicability.current(student, curriculum, end.minusMillis(1)));
        assertEquals(List.of(), applicability.current(student, curriculum, end));
    }

    @Test
    void cancellingScheduledPublicationLeavesTheCurrentVersionInEffect() {
        ReplacementRule first = rule("1-1", List.of("OLD"), List.of("NEW"));
        ReplacementRule successor = revision(first);
        successor.setEndsAt(Date.from(REVISION.minusMillis(1)));
        Curriculum curriculum = setup(List.of("OLD"), first, successor);

        assertEquals(List.of(first), applicability.current(student, curriculum, REVISION.plusMillis(1)));
    }

    @Test
    void publishedInactiveSuccessorClosesThePreviousWindow() {
        ReplacementRule first = rule("1-1", List.of("OLD"), List.of("NEW"));
        ReplacementRule inactive = revision(first);
        inactive.setStatus("INACTIVE");
        Curriculum curriculum = setup(List.of("OLD"), first, inactive);

        assertEquals(List.of(), applicability.current(student, curriculum, REVISION));
        assertTrue(applicability.windows(student, curriculum, REVISION).getFirst().verifies(REVISION.minusMillis(1)));
        assertFalse(applicability.windows(student, curriculum, REVISION).getFirst().verifies(REVISION));
    }

    @Test
    void oneAttemptCannotCompleteTwoIndependentMandatoryRequirements() {
        Curriculum curriculum = setup(List.of("OLD"), rule("1-1", List.of("OLD"), List.of("NEW")));
        curriculum.setRequirementGroups(List.of(curriculum.getRequirementGroups().getFirst(),
            Curriculum.RequirementGroupItem.builder().groupCode("DIRECT").kind("MANDATORY_COMMON")
                .eligibleCourseCodes(List.of("NEW")).requiredCredits(3).build()));
        attempts(record("NEW", attempt("pass", 7, START)));

        Map<String, Object> result = service.evaluateOfficial(student);
        assertAll(
            () -> assertEquals(3, result.get("recognizedCredits")),
            () -> assertEquals(false, result.get("officialGraduationEligible")),
            () -> assertEquals(3, result.get("gpaCredits")),
            () -> assertEquals(3.0, result.get("cumulativeGpa4")));
    }

    @Test
    void knownOutOfWindowAttemptDoesNotBlockGraduationThroughAnotherCourse() {
        Curriculum curriculum = setup(List.of("OLD"), rule("1-1", List.of("OLD"), List.of("NEW")));
        curriculum.setRequirementGroups(List.of(
            Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                .eligibleCourseCodes(List.of("OLD", "ALT")).requiredCredits(3).build()));
        attempts(record("NEW", attempt("early", 7, START.minusMillis(1))),
            record("ALT", attempt("direct", 8, START)));

        Map<String, Object> result = service.evaluateOfficial(student);
        assertAll(
            () -> assertEquals(Map.of(), result.get("recognizedReplacements")),
            () -> assertEquals(List.of(), result.get("historicalEvidenceIssues")),
            () -> assertEquals(true, result.get("officialGraduationEligible")));
    }

    @Test
    void electiveAndGraduationOptionsCompeteForTheSameReplacementSource() {
        Curriculum curriculum = setup(List.of("OTHER"), rule("1-1", List.of("OLD"), List.of("NEW")));
        curriculum.setRequirementGroups(List.of(curriculum.getRequirementGroups().getFirst(),
            Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                .eligibleCourseCodes(List.of("NEW", "ALT")).requiredCredits(3).build(),
            Curriculum.RequirementGroupItem.builder().groupCode("FINAL").kind("GRADUATION")
                .completionOptions(List.of(Curriculum.CompletionOptionItem.builder()
                    .courseCodes(List.of("OLD")).build())).requiredCredits(3).build()));
        AcademicRecord mandatory = record("OTHER", attempt("core", 6, START));
        AcademicRecord replacement = record("NEW", attempt("replacement", 7, START));
        attempts(mandatory, replacement);
        assertEquals(6, service.evaluateOfficial(student).get("recognizedCredits"));

        attempts(mandatory, replacement, record("ALT", attempt("alternative", 8, START)));
        Map<String, Object> result = service.evaluateOfficial(student);
        assertEquals(9, result.get("recognizedCredits"));
        // A later elective must not replace the earlier prefix merely to free
        // its source for a graduation option.
        attempts(mandatory, replacement, record("ALT", attempt("later", 8, REVISION)));
        Map<String,Object> chronological = service.evaluateOfficial(student);
        assertEquals(6, chronological.get("recognizedCredits"));
        assertEquals(result.get("gpaCredits"), chronological.get("gpaCredits"));
        assertEquals(result.get("cumulativeGpa4"), chronological.get("cumulativeGpa4"));
        assertEquals(true, result.get("officialGraduationEligible"));
        assertEquals(9, result.get("gpaCredits"));
        assertEquals(3.0, result.get("cumulativeGpa4"));
    }

    @Test
    void oneToManyCannotUsePartOfItsEvidenceAlreadySpentOnMandatoryCredit() {
        Curriculum curriculum = setup(List.of("NEW"), rule("1-N", List.of("OLD"), List.of("NEW", "ALT")));
        curriculum.setRequirementGroups(List.of(curriculum.getRequirementGroups().getFirst(),
            Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                .eligibleCourseCodes(List.of("ALT")).requiredCredits(3).build(),
            Curriculum.RequirementGroupItem.builder().groupCode("FINAL").kind("GRADUATION")
                .completionOptions(List.of(Curriculum.CompletionOptionItem.builder()
                    .courseCodes(List.of("OLD")).build())).requiredCredits(3).build()));
        attempts(record("NEW", attempt("mandatory", 7, START)), record("ALT", attempt("elective", 8, START)));

        Map<String, Object> result = service.evaluateOfficial(student);
        assertEquals(6, result.get("recognizedCredits"));
        assertEquals(false, result.get("officialGraduationEligible"));
        assertEquals(3, result.get("gpaCredits"));
        assertEquals(3.25, result.get("cumulativeGpa4"));
    }

    @Test
    void manyToOneCannotAlsoCountItsSourceAsADirectMandatoryCourse() {
        Curriculum curriculum = setup(List.of("OLD", "OTHER"),
            rule("N-1", List.of("OLD", "OTHER"), List.of("NEW")));
        curriculum.setRequirementGroups(List.of(curriculum.getRequirementGroups().getFirst(),
            Curriculum.RequirementGroupItem.builder().groupCode("DIRECT").kind("MANDATORY_COMMON")
                .eligibleCourseCodes(List.of("NEW")).requiredCredits(3).build()));
        attempts(record("NEW", attempt("pass", 7, START)));

        Map<String, Object> result = service.evaluateOfficial(student);
        assertEquals(3, result.get("recognizedCredits"));
        assertEquals(6, result.get("gpaCredits"));
        assertEquals(false, result.get("officialGraduationEligible"));
    }

    @Test
    void genuinelyMissingDateIsFlaggedButDoesNotBlockAnIndependentCompletion() {
        ReplacementRule rule = rule("1-1", List.of("OLD"), List.of("NEW"));
        Curriculum curriculum = setup(List.of("OLD"), rule);
        curriculum.setRequirementGroups(List.of(
            Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                .eligibleCourseCodes(List.of("OLD", "ALT")).requiredCredits(3).build()));
        attempts(record("NEW", attempt("missing-date", 7, null)));
        Map<String, Object> incomplete = service.evaluateOfficial(student);
        assertEquals(List.of(rule.getId().toHexString()), incomplete.get("historicalEvidenceIssues"));
        assertEquals(false, incomplete.get("officialGraduationEligible"));

        attempts(record("NEW", attempt("missing-date", 7, null)), record("ALT", attempt("direct", 8, START)));
        Map<String, Object> complete = service.evaluateOfficial(student);
        assertEquals(List.of(rule.getId().toHexString()), complete.get("historicalEvidenceIssues"));
        assertEquals(true, complete.get("officialGraduationEligible"));
        verify(evidence, never()).insert(any(RecognitionEvidence.class));
    }

    @Test
    void replacementUsedByOneRuleDoesNotAlsoEarnElectiveCreditOrGpaCredit() {
        ReplacementRule first = rule("1-1", List.of("OLD"), List.of("NEW"));
        ReplacementRule other = rule("1-1", List.of("OTHER"), List.of("NEW"));
        Curriculum curriculum = setup(List.of("OLD", "OTHER"), first, other);
        curriculum.setRequirementGroups(List.of(curriculum.getRequirementGroups().getFirst(),
            Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                .eligibleCourseCodes(List.of("NEW")).requiredCredits(3).build()));
        attempts(record("NEW", attempt("pass", 7, START)));

        Map<String, Object> result = service.evaluate(student);
        assertEquals(0, ((Map<?, ?>) result.get("recognizedReplacements")).size());
        assertEquals(3, result.get("recognizedCredits"));
        assertEquals(3, result.get("gpaCredits"));
    }

    @Test
    void existingEvidenceSurvivesWhenItsRuleIsNoLongerAvailable() {
        setup(List.of("OLD"));
        ObjectId ruleId = new ObjectId();
        RecognitionEvidence saved = RecognitionEvidence.builder()
            .id(new ObjectId()).ruleId(ruleId).originalCourseCodes(List.of("OLD"))
            .replacementCourseCodes(List.of("NEW")).attemptIds(Map.of("NEW", "older-pass"))
            .grade4(2.5).build();
        when(evidence.findByStudentIdAndCurriculumId(student.getId(), student.getCurriculumId()))
            .thenReturn(List.of(saved));
        attempts();

        Map<String, Object> result = service.evaluateOfficial(student);
        assertEquals(Map.of("OLD", List.of("NEW")), result.get("recognizedReplacements"));
        assertEquals(List.of(ruleId.toHexString()), result.get("recognizedReplacementRuleIds"));
        assertEquals(2.5, result.get("cumulativeGpa4"));
        verify(evidence).findByStudentIdAndCurriculumId(student.getId(), student.getCurriculumId());
        verifyNoMoreInteractions(evidence);
    }

    @Test
    void reloadsConcurrentEvidenceAndKeepsLegacySnapshot() {
        ReplacementRule rule = rule("1-1", List.of("OLD"), List.of("NEW"));
        setup(List.of("OLD"), rule);
        attempts(record("NEW", attempt("pass", 7, START)));
        RecognitionEvidence saved = RecognitionEvidence.builder()
            .id(new ObjectId()).ruleId(rule.getId()).originalCourseCodes(List.of("OLD"))
            .replacementCourseCodes(List.of("NEW")).attemptIds(Map.of("NEW", "older-pass"))
            .grade4(2.5).build();
        when(evidence.findByStudentIdAndCurriculumId(student.getId(), student.getCurriculumId()))
            .thenReturn(List.of()).thenReturn(List.of(saved));
        when(evidence.insert(any(RecognitionEvidence.class))).thenThrow(new DuplicateKeyException("concurrent"));

        assertEquals(2.5, service.evaluateOfficial(student).get("cumulativeGpa4"));
        verify(evidence).insert(argThat((RecognitionEvidence candidate) -> candidate.getId().equals(
            RecognitionEvidence.stableId(student.getId(), student.getCurriculumId(), rule.getId()))));
        verify(evidence, never()).save(any(RecognitionEvidence.class));
    }

    @Test
    void electivesUseChronologicalPrefixInsteadOfTheSmallestExcessCombination() {
        electiveSetup(5);
        attempts(record("NEW", attempt("n", 7, START)),
            record("ALT", attempt("a", 8, START.plusSeconds(60))),
            record("OTHER", attempt("o", 10, REVISION)));
        Map<String,Object> result = service.evaluate(student);
        Map<?,?> group = ((List<Map<?,?>>) result.get("groupProgress")).getFirst();
        assertEquals(List.of("NEW", "ALT"), group.get("recognizedCourseCodes"));
        assertEquals(7, group.get("actualSelectedCredits"));
        assertEquals(5, result.get("recognizedCredits"));
        assertEquals(true, result.get("requirementsComplete"));
        assertEquals(9, result.get("gpaCredits"));
        assertEquals(3.39, result.get("cumulativeGpa4"));
    }

    @Test
    void electiveRetakesUseFirstPassForOrderAndHighestScoreForGpa() {
        electiveSetup(3);
        var failed = attempt("fail", 2, START.minusSeconds(60));
        var first = attempt("first", 5, START);
        var best = attempt("best", 10, REVISION);
        var alternative = attempt("alt", 7, START.plusSeconds(60));
        failed.setSemesterCode("2023-2"); first.setSemesterCode("2024-1");
        best.setSemesterCode("2024-2"); alternative.setSemesterCode("2024-1");
        attempts(record("NEW", failed, first, best), record("ALT", alternative));
        Map<String,Object> result = service.evaluate(student);
        assertEquals(List.of("NEW"), ((List<Map<?,?>>) result.get("groupProgress")).getFirst().get("recognizedCourseCodes"));
        assertEquals(3, result.get("recognizedCredits"));
        assertEquals(7, result.get("gpaCredits"));
        assertEquals(3.57, result.get("cumulativeGpa4"));
        assertEquals(Map.of("2023-2", 0.0, "2024-1", 2.14, "2024-2", 4.0), result.get("semesterGpa4"));
    }

    @Test
    void electiveTiesUseCodeAndAFailedElectiveCanBeCompensated() {
        electiveSetup(5);
        attempts(record("NEW", attempt("n", 3, START)), record("ALT", attempt("a", 7, START)),
            record("OTHER", attempt("o", 7, START)));
        Map<String,Object> result = service.evaluate(student);
        assertEquals(List.of("ALT", "OTHER"), ((List<Map<?,?>>) result.get("groupProgress")).getFirst().get("recognizedCourseCodes"));
        assertEquals(true, result.get("requirementsComplete"));
        assertEquals(5, result.get("recognizedCredits"));
        assertEquals(9, result.get("gpaCredits")); // Includes the failed elective in GPA.
    }

    @Test
    void electiveCannotSupplyMandatoryReplacementRegardlessOfRuleShape() {
        for (String type : List.of("1-1", "1-N", "N-1")) {
            List<String> olds = type.equals("N-1") ? List.of("OLD", "OTHER") : List.of("OLD");
            List<String> news = type.equals("1-N") ? List.of("NEW", "ALT") : List.of("NEW");
            Curriculum curriculum = setup(olds, rule(type, olds, news));
            curriculum.setRequirementGroups(List.of(curriculum.getRequirementGroups().getFirst(),
                Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                    .eligibleCourseCodes(List.of("NEW", "ALT")).requiredCredits(3).build()));
            attempts(record("NEW", attempt("n", 7, START)), record("ALT", attempt("a", 8, START)));
            Map<String,Object> result = service.evaluateOfficial(student);
            assertEquals(Map.of(), result.get("recognizedReplacements"), type);
            assertEquals(3, result.get("recognizedCredits"), type);
            assertEquals(false, result.get("requirementsComplete"), type);
            assertEquals(6, result.get("gpaCredits"), type);
        }
        verify(evidence, never()).insert(any(RecognitionEvidence.class));
    }

    @Test
    void partialElectiveCreditDoesNotRequireRepeatingFailedAlternatives() {
        electiveSetup(5);
        attempts(record("OTHER", attempt("two", 7, START)), record("NEW", attempt("fail", 0, START)));
        Map<String,Object> result = service.evaluate(student);
        assertEquals(2, result.get("recognizedCredits"));
        assertEquals(false, result.get("requirementsComplete"));
        assertEquals(List.of("OTHER"), ((List<Map<?,?>>) result.get("groupProgress")).getFirst().get("recognizedCourseCodes"));
    }

    @Test
    void tiedFirstPassingInstantsUseCourseCodeRatherThanScoreOrCredit() {
        electiveSetup(3);
        attempts(record("NEW", attempt("n", 10, START)), record("ALT", attempt("a", 4, START)));
        Map<String,Object> result = service.evaluate(student);
        assertEquals(List.of("ALT"), ((List<Map<?,?>>) result.get("groupProgress")).getFirst().get("recognizedCourseCodes"));
        assertEquals(3, result.get("recognizedCredits"));
    }

    @Test
    void missingFirstPassDateRequiresVerificationWithoutChangingGpaOrWritingHistory() {
        electiveSetup(3);
        attempts(record("NEW", attempt("unknown", 5, null), attempt("best", 10, REVISION)),
            record("ALT", attempt("known", 7, START)));
        Map<String,Object> result = service.evaluateOfficial(student);
        assertEquals(List.of("NEW"), result.get("electiveHistoryVerificationRequiredCourseCodes"));
        assertEquals(0, result.get("recognizedCredits"));
        assertEquals(7, result.get("gpaCredits"));
        assertEquals(3.57, result.get("cumulativeGpa4"));
        verify(records, never()).save(any(AcademicRecord.class));
    }

    @Test
    void existingMandatoryRecognitionIsRetainedAndItsElectiveSourceCannotEarnCreditAgain() {
        Curriculum curriculum = setup(List.of("OLD"));
        curriculum.setRequirementGroups(List.of(curriculum.getRequirementGroups().getFirst(),
            Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                .requiredCredits(3).eligibleCourseCodes(List.of("NEW", "ALT")).build()));
        RecognitionEvidence saved = RecognitionEvidence.builder().id(new ObjectId()).ruleId(new ObjectId())
            .originalCourseCodes(List.of("OLD")).replacementCourseCodes(List.of("NEW"))
            .attemptIds(Map.of("NEW", "older")).grade4(2.5).build();
        when(evidence.findByStudentIdAndCurriculumId(student.getId(), student.getCurriculumId())).thenReturn(List.of(saved));
        attempts(record("NEW", attempt("older", 7, START)));
        Map<String,Object> result = service.evaluateOfficial(student);
        assertEquals(Map.of("OLD", List.of("NEW")), result.get("recognizedReplacements"));
        assertEquals(3, result.get("recognizedCredits"));
        assertEquals(2.5, result.get("cumulativeGpa4"));
        verify(evidence, never()).save(any(RecognitionEvidence.class));
        verify(evidence, never()).insert(any(RecognitionEvidence.class));
        attempts(record("NEW", attempt("older", 7, START)), record("ALT", attempt("other", 8, REVISION)));
        assertEquals(6, service.evaluateOfficial(student).get("recognizedCredits"));
        assertEquals(2.5, saved.getGrade4());
    }

    @Test
    void electiveReplacementUsesFirstEligibleBundleCompletionNotHighestScoringAttempts() {
        Curriculum curriculum = setup(List.of("OLD"), rule("1-N", List.of("OLD"), List.of("NEW", "OTHER")));
        curriculum.setRequirementGroups(List.of(Curriculum.RequirementGroupItem.builder()
            .groupCode("FREE").kind("ELECTIVE_FREE").requiredCredits(3).eligibleCourseCodes(List.of("OLD", "ALT")).build()));
        attempts(record("NEW", attempt("before", 8, START.minusSeconds(1)),
                attempt("eligible", 5, START), attempt("best", 10, REVISION)),
            record("OTHER", attempt("bundle", 7, START.plusSeconds(60))),
            record("ALT", attempt("alt", 7, START.plusSeconds(120))));
        Map<String,Object> result = service.evaluateOfficial(student);
        assertEquals(List.of("OLD"), ((List<Map<?,?>>) result.get("groupProgress")).getFirst().get("recognizedCourseCodes"));
        assertEquals(3, result.get("recognizedCredits"));
        assertEquals(List.of(), result.get("electiveHistoryVerificationRequiredCourseCodes"));
        assertEquals(6, result.get("gpaCredits"));
        assertEquals(3.25, result.get("cumulativeGpa4"));
    }

    @Test
    void electiveAliasAndItsSourceAreNotBothCountedInsideTheSameGroup() {
        Curriculum curriculum = setup(List.of("OLD"), rule("1-1", List.of("OLD"), List.of("NEW")));
        curriculum.setRequirementGroups(List.of(Curriculum.RequirementGroupItem.builder()
            .groupCode("FREE").kind("ELECTIVE_FREE").requiredCredits(6)
            .eligibleCourseCodes(List.of("OLD", "NEW", "ALT")).build()));
        attempts(record("NEW", attempt("n", 7, START)), record("ALT", attempt("a", 8, REVISION)));
        Map<String,Object> result = service.evaluateOfficial(student);
        assertEquals(List.of("NEW", "ALT"), ((List<Map<?,?>>) result.get("groupProgress")).getFirst().get("recognizedCourseCodes"));
        assertEquals(6, result.get("recognizedCredits"));
        assertEquals(6, result.get("gpaCredits"));
    }

    @Test
    void missingDateDoesNotBlockAGroupWhenThereIsNoSurplusToOrder() {
        electiveSetup(2);
        attempts(record("OTHER", attempt("legacy", 7, null)));
        Map<String,Object> result = service.evaluateOfficial(student);
        assertEquals(2, result.get("recognizedCredits"));
        assertEquals(true, result.get("officialGraduationEligible"));
        assertEquals(List.of(), result.get("electiveHistoryVerificationRequiredCourseCodes"));
    }

    @Test
    void anExistingElectiveEntitlementDoesNotNeedItsDeletedRuleWhenNoSurplusExists() {
        Curriculum curriculum = setup(List.of("OLD"));
        curriculum.setRequirementGroups(List.of(Curriculum.RequirementGroupItem.builder()
            .groupCode("FREE").kind("ELECTIVE_FREE").requiredCredits(3).eligibleCourseCodes(List.of("OLD", "ALT")).build()));
        RecognitionEvidence saved = RecognitionEvidence.builder().id(new ObjectId()).ruleId(new ObjectId())
            .originalCourseCodes(List.of("OLD")).replacementCourseCodes(List.of("NEW"))
            .attemptIds(Map.of("NEW", "older")).grade4(2.5).build();
        when(evidence.findByStudentIdAndCurriculumId(student.getId(), student.getCurriculumId())).thenReturn(List.of(saved));
        attempts();
        Map<String,Object> result = service.evaluateOfficial(student);
        assertEquals(3, result.get("recognizedCredits"));
        assertEquals(2.5, result.get("cumulativeGpa4"));
        assertEquals(true, result.get("officialGraduationEligible"));
        verify(evidence, never()).save(any(RecognitionEvidence.class));
        verify(evidence, never()).insert(any(RecognitionEvidence.class));
    }

    private Curriculum electiveSetup(int quota) {
        Curriculum curriculum = setup(List.of("OLD"));
        curriculum.setRequirementGroups(List.of(Curriculum.RequirementGroupItem.builder()
            .groupCode("FREE").kind("ELECTIVE_FREE").requiredCredits(quota)
            .eligibleCourseCodes(List.of("NEW", "ALT", "OTHER")).build()));
        when(catalog.findAll()).thenReturn(List.of(Course.builder().courseCode("NEW").credits(4).build(),
            Course.builder().courseCode("ALT").credits(3).build(),
            Course.builder().courseCode("OTHER").credits(2).build()));
        return curriculum;
    }

    @Test void eitherGraduationOptionSatisfiesTheSameQuotaAndPassingBothDoesNotDoubleCredit() {
        var c = setup(List.of("OLD"));
        c.setRequirementGroups(List.of(c.getRequirementGroups().getFirst(),
            Curriculum.RequirementGroupItem.builder().groupCode("FINAL").kind("GRADUATION").requiredCredits(3)
                .completionOptions(List.of(new Curriculum.CompletionOptionItem("THESIS",List.of("NEW")),
                    new Curriculum.CompletionOptionItem("CAPSTONE",List.of("ALT")))).build()));
        var core = record("OLD",attempt("core",7,START));
        for (String option : List.of("NEW","ALT")) {
            attempts(core,record(option,attempt("option",8,START)));
            var result=service.evaluateOfficial(student);
            assertEquals(6,result.get("recognizedCredits"));assertEquals(true,result.get("officialGraduationEligible"));
        }
        attempts(core,record("NEW",attempt("thesis",8,START)),record("ALT",attempt("capstone",8,START)));
        var both=service.evaluate(student);
        assertEquals(6,both.get("recognizedCredits"));assertEquals(9,both.get("gpaCredits"));
        c.getRequirementGroups().getLast().setCompletionOptions(List.of(new Curriculum.CompletionOptionItem("THESIS",List.of("NEW")),
            new Curriculum.CompletionOptionItem("CAPSTONE",List.of("NEW"))));
        attempts(core,record("NEW",attempt("shared",8,START)));
        assertEquals(6,service.evaluate(student).get("recognizedCredits"));
    }

    private Curriculum setup(List<String> originals, ReplacementRule... versions) {
        Curriculum curriculum = Curriculum.builder().id(student.getCurriculumId())
            .majorId(student.getMajorId()).cohort(student.getCohort())
            .requirementGroups(List.of(Curriculum.RequirementGroupItem.builder()
                .groupCode("CORE").kind("MANDATORY_COMMON").eligibleCourseCodes(originals)
                .requiredCredits(originals.size() * 3).build())).build();
        when(curricula.findById(student.getCurriculumId())).thenReturn(Optional.of(curriculum));
        when(catalog.findAll()).thenReturn(List.of("OLD", "OTHER", "NEW", "ALT").stream()
            .map(code -> Course.builder().courseCode(code).credits(3).build()).toList());
        when(rules.findAll()).thenReturn(List.of(versions));
        return curriculum;
    }

    private ReplacementRule rule(String type, List<String> originals, List<String> replacements) {
        return ReplacementRule.builder().id(new ObjectId()).ruleType(type).status("ACTIVE")
            .lifecycleStatus("PUBLISHED").majorId(student.getMajorId()).applicableCohorts(List.of(student.getCohort()))
            .effectiveDate(Date.from(START)).publishedAt(Date.from(START.minusSeconds(60)))
            .oldCourseCodes(originals).newCourseCodes(replacements).build();
    }

    private ReplacementRule revision(ReplacementRule first) {
        ReplacementRule next = rule("1-1", first.getOldCourseCodes(), List.of("ALT"));
        next.setRuleFamilyId(first.getId());
        next.setVersionNumber(2);
        next.setEffectiveDate(Date.from(REVISION));
        return next;
    }

    private void attempts(AcademicRecord... studentRecords) {
        when(records.findByStudentId(student.getId())).thenReturn(List.of(studentRecords));
    }

    private AcademicRecord record(String code, AcademicRecord.AttemptItem... items) {
        return AcademicRecord.builder().courseCode(code).attempts(List.of(items)).build();
    }

    private AcademicRecord.AttemptItem attempt(String id, double score, Instant completedAt) {
        AcademicRecord.AttemptItem item = new AcademicRecord.AttemptItem();
        item.setAttemptId(id);
        item.setScore10(score);
        item.setCompletedAt(completedAt == null ? null : Date.from(completedAt));
        return item;
    }

}
