package vn.edu.huflit.portal.studentcurriculum.controller;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.impl.GradeConversionServiceImpl;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.service.*;
import vn.edu.huflit.portal.entity.AcademicRecord;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;

import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentElectiveProgressTest {
    @Test
    @SuppressWarnings("unchecked")
    void alternativesRemainInTheirPlannedSemestersWithoutBecomingDebtsOrChangingGrades() {
        var curricula = mock(CurriculumRepository.class);
        var catalog = mock(CourseRepository.class);
        var records = mock(AcademicRecordRepository.class);
        var rules = mock(ReplacementRuleRepository.class);
        var evidence = mock(RecognitionEvidenceRepository.class);
        User student = User.builder().id(new ObjectId()).curriculumId(new ObjectId())
            .majorId(new ObjectId()).cohort("K2024").build();
        Curriculum curriculum = Curriculum.builder().id(student.getCurriculumId())
            .majorId(student.getMajorId()).cohort(student.getCohort())
            .requirementGroups(List.of(Curriculum.RequirementGroupItem.builder().groupCode("FREE")
                .kind("ELECTIVE_FREE").requiredCredits(3).eligibleCourseCodes(List.of("E", "F", "U")).build()))
            .semesters(List.of(new Curriculum.SemesterItem(1, List.of(item("E"))),
                new Curriculum.SemesterItem(2, List.of(item("F"))),
                new Curriculum.SemesterItem(3, List.of(item("U"))))).build();
        when(curricula.findById(student.getCurriculumId())).thenReturn(Optional.of(curriculum));
        when(catalog.findAll()).thenReturn(List.of("E", "F", "U").stream()
            .map(code -> Course.builder().courseCode(code).credits(3).build()).toList());
        var passed = record(student, "E", 7, "PASSED");
        var failed = record(student, "F", 2, "FAILED");
        when(records.findByStudentId(student.getId())).thenReturn(List.of(passed, failed));
        var applicability = new ReplacementApplicability(rules);
        var evaluation = new CurriculumEvaluationService(curricula, catalog, records, applicability,
            new GradeConversionServiceImpl(), evidence);
        var controller = new StudentCurriculumController(evaluation, curricula, records, catalog, applicability);

        Map<String,Object> response = controller.getMyCurriculum(student).getBody().getData();
        List<Map<String,Object>> semesters = (List<Map<String,Object>>)response.get("semesters");
        assertEquals(List.of(1, 2, 3), semesters.stream().map(s -> s.get("semesterNumber")).toList());
        List<Map<String,Object>> courses = semesters.stream()
            .flatMap(s -> ((List<Map<String,Object>>)s.get("courses")).stream()).toList();
        assertEquals(List.of("E", "F", "U"), courses.stream().map(c -> c.get("courseCode")).toList());
        assertEquals(List.of("PASSED", "FAILED", "NOT_STUDIED"), courses.stream().map(c -> c.get("status")).toList());
        assertTrue(courses.stream().allMatch(c -> Boolean.FALSE.equals(c.get("requiredForCompletion"))
            && Boolean.TRUE.equals(c.get("electiveGroupComplete"))));
        assertEquals(3, response.get("recognizedCredits"));
        assertEquals(3, response.get("electiveQuotaEstimate"));
        assertEquals(true, response.get("officialGraduationEligible"));
        assertEquals(1.5, response.get("cumulativeGpa4"));
        assertEquals("FAILED", failed.getStatus());
        assertEquals(2.0, failed.getHighestScore10());
        verify(records, never()).save(any(AcademicRecord.class));
    }

    private Curriculum.CurriculumCourseItem item(String code) {
        return Curriculum.CurriculumCourseItem.builder().courseCode(code).type("ELECTIVE").build();
    }

    private AcademicRecord record(User student, String code, double score, String status) {
        var attempt = new AcademicRecord.AttemptItem();
        attempt.setScore10(score);
        attempt.setCompletedAt(Date.from(Instant.parse("2024-03-01T00:00:00Z")));
        attempt.setSemesterCode("2024-1");
        return AcademicRecord.builder().studentId(student.getId()).curriculumId(student.getCurriculumId())
            .courseCode(code).highestScore10(score).status(status).attempts(List.of(attempt)).build();
    }
}
