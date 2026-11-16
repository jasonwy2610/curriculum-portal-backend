package vn.edu.huflit.portal.academic.controller;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import vn.edu.huflit.portal.academic.service.AcademicRecordService;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.dto.request.AddAcademicAttemptRequest;
import vn.edu.huflit.portal.dto.request.AdminAddAcademicAttemptRequest;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;
import vn.edu.huflit.portal.repository.UserRepository;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AdminAcademicRecordControllerTest {
    @Test
    void replacementEntryRequiresActualCompletionUnderTheApplicableVersion() {
        var academic = mock(AcademicRecordService.class);
        var students = mock(UserRepository.class);
        var curricula = mock(CurriculumRepository.class);
        var courses = mock(CourseRepository.class);
        var rules = mock(ReplacementRuleRepository.class);
        var controller = new AdminAcademicRecordController(
            academic, students, curricula, courses, new ReplacementApplicability(rules));
        var student = User.builder().id(new ObjectId()).curriculumId(new ObjectId()).majorId(new ObjectId())
            .role("STUDENT").cohort("K2024").build();
        var curriculum = Curriculum.builder().id(student.getCurriculumId())
            .semesters(List.of(Curriculum.SemesterItem.builder().courses(List.of(
                Curriculum.CurriculumCourseItem.builder().courseCode("OLD").build())).build())).build();
        Instant start = Instant.parse("2024-06-01T00:00:00Z");
        var rule = ReplacementRule.builder().id(new ObjectId()).ruleType("1-1")
            .status("ACTIVE").lifecycleStatus("PUBLISHED").majorId(student.getMajorId())
            .applicableCohorts(List.of("K2024")).oldCourseCodes(List.of("OLD")).newCourseCodes(List.of("NEW"))
            .effectiveDate(Date.from(start)).publishedAt(Date.from(start)).build();
        when(students.findById(student.getId())).thenReturn(Optional.of(student));
        when(curricula.findById(curriculum.getId())).thenReturn(Optional.of(curriculum));
        when(courses.existsByCourseCode("NEW")).thenReturn(true);
        when(rules.findAll()).thenReturn(List.of(rule));

        var request = new AdminAddAcademicAttemptRequest();
        request.setStudentId(student.getId().toHexString());
        request.setCurriculumId(curriculum.getId().toHexString());
        request.setCourseCode("NEW");
        var attempt = new AddAcademicAttemptRequest("2024-2", 8.0, null, null);
        request.setAttempt(attempt);
        assertThrows(IllegalArgumentException.class, () -> controller.add(request));
        attempt.setCompletedAt(Date.from(start.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> controller.add(request));
        attempt.setCompletedAt(Date.from(Instant.now().plusSeconds(3600)));
        assertThrows(IllegalArgumentException.class, () -> controller.add(request));
        verifyNoInteractions(academic);

        attempt.setCompletedAt(Date.from(start));
        controller.add(request);
        verify(academic).addAttempt(student.getId(), "NEW", curriculum.getId(), attempt);
    }
}
