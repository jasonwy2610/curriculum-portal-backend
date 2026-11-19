package vn.edu.huflit.portal.academic.service;

import org.junit.jupiter.api.Test;
import org.bson.types.ObjectId;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.impl.GradeConversionServiceImpl;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.service.CurriculumEvaluationService;
import vn.edu.huflit.portal.entity.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentTranscriptServiceTest {
    final AcademicRecordRepository records=mock(AcademicRecordRepository.class);
    final CourseRepository courses=mock(CourseRepository.class);
    Map<String,Object> official=Map.of(); int previewCalls;
    final CurriculumEvaluationService evaluation=new CurriculumEvaluationService(null,null,null,null,null,null){
        @Override public Map<String,Object> evaluate(User user){previewCalls++;return official;}
        @Override public Map<String,Object> evaluateOfficial(User user){throw new AssertionError("Transcript must not persist recognition");}
    };
    final StudentTranscriptService service=new StudentTranscriptService(records,courses,evaluation,new GradeConversionServiceImpl());
    final User student=User.builder().id(new ObjectId()).curriculumId(new ObjectId()).build();
    AcademicRecord.AttemptItem attempt(double score,String term,int number) {
        var a=new AcademicRecord.AttemptItem();a.setAttemptId(UUID.randomUUID().toString());a.setScore10(score);
        a.setSemesterCode(term);a.setAttemptNumber(number);a.setCompletedAt(new Date(1700000000000L));return a;
    }
    void setup(List<AcademicRecord> history,List<Course> catalog){
        when(records.findByStudentId(student.getId())).thenReturn(history);
        when(courses.findByCourseCodeIn(anyList())).thenReturn(catalog);
        official=Map.of("cumulativeGpa4",3.5,"gpaCredits",7,"recognizedCredits",5,
            "semesterGpa4",Map.of("2024-1",1.5,"2025-1",4.0),"semesterGpaCredits",Map.of("2024-1",7,"2025-1",4));
    }
    @Test void enrichesRetakesAndUsesOfficialGpaWithoutConflatingEarnedAndRecognizedCredits(){
        var r=AcademicRecord.builder().id(new ObjectId()).studentId(student.getId()).courseCode("C4")
            .attempts(List.of(attempt(3,"2024-1",1),attempt(10,"2025-1",2))).build();
        setup(List.of(r),List.of(Course.builder().courseCode("C4").courseName("Tên học phần").credits(4).build()));
        var value=service.get(student);
        assertEquals(2,value.attempts().size());assertEquals("Tên học phần",value.attempts().getFirst().courseName());
        assertEquals(4,value.attempts().getFirst().credits());assertEquals("FAILED",value.attempts().getFirst().status());
        assertEquals("F",value.attempts().getFirst().letterGrade());assertEquals(0.0,value.attempts().getFirst().score4());
        assertTrue(value.attempts().getLast().retake());assertEquals("2025",value.attempts().getLast().academicYear());
        assertEquals(3.5,value.summary().cumulativeGpa4());assertEquals(4,value.summary().academicEarnedCredits());
        assertEquals(5,value.summary().graduationRecognizedCredits());assertEquals(0,value.summary().failedCourseCount());
        assertEquals(1.5,value.semesters().getFirst().gpa4());assertEquals(0,value.semesters().getFirst().earnedCredits());
        assertEquals(4,value.semesters().getLast().earnedCredits());assertEquals(1,previewCalls);
    }
    @Test void missingCatalogDoesNotProduceFabricatedCreditsOrGpa(){
        setup(List.of(AcademicRecord.builder().courseCode("MISSING").attempts(List.of(attempt(7,"2024-1",1))).build()),List.of());
        var v=service.get(student);assertNull(v.summary().cumulativeGpa4());assertNull(v.summary().academicEarnedCredits());
        assertNull(v.semesters().getFirst().attemptedCredits());assertNull(v.semesters().getFirst().gpa4());
        assertTrue(v.issues().stream().anyMatch(i->i.field().equals("credits")&&i.courseCode().equals("MISSING")));
    }
    @Test void missingSemesterAndCompletionRemainExplicitWithoutManufacturedDates(){
        var a=attempt(7,"unknown",1);a.setCompletedAt(null);
        setup(List.of(AcademicRecord.builder().courseCode("C").attempts(List.of(a)).build()),List.of(Course.builder().courseCode("C").courseName("C").credits(3).build()));
        var v=service.get(student);assertEquals("UNKNOWN",v.attempts().getFirst().semesterCode());assertNull(v.attempts().getFirst().completedAt());
        assertNull(v.semesters().getFirst().gpa4());assertEquals(2,v.issues().size());
    }
    @Test void unassignedStudentStillSeesHistoryAndNoOfficialGpa(){
        student.setCurriculumId(null);setup(List.of(),List.of());var v=service.get(student);
        assertTrue(v.attempts().isEmpty());assertNull(v.summary().cumulativeGpa4());assertEquals(0,v.summary().academicEarnedCredits());
        assertEquals(0,previewCalls);
    }
    @Test void passFailCompletionDoesNotFabricateNumericGradeOrMissingScoreWarning(){
        var a=attempt(0,"2024-1",1);a.setScore10(null);
        setup(List.of(AcademicRecord.builder().courseCode("PF").status("PASSED").attempts(List.of(a)).build()),List.of(Course.builder().courseCode("PF").courseName("Điều kiện").credits(2).build()));
        official=new HashMap<>(official);official.put("gpaExcludedPassFailCourseCodes",List.of("PF"));
        var v=service.get(student);assertEquals("PASSED",v.attempts().getFirst().status());assertNull(v.attempts().getFirst().score4());
        assertEquals(2,v.summary().academicEarnedCredits());assertTrue(v.issues().isEmpty());
    }
    @Test void aggregateWithoutAttemptsIsRetainedAsAnExplicitHistoryIssueNotPartialGpa(){
        setup(List.of(AcademicRecord.builder().courseCode("C").status("PASSED").attempts(List.of()).build()),List.of(Course.builder().courseCode("C").courseName("C").credits(3).build()));
        var v=service.get(student);assertNull(v.summary().cumulativeGpa4());assertNull(v.summary().academicEarnedCredits());
        assertTrue(v.attempts().isEmpty());assertEquals("attempts",v.issues().getFirst().field());
    }
}
