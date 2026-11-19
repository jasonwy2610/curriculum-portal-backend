package vn.edu.huflit.portal.academic.service;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.repository.UserRepository;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentCurriculumAssignmentTest {
    private final UserRepository users = mock(UserRepository.class);
    private final CurriculumRepository curricula = mock(CurriculumRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final vn.edu.huflit.portal.curriculum.service.CurriculumRequirementValidationService validator =
        mock(vn.edu.huflit.portal.curriculum.service.CurriculumRequirementValidationService.class,
            withSettings().mockMaker(MockMakers.SUBCLASS));
    private final StudentCurriculumAssignmentService service = new StudentCurriculumAssignmentService(users, curricula, mongo, validator);
    private final Curriculum program = Curriculum.builder().id(new ObjectId()).majorId(new ObjectId()).cohort("K2026")
        .status("ACTIVE").specializations(List.of(new Curriculum.SpecializationItem("AA", "Track A"))).build();
    private final User student = User.builder().id(new ObjectId()).role("STUDENT").majorId(program.getMajorId()).cohort("K2026").build();

    private void setup() {
        when(users.findById(student.getId())).thenReturn(Optional.of(student));
        when(curricula.findById(program.getId())).thenReturn(Optional.of(program));
        when(validator.validate(program)).thenReturn(new vn.edu.huflit.portal.curriculum.service.CurriculumRequirementValidationService.ValidationResult(
            true, List.of(), List.of(), Map.of("AA", 9)));
    }

    @Test
    void assignsOnlyOnceWithCompareAndSetAndAcceptsIdenticalRetry() {
        setup();
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(User.class)))
            .thenReturn(student);
        service.assign(student.getId().toHexString(), program.getId().toHexString(), "AA");
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(Update.class), any(FindAndModifyOptions.class), eq(User.class));
        assertTrue(query.getValue().getQueryObject().containsKey("curriculumId"));
        assertNull(query.getValue().getQueryObject().get("curriculumId"));
        student.setCurriculumId(program.getId()); student.setChosenSpecializationCode("AA");
        assertSame(student, service.assign(student.getId().toHexString(), program.getId().toHexString(), "AA"));
        assertThrows(IllegalArgumentException.class, () ->
            service.assign(student.getId().toHexString(), program.getId().toHexString(), "BB"));
        assertThrows(IllegalArgumentException.class, () ->
            service.assign(student.getId().toHexString(), new ObjectId().toHexString(), "AA"));
    }

    @Test
    void rejectsWrongCohortMajorDraftAndUnknownTrack() {
        setup();
        student.setCohort("K2025");
        assertRejected("AA");
        student.setCohort(program.getCohort()); student.setMajorId(new ObjectId());
        assertRejected("AA");
        student.setMajorId(program.getMajorId()); program.setStatus("DRAFT");
        assertRejected("AA");
        program.setStatus("ACTIVE");
        assertRejected("UNKNOWN");
        verifyNoInteractions(mongo);
    }

    @Test
    void refusesIncompleteLegacyProgramAndConcurrentAssignment() {
        setup();
        when(validator.validate(program)).thenReturn(new vn.edu.huflit.portal.curriculum.service.CurriculumRequirementValidationService.ValidationResult(
            false, List.of("Thiếu cây"), List.of(), Map.of()));
        assertRejected("AA");
        verifyNoInteractions(mongo);
        when(validator.validate(program)).thenReturn(new vn.edu.huflit.portal.curriculum.service.CurriculumRequirementValidationService.ValidationResult(
            true, List.of(), List.of(), Map.of("AA", 9)));
        assertThrows(IllegalStateException.class, () ->
            service.assign(student.getId().toHexString(), program.getId().toHexString(), "AA"));
    }

    @Test
    void repairsMissingTrackOnTheAssignedVersionWithoutAllowingAVersionSwitch() {
        setup();
        student.setCurriculumId(program.getId());
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(User.class)))
            .thenReturn(student);
        service.assign(student.getId().toHexString(), program.getId().toHexString(), "AA");
        assertThrows(IllegalArgumentException.class, () ->
            service.assign(student.getId().toHexString(), new ObjectId().toHexString(), "AA"));
    }

    @Test
    void anExplicitTreeMustExistEvenIfTheSpecializationIsDeclared() {
        setup();
        program.setSpecializationTrees(Map.of("BB", new Curriculum.SpecializationTree()));
        assertThrows(IllegalStateException.class, () ->
            service.assign(student.getId().toHexString(), program.getId().toHexString(), "AA"));
        verifyNoInteractions(mongo);
    }

    private void assertRejected(String code) {
        assertThrows(IllegalArgumentException.class, () ->
            service.assign(student.getId().toHexString(), program.getId().toHexString(), code));
    }
}
