package vn.edu.huflit.portal.curriculum.service;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.entity.User;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Identity is initialized in source data; resolution never repairs or switches it. */
class StoredStudentCurriculumTest {
    private final Curriculum program=Curriculum.builder().id(new ObjectId()).majorId(new ObjectId()).cohort("K2026")
        .status("ACTIVE").version(1).specializations(List.of(new Curriculum.SpecializationItem("AA","Track A"),new Curriculum.SpecializationItem("BB","Track B")))
        .specializationTrees(Map.of("AA",new Curriculum.SpecializationTree(List.of(new Curriculum.SemesterItem(1,List.of(Curriculum.CurriculumCourseItem.builder().courseCode("A").build()))),List.of()),
            "BB",new Curriculum.SpecializationTree(List.of(new Curriculum.SemesterItem(1,List.of(Curriculum.CurriculumCourseItem.builder().courseCode("B").build()))),List.of()))).build();
    private final User student=User.builder().id(new ObjectId()).role("STUDENT").majorId(program.getMajorId()).cohort("K2026")
        .curriculumId(program.getId()).chosenSpecializationCode("AA").build();

    @Test void selectsStoredTreeWithoutMutatingIdentityOrProgram(){
        var selected=CurriculumTracks.forStudent(program,student);
        assertEquals("A",selected.getSemesters().getFirst().getCourses().getFirst().getCourseCode());
        assertEquals(program.getId(),student.getCurriculumId());assertEquals("AA",student.getChosenSpecializationCode());
        assertEquals(2,program.getSpecializationTrees().size());assertEquals(1,selected.getVersion());
    }
    @Test void missingLinksAndMissingDocumentAreExplicitErrorsAndNeverRepaired(){
        student.setCurriculumId(null);assertThrows(IllegalArgumentException.class,()->CurriculumTracks.forStudent(program,student));
        assertNull(student.getCurriculumId());student.setCurriculumId(program.getId());
        assertTrue(assertThrows(IllegalArgumentException.class,()->CurriculumTracks.forStudent(null,student)).getMessage().contains("dữ liệu nguồn"));
        student.setChosenSpecializationCode(null);assertThrows(IllegalArgumentException.class,()->CurriculumTracks.forStudent(program,student));assertNull(student.getChosenSpecializationCode());
    }
    @Test void rejectsWrongMajorCohortTrackAndMissingTree(){
        student.setMajorId(new ObjectId());assertThrows(IllegalArgumentException.class,()->CurriculumTracks.forStudent(program,student));
        student.setMajorId(program.getMajorId());student.setCohort("K2025");assertThrows(IllegalArgumentException.class,()->CurriculumTracks.forStudent(program,student));
        student.setCohort("K2026");student.setChosenSpecializationCode("FOREIGN");assertThrows(IllegalArgumentException.class,()->CurriculumTracks.forStudent(program,student));
        student.setChosenSpecializationCode("AA");program.setSpecializationTrees(Map.of("BB",new Curriculum.SpecializationTree()));
        assertThrows(IllegalStateException.class,()->CurriculumTracks.forStudent(program,student));assertEquals("AA",student.getChosenSpecializationCode());
    }
    @Test void keepsArchivedAssignedVersionAndRejectsDraftOrAnotherVersion(){
        program.setStatus("ARCHIVED");assertEquals(1,CurriculumTracks.forStudent(program,student).getVersion());
        var next=program.toBuilder().id(new ObjectId()).version(2).status("ACTIVE").build();
        assertThrows(IllegalArgumentException.class,()->CurriculumTracks.forStudent(next,student));
        assertEquals(program.getId(),student.getCurriculumId());program.setStatus("DRAFT");
        assertThrows(IllegalArgumentException.class,()->CurriculumTracks.forStudent(program,student));
    }
}
