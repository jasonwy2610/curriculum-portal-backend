package vn.edu.huflit.portal.academic.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.bson.types.ObjectId;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.impl.GradeConversionServiceImpl;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.service.CurriculumEvaluationService;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
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
    final CurriculumRepository curricula=mock(CurriculumRepository.class);
    final StudentTranscriptService service=new StudentTranscriptService(records,courses,evaluation,new GradeConversionServiceImpl(),curricula);
    final User student=User.builder().id(new ObjectId()).curriculumId(new ObjectId()).majorId(new ObjectId()).cohort("K2024").chosenSpecializationCode("AA").build();
    Curriculum program;
    @BeforeEach void assigned() {
        program=Curriculum.builder().id(student.getCurriculumId()).majorId(student.getMajorId()).cohort(student.getCohort())
            .status("ACTIVE").version(3).specializations(List.of(new Curriculum.SpecializationItem("AA","A")))
            .semesters(List.of(new Curriculum.SemesterItem(1,List.of()))).build();
        when(curricula.findById(student.getCurriculumId())).thenReturn(Optional.of(program));
    }
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
    @Test void confirmedK2023CalendarIsReturnedWithoutChangingActualHistory() {
        plan(8);
        student.setCurriculumId(new ObjectId("48982b5177b75b4f638ec2b3"));
        student.setCohort("K2023");student.setChosenSpecializationCode("CNPM");
        program.setId(student.getCurriculumId());program.setVersion(1);program.setCohort("K2023");
        program.setSpecializationTrees(Map.of("CNPM",program.getSpecializationTrees().get("AA")));
        program.setSpecializations(List.of(new Curriculum.SpecializationItem("CNPM","Công nghệ phần mềm")));
        when(curricula.findById(student.getCurriculumId())).thenReturn(Optional.of(program));
        setup(List.of(AcademicRecord.builder().courseCode("C1").attempts(List.of(attempt(8,"2025-3",1))).build()),planCatalog(8));
        var v=service.get(student);
        assertEquals(List.of("2023-1","2023-2","2024-1","2024-2","2025-1","2025-2","2026-1","2026-2"),
            v.curriculumPlan().semesters().stream().map(s->s.academicSemesterCode()).toList());
        assertEquals("2025-3",v.attempts().getFirst().semesterCode());
        assertEquals(1,v.attempts().size());assertEquals(3.5,v.summary().cumulativeGpa4());
        verify(records,never()).save(any());verify(curricula,never()).save(any());
    }
    void plan(int count) {
        var semesters=new ArrayList<Curriculum.SemesterItem>();
        for(int i=1;i<=count;i++)semesters.add(new Curriculum.SemesterItem(i,List.of(
            Curriculum.CurriculumCourseItem.builder().courseCode("C"+i).build())));
        program.setSpecializationTrees(Map.of("AA",new Curriculum.SpecializationTree(semesters,List.of()),
            "BB",new Curriculum.SpecializationTree(List.of(new Curriculum.SemesterItem(99,List.of(
                Curriculum.CurriculumCourseItem.builder().courseCode("OTHER").build()))),List.of())));
        program.setSpecializations(List.of(new Curriculum.SpecializationItem("AA","A"),new Curriculum.SpecializationItem("BB","B")));
    }
    List<Course> planCatalog(int count) {
        return java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i->Course.builder().courseCode("C"+i).courseName("Môn "+i).credits(3).build()).toList();
    }
    @Test void eightPlannedTermsAndThreeActualTermsStaySeparateWithoutInventedAcademicYears(){
        plan(8);var history=new ArrayList<AcademicRecord>();
        for(int i=1;i<=3;i++)history.add(AcademicRecord.builder().courseCode("C"+i).attempts(List.of(attempt(7,"2024-"+i,1))).build());
        setup(history,planCatalog(8));var value=service.get(student);
        assertTrue(value.curriculumPlan().available());assertEquals(8,value.curriculumPlan().semesters().size());
        assertTrue(value.curriculumPlan().semesters().stream().allMatch(s->s.academicSemesterCode()==null),
            "Cohort and actual grades are not an official academic calendar");
        assertEquals(3,value.semesters().size());assertEquals(3,value.attempts().size());
        assertEquals(3,value.curriculumPlan().version());assertEquals("AA",value.curriculumPlan().specializationCode());
        assertEquals("PLAN:8",value.curriculumPlan().semesters().getLast().semesterKey());
        assertEquals("NOT_STUDIED",value.curriculumPlan().semesters().getLast().courses().getFirst().state());
        assertEquals("2024-1",value.attempts().getFirst().semesterCode());
        verify(records,never()).save(any());verify(curricula,never()).save(any());
    }
    @Test void nonEightTermPlanAndAStudentWithoutAnyGradesReturnOnlyPlannedReferences(){
        plan(5);setup(List.of(),planCatalog(5));var value=service.get(student);
        assertEquals(5,value.curriculumPlan().semesters().size());assertTrue(value.attempts().isEmpty());assertTrue(value.semesters().isEmpty());
        assertEquals(0,value.summary().academicEarnedCredits());
        assertTrue(value.curriculumPlan().semesters().stream().flatMap(s->s.courses().stream()).allMatch(c->"NOT_STUDIED".equals(c.state())));
    }
    @Test void earlyLateAndRetakenCoursesRemainAtTheirActualTerms(){
        plan(8);setup(List.of(AcademicRecord.builder().courseCode("C8").attempts(List.of(attempt(3,"2024-1",1),attempt(9,"2026-2",2))).build(),
            AcademicRecord.builder().courseCode("C1").attempts(List.of(attempt(7,"2027-3",1))).build()),planCatalog(8));
        var value=service.get(student);var planned=value.curriculumPlan().semesters().getLast().courses().getFirst();
        assertEquals(List.of("2024-1","2026-2"),planned.actualSemesterCodes());assertEquals("HAS_HISTORY",planned.state());
        assertEquals(List.of("2024-1","2026-2","2027-3"),value.semesters().stream().map(s->s.semesterCode()).toList());
        assertEquals(3,value.attempts().size());assertTrue(value.attempts().get(1).retake());assertEquals(3.5,value.summary().cumulativeGpa4());
    }
    @Test void electiveCompletionAndAllocatedReplacementAreReadOnlyPlanAnnotations(){
        plan(3);setup(List.of(AcademicRecord.builder().courseCode("SOURCE").attempts(List.of(attempt(8,"2026-2",1))).build()),
            List.of(Course.builder().courseCode("SOURCE").credits(3).courseName("Nguồn").build(),planCatalog(3).get(0),planCatalog(3).get(1),planCatalog(3).get(2)));
        program.getSpecializationTrees().get("AA").setRequirementGroups(List.of(Curriculum.RequirementGroupItem.builder()
            .groupCode("FREE").kind("ELECTIVE_FREE").requiredCredits(3).eligibleCourseCodes(List.of("C2","C3")).build()));
        official=new HashMap<>(official);official.put("recognizedReplacements",Map.of("C1",List.of("SOURCE")));
        official.put("groupProgress",List.of(Map.of("groupCode","CORE","recognizedCourseCodes",List.of("C1")),
            Map.of("groupCode","FREE","kind","ELECTIVE_FREE","complete",true,"recognizedCourseCodes",List.of("C2"))));
        var value=service.get(student);
        assertEquals("RECOGNIZED_REPLACEMENT",value.curriculumPlan().semesters().getFirst().courses().getFirst().state());
        assertEquals(List.of("SOURCE"),value.curriculumPlan().semesters().getFirst().courses().getFirst().replacementCourseCodes());
        assertEquals("GROUP_COMPLETE",value.curriculumPlan().semesters().getLast().courses().getFirst().state());
        assertEquals(1,value.attempts().size());assertEquals(3,value.summary().academicEarnedCredits());assertEquals(5,value.summary().graduationRecognizedCredits());
        verify(records,never()).save(any());
    }
    @Test void invalidStoredSpecializationDoesNotGuessAnotherTreeAndKeepsActualHistory(){
        plan(3);student.setChosenSpecializationCode("UNKNOWN");setup(List.of(AcademicRecord.builder().courseCode("C1").attempts(List.of(attempt(7,"2024-1",1))).build()),planCatalog(3));
        var value=service.get(student);assertFalse(value.curriculumPlan().available());assertTrue(value.curriculumPlan().semesters().isEmpty());
        assertTrue(value.curriculumPlan().message().contains("Chuyên ngành cố định không thuộc CTĐT"));
        assertEquals(1,value.attempts().size());verify(curricula,never()).save(any());
    }
    @Test void specializationTreeReturnsAllFiveCoursesWhenOnlyOneHasAnAcademicRecord(){
        plan(1);
        program.getSpecializationTrees().get("AA").setSemesters(List.of(new Curriculum.SemesterItem(1,
            java.util.stream.IntStream.rangeClosed(1,5).mapToObj(i->Curriculum.CurriculumCourseItem.builder().courseCode("C"+i).build()).toList())));
        setup(List.of(AcademicRecord.builder().courseCode("C1").attempts(List.of(attempt(8,"2026-2",1))).build()),planCatalog(5));
        var v=service.get(student);
        assertTrue(v.curriculumPlan().available());assertEquals(5,v.curriculumPlan().semesters().getFirst().courses().size());
        assertEquals(4,v.curriculumPlan().semesters().getFirst().courses().stream().filter(c->"NOT_STUDIED".equals(c.state())).count());
        assertEquals(1,v.attempts().size());assertEquals("2026-2",v.attempts().getFirst().semesterCode());
        assertEquals(student.getCurriculumId().toHexString(),v.curriculumPlan().curriculumId());
        assertEquals("AA",v.curriculumPlan().specializationCode());assertEquals(3.5,v.summary().cumulativeGpa4());
        verify(records,never()).save(any());
    }
    @Test void completingOneGraduationGroupDoesNotMarkASharedChoiceUnnecessaryForAnotherUnfinishedGroup(){
        plan(3);setup(List.of(),planCatalog(3));
        program.getSpecializationTrees().get("AA").setRequirementGroups(List.of(
            Curriculum.RequirementGroupItem.builder().groupCode("G1").kind("GRADUATION").completionOptions(List.of(
                new Curriculum.CompletionOptionItem("A",List.of("C3")),new Curriculum.CompletionOptionItem("B",List.of("C1")))).build(),
            Curriculum.RequirementGroupItem.builder().groupCode("G2").kind("GRADUATION").completionOptions(List.of(
                new Curriculum.CompletionOptionItem("A",List.of("C3")),new Curriculum.CompletionOptionItem("B",List.of("C2")))).build()));
        official=new HashMap<>(official);official.put("groupProgress",List.of(Map.of("groupCode","G1","complete",true),Map.of("groupCode","G2","complete",false)));
        var v=service.get(student);
        assertEquals("NOT_STUDIED",v.curriculumPlan().semesters().getLast().courses().getFirst().state());
    }
}
