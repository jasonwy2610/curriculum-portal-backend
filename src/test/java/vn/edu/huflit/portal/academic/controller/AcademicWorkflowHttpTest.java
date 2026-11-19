package vn.edu.huflit.portal.academic.controller;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.StudentCurriculumAssignmentService;
import vn.edu.huflit.portal.academic.service.impl.*;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.service.*;
import vn.edu.huflit.portal.entity.*;
import vn.edu.huflit.portal.exception.GlobalExceptionHandler;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;
import vn.edu.huflit.portal.replacement.controller.ReplacementRuleController;
import vn.edu.huflit.portal.replacement.service.impl.ReplacementRuleServiceImpl;
import vn.edu.huflit.portal.notification.repository.NotificationRepository;
import vn.edu.huflit.portal.major.repository.MajorRepository;
import vn.edu.huflit.portal.repository.UserRepository;
import vn.edu.huflit.portal.studentcurriculum.controller.StudentCurriculumController;

import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC/JSON integration with real business services and in-memory mocked persistence. No application/DB bootstrap. */
class AcademicWorkflowHttpTest {
    private final UserRepository users = mock(UserRepository.class);
    private final CurriculumRepository curricula = mock(CurriculumRepository.class);
    private final CourseRepository catalog = mock(CourseRepository.class);
    private final AcademicRecordRepository records = mock(AcademicRecordRepository.class);
    private final ReplacementRuleRepository rules = mock(ReplacementRuleRepository.class);
    private final RecognitionEvidenceRepository evidence = mock(RecognitionEvidenceRepository.class);
    private final ChangeLogService logs = mock(ChangeLogService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final List<AcademicRecord> storedRecords = new ArrayList<>();
    private final List<RecognitionEvidence> storedEvidence = new ArrayList<>();
    private final User student = User.builder().id(new ObjectId()).role("STUDENT").curriculumId(new ObjectId())
        .chosenSpecializationCode("AA").majorId(new ObjectId()).cohort("K2024").passwordHash("test-only-hidden-hash").build();
    private final Curriculum curriculum = Curriculum.builder().id(student.getCurriculumId()).status("ACTIVE")
        .majorId(student.getMajorId()).cohort(student.getCohort())
        .specializations(List.of(new Curriculum.SpecializationItem("AA", "A")))
        .specializationTrees(Map.of("AA", new Curriculum.SpecializationTree(List.of(
            new Curriculum.SemesterItem(1, List.of(item("OLD", "MANDATORY"))),
            new Curriculum.SemesterItem(2, List.of(item("EL", "ELECTIVE"), item("EL2", "ELECTIVE")))), List.of(
                Curriculum.RequirementGroupItem.builder().groupCode("CORE").kind("MANDATORY_COMMON")
                    .requiredCredits(3).eligibleCourseCodes(List.of("OLD")).build(),
                Curriculum.RequirementGroupItem.builder().groupCode("FREE").kind("ELECTIVE_FREE")
                    .requiredCredits(3).eligibleCourseCodes(List.of("EL", "EL2")).build())))).build();
    private final ReplacementRule rule = ReplacementRule.builder().id(new ObjectId()).ruleType("1-1")
        .majorId(student.getMajorId()).applicableCohorts(List.of("K2024")).status("ACTIVE").lifecycleStatus("PUBLISHED")
        .oldCourseCodes(List.of("OLD")).newCourseCodes(List.of("NEW"))
        .effectiveDate(Date.from(Instant.parse("2024-06-01T00:00:00Z")))
        .publishedAt(Date.from(Instant.parse("2024-05-01T00:00:00Z"))).build();
    private final MockMvc mvc;

    AcademicWorkflowHttpTest() {
        when(users.findById(student.getId())).thenReturn(Optional.of(student));
        when(users.existsById(student.getId())).thenReturn(true);
        when(curricula.findById(curriculum.getId())).thenReturn(Optional.of(curriculum));
        when(catalog.existsByCourseCode(anyString())).thenReturn(true);
        when(catalog.findAll()).thenReturn(List.of("OLD", "EL", "EL2", "NEW", "HISTORY").stream()
            .map(code -> Course.builder().courseCode(code).credits(3).build()).toList());
        when(rules.findAll()).thenReturn(List.of(rule));
        when(records.findByStudentId(student.getId())).thenAnswer(call -> List.copyOf(storedRecords));
        when(records.findByStudentIdAndCourseCodeAndCurriculumId(any(), anyString(), any())).thenAnswer(call ->
            storedRecords.stream().filter(r -> Objects.equals(r.getStudentId(), call.getArgument(0))
                && Objects.equals(r.getCourseCode(), call.getArgument(1)) && Objects.equals(r.getCurriculumId(), call.getArgument(2))).findFirst());
        when(records.findById(any(ObjectId.class))).thenAnswer(call ->
            storedRecords.stream().filter(r -> r.getId().equals(call.getArgument(0))).findFirst());
        when(records.save(any(AcademicRecord.class))).thenAnswer(call -> {
            AcademicRecord record = call.getArgument(0);
            if (record.getId() == null) record.setId(new ObjectId());
            if (!storedRecords.contains(record)) storedRecords.add(record);
            return record;
        });
        when(evidence.findByStudentIdAndCurriculumId(student.getId(), curriculum.getId())).thenAnswer(call -> List.copyOf(storedEvidence));
        when(evidence.insert(any(RecognitionEvidence.class))).thenAnswer(call -> {
            RecognitionEvidence saved = call.getArgument(0); storedEvidence.add(saved); return saved;
        });
        var applicability = new ReplacementApplicability(rules);
        var evaluation = new CurriculumEvaluationService(curricula, catalog, records, applicability, new GradeConversionServiceImpl(), evidence);
        var academic = new AcademicRecordServiceImpl(records, new GradeConversionServiceImpl(), logs, users);
        var validator = mock(CurriculumRequirementValidationService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        var assignments = new StudentCurriculumAssignmentService(users, curricula, mongo, validator);
        var replacements = new ReplacementRuleServiceImpl(rules, logs, mock(NotificationRepository.class), users,
            catalog, mock(MajorRepository.class), curricula, mongo, applicability);
        mvc = MockMvcBuilders.standaloneSetup(new AdminAcademicRecordController(academic, users, curricula, catalog, applicability),
            new StudentCurriculumController(evaluation, curricula, records, catalog, applicability),
            new AdminStudentLookupController(mongo, assignments), new ReplacementRuleController(replacements))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(student, null, List.of()));
    }

    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }

    @Test void entryPreviewOfficialRecognitionAndHistoryAgreeAcrossHttpApis() throws Exception {
        postScore("NEW", "\"2024-06-01T00:00:00Z\"").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.highestScore10").value(8.0))
            .andExpect(jsonPath("$.data.attempts[0].completedAt").exists());
        assertEquals(Date.from(Instant.parse("2024-06-01T00:00:00Z")), storedRecords.getFirst().getAttempts().getFirst().getCompletedAt());
        postScore("EL2", "\"2024-07-01T00:00:00Z\"").andExpect(status().isOk());
        mvc.perform(get("/api/v1/student/curriculum/evaluation-preview")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.recognizedCredits").value(6))
            .andExpect(jsonPath("$.data.cumulativeGpa4").value(3.5));
        assertTrue(storedEvidence.isEmpty());
        mvc.perform(get("/api/v1/student/curriculum")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.officialGraduationEligible").value(true))
            .andExpect(jsonPath("$.data.semesters[0].courses[0].courseCode").value("OLD"))
            .andExpect(jsonPath("$.data.semesters[0].courses[0].completedByReplacement").value(true))
            .andExpect(jsonPath("$.data.semesters[1].courses[0].status").value("NOT_STUDIED"))
            .andExpect(jsonPath("$.data.semesters[1].courses[0].electiveGroupComplete").value(true));
        assertEquals(1, storedEvidence.size());
        RecognitionEvidence original = storedEvidence.getFirst();
        rule.setEndsAt(rule.getEffectiveDate()); // Simulate subsequent retirement/removal from the applicable timeline.
        mvc.perform(get("/api/v1/student/curriculum/evaluation-official")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.recognizedCredits").value(6))
            .andExpect(jsonPath("$.data.cumulativeGpa4").value(3.5));
        assertSame(original, storedEvidence.getFirst());
        assertEquals(1, storedEvidence.size());
        mvc.perform(get("/api/v1/student/curriculum")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.semesters[0].courses[0].replacementRules[0].recognized").value(true))
            .andExpect(jsonPath("$.data.semesters[0].courses[0].replacementRules[0].newCourses[0].courseCode").value("NEW"));
        mvc.perform(get("/api/v1/admin/academic-records/student/" + student.getId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        verify(evidence, never()).save(any(RecognitionEvidence.class));
    }

    @Test void missingDatesDraftRulesAndWrongCohortsReturnBusinessErrorsWithoutWrites() throws Exception {
        postScore("NEW", "null").andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("completedAt")));
        rule.setLifecycleStatus("DRAFT");
        postScore("NEW", "\"2024-06-01T00:00:00Z\"").andExpect(status().isBadRequest());
        rule.setLifecycleStatus("PUBLISHED"); curriculum.setCohort("K2025");
        postScore("OLD", "\"2024-06-01T00:00:00Z\"").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("khóa tuyển sinh")));
        assertTrue(storedRecords.isEmpty()); assertTrue(storedEvidence.isEmpty());
    }

    @Test void historicalResultsOutsideTheTreeRemainReadableAndExplicitCorrectionsKeepAttemptIdentity() throws Exception {
        var old = new AcademicRecord.AttemptItem(); old.setAttemptId("historical-id"); old.setScore10(5.0);
        Date completed = Date.from(Instant.parse("2024-01-01T00:00:00Z")); old.setCompletedAt(completed);
        storedRecords.add(AcademicRecord.builder().id(new ObjectId()).studentId(student.getId()).curriculumId(curriculum.getId())
            .courseCode("HISTORY").attempts(new ArrayList<>(List.of(old))).build());
        postScore("HISTORY", "\"2024-06-01T00:00:00Z\"").andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/admin/academic-records/" + storedRecords.getFirst().getId() + "/attempts/historical-id")
            .contentType("application/json").content("{\"score10\":7,\"reason\":\"Sửa theo bảng điểm gốc\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.highestScore10").value(7.0))
            .andExpect(jsonPath("$.data.attempts.length()").value(1));
        assertEquals("historical-id", old.getAttemptId()); assertEquals(completed, old.getCompletedAt());
        mvc.perform(get("/api/v1/admin/academic-records/student/" + student.getId())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].courseCode").value("HISTORY"));
        mvc.perform(get("/api/v1/student/curriculum/evaluation-preview")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.recognizedCredits").value(0)).andExpect(jsonPath("$.data.gpaCredits").value(0));
        assertEquals(1, storedRecords.size());
        verify(logs).recordChange(eq("AcademicRecord"), any(), eq("CORRECT_ATTEMPT"), anyList(), any());
    }

    @Test void assignmentApiAcceptsIdenticalRetryButRejectsVersionAndTrackSwitches() throws Exception {
        String path = "/api/v1/admin/students/" + student.getId() + "/curriculum-assignment";
        mvc.perform(put(path).contentType("application/json").content(assignment(curriculum.getId().toHexString(), "AA")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.chosenSpecializationCode").value("AA"));
        mvc.perform(put(path).contentType("application/json").content(assignment(new ObjectId().toHexString(), "AA")))
            .andExpect(status().isBadRequest());
        mvc.perform(put(path).contentType("application/json").content(assignment(curriculum.getId().toHexString(), "BB")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(mongo);
    }

    @Test void ruleListingAndEntryUseTheSameTreeAndElectiveSourcePolicy() throws Exception {
        mvc.perform(get("/api/v1/student/replacement-rules")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/v1/student/replacement-rules/" + rule.getId())).andExpect(status().isOk());
        rule.setNewCourseCodes(List.of("EL"));
        mvc.perform(get("/api/v1/student/replacement-rules")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/v1/student/replacement-rules/" + rule.getId())).andExpect(status().isBadRequest());
        rule.setNewCourseCodes(List.of("NEW")); rule.setLifecycleStatus("DRAFT");
        postScore("NEW", "\"2024-06-01T00:00:00Z\"").andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/student/replacement-rules")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test void adminDirectoryPreservesAssignmentIdentityAndDoesNotExposePasswordHash() throws Exception {
        when(mongo.find(any(org.springframework.data.mongodb.core.query.Query.class), eq(User.class))).thenReturn(List.of(student));
        when(mongo.count(any(org.springframework.data.mongodb.core.query.Query.class), eq(User.class))).thenReturn(1L);
        mvc.perform(get("/api/v1/admin/students")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].curriculumId").value(curriculum.getId().toHexString()))
            .andExpect(jsonPath("$.data.items[0].majorId").value(student.getMajorId().toHexString()))
            .andExpect(jsonPath("$.data.items[0].cohort").value("K2024"))
            .andExpect(jsonPath("$.data.items[0].chosenSpecializationCode").value("AA"))
            .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-only-hidden-hash"))));
    }

    private org.springframework.test.web.servlet.ResultActions postScore(String code, String dateJson) throws Exception {
        return mvc.perform(post("/api/v1/admin/academic-records/attempts").contentType("application/json").content("""
            {"studentId":"%s","curriculumId":"%s","courseCode":"%s", "attempt":
                {"semesterCode":"2024-2","score10":8,"completedAt":%s}}
            """.formatted(student.getId(), curriculum.getId(), code, dateJson)));
    }

    private String assignment(String id, String track) {
        return "{\"curriculumId\":\"" + id + "\",\"chosenSpecializationCode\":\"" + track + "\"}";
    }

    private Curriculum.CurriculumCourseItem item(String code, String type) {
        return Curriculum.CurriculumCourseItem.builder().courseCode(code).type(type).build();
    }
}
