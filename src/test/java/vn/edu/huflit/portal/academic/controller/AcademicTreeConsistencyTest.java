package vn.edu.huflit.portal.academic.controller;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.AcademicRecordService;
import vn.edu.huflit.portal.academic.service.impl.GradeConversionServiceImpl;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.service.*;
import vn.edu.huflit.portal.dto.request.*;
import vn.edu.huflit.portal.entity.*;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;
import vn.edu.huflit.portal.repository.UserRepository;
import vn.edu.huflit.portal.studentcurriculum.controller.StudentCurriculumController;

import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicTreeConsistencyTest {
    private static final Instant START = Instant.parse("2024-06-01T00:00:00Z");
    private final AcademicRecordService academic = mock(AcademicRecordService.class);
    private final UserRepository students = mock(UserRepository.class);
    private final CurriculumRepository curricula = mock(CurriculumRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final ReplacementRuleRepository rules = mock(ReplacementRuleRepository.class);
    private final AcademicRecordRepository records = mock(AcademicRecordRepository.class);
    private final RecognitionEvidenceRepository evidence = mock(RecognitionEvidenceRepository.class);
    private final ReplacementApplicability applicability = new ReplacementApplicability(rules);
    private final AdminAcademicRecordController controller = new AdminAcademicRecordController(
        academic, students, curricula, courses, applicability);
    private final User student = User.builder().id(new ObjectId()).curriculumId(new ObjectId()).majorId(new ObjectId())
        .role("STUDENT").cohort("K2024").chosenSpecializationCode("AA").build();
    private final Curriculum curriculum = Curriculum.builder().id(student.getCurriculumId())
        .majorId(student.getMajorId()).cohort(student.getCohort()).status("ACTIVE")
        .specializations(List.of(new Curriculum.SpecializationItem("AA", "A"), new Curriculum.SpecializationItem("BB", "B")))
        .specializationTrees(Map.of("AA", tree("OLD"), "BB", tree("OTHER"))).build();

    AcademicTreeConsistencyTest() {
        when(students.findById(student.getId())).thenReturn(Optional.of(student));
        when(curricula.findById(curriculum.getId())).thenReturn(Optional.of(curriculum));
        when(courses.existsByCourseCode(anyString())).thenReturn(true);
        when(courses.findAll()).thenReturn(List.of("OLD", "OTHER", "EL", "NEW", "ALT").stream()
            .map(code -> Course.builder().courseCode(code).credits(3).build()).toList());
    }

    @Test void newResultsRequireAnAssignedCurriculum() {
        student.setCurriculumId(null);
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("OLD")));
        verifyNoInteractions(academic);
    }

    @Test void newResultsRejectACurriculumFromAnotherMajor() {
        curriculum.setMajorId(new ObjectId());
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("OLD")));
        verifyNoInteractions(academic);
    }

    @Test void newResultsRejectACurriculumFromAnotherCohort() {
        curriculum.setCohort("K2025");
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("OLD")));
        verifyNoInteractions(academic);
    }

    @Test void replacementApisDoNotOfferElectiveSourcesForMandatoryTargets() {
        when(rules.findAll()).thenReturn(List.of(rule("1-1", List.of("OLD"), List.of("EL"))));
        assertEquals(List.of(), applicability.current(student, curriculum, START));
    }

    @Test void outsideTreeEntryCannotUseABundleContainingAnElectiveForMandatoryRecognition() {
        when(rules.findAll()).thenReturn(List.of(rule("1-N", List.of("OLD"), List.of("EL", "NEW"))));
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("NEW")));
        verifyNoInteractions(academic);
    }

    @Test void malformedPublishedRuleCannotAuthorizeAnOutsideCourse() {
        when(rules.findAll()).thenReturn(List.of(rule("1-1", List.of(), List.of("NEW"))));
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("NEW")));
        verifyNoInteractions(academic);
    }

    @Test void evaluationRejectsMismatchedStudentIdentityBeforeWritingEvidence() {
        curriculum.setMajorId(new ObjectId());
        assertThrows(IllegalArgumentException.class, () -> evaluation().evaluateOfficial(student));
        verifyNoInteractions(evidence);
    }

    @Test void studentCurriculumRejectsMismatchedCohort() {
        curriculum.setCohort("K2025");
        var studentApi = new StudentCurriculumController(evaluation(), curricula, records, courses, applicability);
        assertThrows(IllegalArgumentException.class, () -> studentApi.getMyCurriculum(student));
        verifyNoInteractions(evidence);
    }

    @Test void ownTreeAcceptsResultsAndForeignTreeRequiresAnApplicableRule() {
        var own = request("OLD");
        controller.add(own);
        verify(academic).addAttempt(student.getId(), "OLD", curriculum.getId(), own.getAttempt());
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("OTHER")));
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("NEW")));
        when(rules.findAll()).thenReturn(List.of(rule("1-1", List.of("OTHER"), List.of("NEW"))));
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("NEW")));
    }

    @Test void allSupportedShapesAuthorizeOnlySourcesForTargetsInsideTheAssignedTree() {
        for (String type : List.of("1-1", "1-N", "N-1")) {
            clearInvocations(academic);
            var rule = rule(type, type.equals("N-1") ? List.of("OLD", "EL") : List.of("OLD"),
                type.equals("1-N") ? List.of("NEW", "ALT") : List.of("NEW"));
            when(rules.findAll()).thenReturn(List.of(rule));
            var input = request("NEW");
            controller.add(input);
            verify(academic).addAttempt(student.getId(), "NEW", curriculum.getId(), input.getAttempt());
        }
    }

    @Test void draftsExpiryWrongScopeAndWrongAssignedVersionCannotAuthorizeEntry() {
        ReplacementRule rule = rule("1-1", List.of("OLD"), List.of("NEW"));
        when(rules.findAll()).thenReturn(List.of(rule));
        rule.setLifecycleStatus("DRAFT");
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("NEW")));
        rule.setLifecycleStatus("PUBLISHED"); rule.setEndsAt(Date.from(START.plusSeconds(60)));
        var input = request("NEW"); input.getAttempt().setCompletedAt(rule.getEndsAt());
        assertThrows(IllegalArgumentException.class, () -> controller.add(input));
        rule.setEndsAt(null); rule.setApplicableCohorts(List.of("K2025"));
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("NEW")));
        rule.setApplicableCohorts(List.of("K2024")); rule.setMajorId(new ObjectId());
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("NEW")));
        var differentVersion = request("OLD"); differentVersion.setCurriculumId(new ObjectId().toHexString());
        assertThrows(IllegalArgumentException.class, () -> controller.add(differentVersion));
        verifyNoInteractions(academic);
    }

    @Test void studentViewDoesNotOfferAnUnverifiablePublicationAsAutomaticallyApplicable() {
        ReplacementRule rule = rule("1-1", List.of("OLD"), List.of("NEW"));
        rule.setPublishedAt(null);
        when(rules.findAll()).thenReturn(List.of(rule));
        var api = new StudentCurriculumController(evaluation(), curricula, records, courses, applicability);
        Map<String,Object> response = api.getMyCurriculum(student).getBody().getData();
        @SuppressWarnings("unchecked") var semesters = (List<Map<String,Object>>)response.get("semesters");
        @SuppressWarnings("unchecked") var entries = (List<Map<String,Object>>)semesters.getFirst().get("courses");
        assertEquals(List.of(), entries.getFirst().get("replacementRules"));
    }

    @Test void invalidOrMissingSpecializationTreesAndDraftAssignmentsCannotAcceptNewResults() {
        student.setChosenSpecializationCode("UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("OLD")));
        student.setChosenSpecializationCode("AA"); curriculum.setSpecializationTrees(Map.of("BB", tree("OTHER")));
        assertThrows(IllegalStateException.class, () -> controller.add(request("OLD")));
        curriculum.setSpecializationTrees(Map.of("AA", tree("OLD"))); curriculum.setStatus("DRAFT");
        assertThrows(IllegalArgumentException.class, () -> controller.add(request("OLD")));
        verifyNoInteractions(academic);
    }

    @Test void studentsKeepTheirPublishedVersionWhenArchivedAndNewerVersionsExist() {
        curriculum.setStatus("ARCHIVED");
        controller.add(request("OLD"));
        verify(academic).addAttempt(eq(student.getId()), eq("OLD"), eq(curriculum.getId()), any());
        verify(curricula, never()).findAll();
    }

    private CurriculumEvaluationService evaluation() {
        return new CurriculumEvaluationService(curricula, courses, records, applicability, new GradeConversionServiceImpl(), evidence);
    }

    private AdminAddAcademicAttemptRequest request(String code) {
        var request = new AdminAddAcademicAttemptRequest();
        request.setStudentId(student.getId().toHexString()); request.setCurriculumId(curriculum.getId().toHexString());
        request.setCourseCode(code); request.setAttempt(new AddAcademicAttemptRequest("2024-2", 8.0, null, Date.from(START)));
        return request;
    }

    private ReplacementRule rule(String type, List<String> olds, List<String> news) {
        return ReplacementRule.builder().id(new ObjectId()).ruleType(type).lifecycleStatus("PUBLISHED").status("ACTIVE")
            .majorId(student.getMajorId()).applicableCohorts(List.of("K2024")).effectiveDate(Date.from(START))
            .publishedAt(Date.from(START)).oldCourseCodes(olds).newCourseCodes(news).build();
    }

    private Curriculum.SpecializationTree tree(String mandatory) {
        return new Curriculum.SpecializationTree(List.of(new Curriculum.SemesterItem(1, List.of(
            Curriculum.CurriculumCourseItem.builder().courseCode(mandatory).type("MANDATORY").build(),
            Curriculum.CurriculumCourseItem.builder().courseCode("EL").type("ELECTIVE").build()))), List.of(
                Curriculum.RequirementGroupItem.builder().groupCode("CORE").kind("MANDATORY_COMMON")
                    .requiredCredits(3).eligibleCourseCodes(List.of(mandatory)).build(),
                Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                    .requiredCredits(3).eligibleCourseCodes(List.of("EL")).build()));
    }
}
