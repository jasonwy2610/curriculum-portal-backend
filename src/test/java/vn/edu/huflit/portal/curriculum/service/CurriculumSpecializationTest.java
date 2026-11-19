package vn.edu.huflit.portal.curriculum.service;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.*;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.major.model.*;
import vn.edu.huflit.portal.major.repository.MajorRepository;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurriculumSpecializationTest {
    private final CurriculumRepository repository = mock(CurriculumRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final MajorRepository majors = mock(MajorRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final ChangeLogService logs = mock(ChangeLogService.class);
    private final CurriculumRequirementValidationService validator =
        new CurriculumRequirementValidationService(repository, courses, majors);
    private final CurriculumCreditPlanService creditPlan = new CurriculumCreditPlanService(courses);
    private final CurriculumPublicationService publication =
        new CurriculumPublicationService(repository, validator, mongo, logs, creditPlan);

    private Curriculum fixture() {
        Curriculum program = Curriculum.builder().id(new ObjectId()).revision(0L).majorId(new ObjectId())
            .curriculumCode("IT-2026").cohort("K2026").status("DRAFT").specializationSelectionTiming("EARLY")
            .specializationSelectionSemester(1).specializations(List.of(
                new Curriculum.SpecializationItem("AA", "Track A"), new Curriculum.SpecializationItem("BB", "Track B")))
            .specializationTrees(new LinkedHashMap<>(Map.of("AA", tree("A", 1), "BB", tree("B", 2)))).build();
        var catalog = List.of(course("COMMON", 3), course("A", 3), course("B", 6), course("E", 3));
        when(courses.findAll()).thenReturn(catalog);
        when(courses.findByCourseCodeIn(anyList())).thenAnswer(call -> {
            List<String> codes = call.getArgument(0);
            return catalog.stream().filter(course -> codes.contains(course.getCourseCode())).toList();
        });
        when(majors.findById(program.getMajorId())).thenReturn(Optional.of(Major.builder().id(program.getMajorId())
            .status("ACTIVE").specializations(List.of(new Specialization("AA", "Track A", "ACTIVE"),
                new Specialization("BB", "Track B", "ACTIVE"))).build()));
        when(repository.findById(program.getId())).thenReturn(Optional.of(program));
        return program;
    }

    private Course course(String code, int credits) {
        return Course.builder().courseCode(code).credits(credits).status("ACTIVE").build();
    }

    private Curriculum.SpecializationTree tree(String specialized, int semester) {
        return new Curriculum.SpecializationTree(new ArrayList<>(List.of(new Curriculum.SemesterItem(semester,
            new ArrayList<>(List.of(item("COMMON"), item(specialized), item("E")))))),
            List.of(group("COMMON", "MANDATORY_COMMON", 3, "COMMON"),
                group("SPEC", "MANDATORY_SPECIALIZATION", specialized.equals("B") ? 6 : 3, specialized),
                group("ELECTIVE", "ELECTIVE_FREE", 3, "E")));
    }

    private Curriculum.CurriculumCourseItem item(String code) {
        return Curriculum.CurriculumCourseItem.builder().courseCode(code).type(code.equals("E") ? "ELECTIVE" : "MANDATORY").build();
    }

    private Curriculum.RequirementGroupItem group(String code, String kind, int credits, String... codes) {
        return Curriculum.RequirementGroupItem.builder().groupCode(code).kind(kind).requiredCredits(credits)
            .eligibleCourseCodes(List.of(codes)).build();
    }

    @Test
    void sharedCourseMayBeInDifferentSemestersAndCreditPlansDifferByTrack() {
        Curriculum program = fixture();
        var validation = validator.validate(program);
        assertTrue(validation.valid(), validation.errors().toString());
        assertEquals(Map.of("AA", 9, "BB", 12), validation.computedCreditsBySpecialization());
        assertEquals(Map.of("requiredTotal", 9, "mandatoryCredits", 6, "electiveCredits", 3, "graduationCredits", 0),
            validation.creditSummariesBySpecialization().get("AA"));
        assertEquals(1, CurriculumTracks.select(program, "AA").getSemesters().getFirst().getSemesterNumber());
        assertEquals(2, CurriculumTracks.select(program, "BB").getSemesters().getFirst().getSemesterNumber());
        assertEquals(Map.of("AA", 9, "BB", 12), creditPlan.calculate(program).orElseThrow().totalsByTrack());
        verify(courses, never()).save(any(Course.class));
    }

    @Test
    void validationCreditPreviewSeparatesElectiveQuotaFromGraduationOptions() {
        Curriculum program = fixture();
        var tree = program.getSpecializationTrees().get("AA");
        tree.getSemesters().getFirst().getCourses().add(item("PROJECT"));
        var groups = new ArrayList<>(tree.getRequirementGroups());
        groups.add(Curriculum.RequirementGroupItem.builder().groupCode("FINAL").kind("GRADUATION")
            .requiredCredits(6).completionOptions(List.of(Curriculum.CompletionOptionItem.builder()
                .optionCode("PROJECT").courseCodes(List.of("PROJECT")).build())).build());
        tree.setRequirementGroups(groups);
        var catalogue = List.of(course("COMMON", 3), course("A", 3), course("B", 6), course("E", 3), course("PROJECT", 6));
        when(courses.findAll()).thenReturn(catalogue);
        when(courses.findByCourseCodeIn(anyList())).thenAnswer(call -> {
            List<String> codes = call.getArgument(0);
            return catalogue.stream().filter(c -> codes.contains(c.getCourseCode())).toList();
        });
        var result = validator.validate(program);
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(Map.of("requiredTotal", 15, "mandatoryCredits", 6, "electiveCredits", 3, "graduationCredits", 6),
            result.creditSummariesBySpecialization().get("AA"));
        assertEquals("DRAFT", program.getStatus());
        verifyNoInteractions(mongo);
    }

    @Test
    void invalidMandatoryQuotaReportsTheBackendCreditTotalWithoutPublishing() {
        Curriculum program = fixture();
        program.getSpecializationTrees().get("AA").getRequirementGroups().getFirst().setRequiredCredits(999);
        var result = validator.validate(program);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("COMMON") && e.contains("Backend xác định 3 tín chỉ")));
        assertFalse(result.creditSummariesBySpecialization().containsKey("AA"));
        verifyNoInteractions(mongo);
    }

    @Test
    void duplicateCourseInOneTreeBlocksWholePublication() {
        Curriculum program = fixture();
        program.getSpecializationTrees().get("BB").getSemesters().getFirst().getCourses().add(item("COMMON"));
        assertFalse(validator.validate(program).valid());
        assertThrows(IllegalArgumentException.class, () -> publication.publish(program.getId().toHexString()));
        verifyNoInteractions(mongo);
    }

    @Test
    void missingTreeAndForeignSpecializationBlockPublication() {
        Curriculum program = fixture();
        program.getSpecializationTrees().remove("BB");
        assertFalse(validator.validate(program).valid());
        program.getSpecializationTrees().put("BB", tree("B", 2));
        when(majors.findById(program.getMajorId())).thenReturn(Optional.of(Major.builder().status("ACTIVE")
            .specializations(List.of(new Specialization("AA", "A", "ACTIVE"))).build()));
        assertTrue(validator.validate(program).errors().stream().anyMatch(error -> error.contains("BB")));
    }

    @Test
    void invalidSemesterOrPrerequisiteInAnotherTreeBlocksPublication() {
        Curriculum program = fixture();
        var semester = program.getSpecializationTrees().get("AA").getSemesters().getFirst();
        semester.setSemesterNumber(0);
        assertFalse(validator.validate(program).valid());
        semester.setSemesterNumber(1);
        semester.getCourses().getFirst().setPrerequisiteCourseCodes(List.of("B"));
        assertFalse(validator.validate(program).valid());
    }

    @Test
    void electivesCannotReuseMandatoryCreditEvenIfEachGroupHasEnoughCourses() {
        Curriculum program = fixture();
        program.getSpecializationTrees().get("AA").getRequirementGroups().getLast().setEligibleCourseCodes(List.of("COMMON"));
        assertFalse(validator.validate(program).valid());
    }

    @Test
    void twoElectiveGroupsInOneTreeBlockPublicationEvenWhenTheSecondDoesNotCountCredits() {
        Curriculum program = fixture();
        var tree = program.getSpecializationTrees().get("AA");
        var groups = new ArrayList<>(tree.getRequirementGroups());
        groups.add(group("EXTRA", "ELECTIVE_SPECIALIZATION", 3, "E").toBuilder()
            .countsTowardGraduation(false).build());
        tree.setRequirementGroups(groups);
        assertFalse(validator.validate(program).valid());
        assertThrows(IllegalArgumentException.class, () -> publication.publish(program.getId().toHexString()));
        verifyNoInteractions(mongo);
    }

    @Test
    void electivePoolCannotOverlapAGraduationOptionOrUseMandatoryCourseType() {
        Curriculum program = fixture();
        var tree = program.getSpecializationTrees().get("AA");
        tree.getSemesters().getFirst().getCourses().getLast().setType("MANDATORY");
        assertTrue(validator.validate(program).errors().stream().anyMatch(e -> e.contains("ELECTIVE")));
        tree.getSemesters().getFirst().getCourses().getLast().setType("ELECTIVE");
        var groups = new ArrayList<>(tree.getRequirementGroups());
        groups.add(Curriculum.RequirementGroupItem.builder().groupCode("FINAL").kind("GRADUATION")
            .requiredCredits(3).completionOptions(List.of(Curriculum.CompletionOptionItem.builder()
                .optionCode("PROJECT").courseCodes(List.of("E")).build())).build());
        tree.setRequirementGroups(groups);
        assertFalse(validator.validate(program).valid());
    }

    @Test
    void aLargeElectivePoolWithSurplusDoesNotRequireSubsetEnumeration() {
        Curriculum program = fixture();
        var tree = program.getSpecializationTrees().get("AA");
        List<String> codes = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "E" + i).toList();
        tree.getRequirementGroups().getLast().setEligibleCourseCodes(codes);
        tree.getRequirementGroups().getLast().setRequiredCredits(119);
        var items = new ArrayList<>(List.of(item("COMMON"), item("A")));
        codes.forEach(code -> items.add(Curriculum.CurriculumCourseItem.builder().courseCode(code).type("ELECTIVE").build()));
        tree.getSemesters().getFirst().setCourses(items);
        var catalog = new ArrayList<>(List.of(course("COMMON", 3), course("A", 3), course("B", 6), course("E", 3)));
        codes.forEach(code -> catalog.add(course(code, 3)));
        when(courses.findByCourseCodeIn(anyList())).thenAnswer(call -> {
            List<String> requested = call.getArgument(0);
            return catalog.stream().filter(c -> requested.contains(c.getCourseCode())).toList();
        });
        var result = validator.validate(program);
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(125, result.computedCreditsBySpecialization().get("AA"));
    }

    @Test
    void publicationStoresAllTrackPlansAtomicallyAndChecksDraftRevision() {
        Curriculum program = fixture();
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Curriculum.class)))
            .thenReturn(program);
        publication.publish(program.getId().toHexString());
        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(Curriculum.class));
        assertEquals("DRAFT", query.getValue().getQueryObject().get("status"));
        assertEquals(0L, query.getValue().getQueryObject().get("revision"));
        var fields = update.getValue().getUpdateObject().get("$set", org.bson.Document.class);
        assertEquals("ACTIVE", fields.get("status"));
        var plans = (Map<?, ?>) fields.get("creditPlansBySpecialization");
        assertEquals(9, ((Curriculum.TrackCreditPlan) plans.get("AA")).getRequiredTotal());
        assertEquals(12, ((Curriculum.TrackCreditPlan) plans.get("BB")).getRequiredTotal());
        program.setStatus("ACTIVE");
        assertThrows(IllegalStateException.class, () -> publication.publish(program.getId().toHexString()));
    }

    @Test
    void partialDraftTreesCanBeSavedButCannotBePublished() {
        Curriculum program = fixture();
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Curriculum.class)))
            .thenReturn(program);
        new CurriculumTreeService(repository, mongo).save(program.getId().toHexString(), "AA",
            new Curriculum.SpecializationTree());
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(Curriculum.class));
        var trees = (Map<?, ?>) update.getValue().getUpdateObject().get("$set", org.bson.Document.class).get("specializationTrees");
        assertNotNull(trees.get("BB"));
        assertTrue(((Curriculum.SpecializationTree) trees.get("AA")).getRequirementGroups().isEmpty());
        program.getSpecializationTrees().put("AA", new Curriculum.SpecializationTree());
        assertFalse(validator.validate(program).valid());
    }

    @Test
    void editingLegacyDraftUsesStatusAndRevisionGuardWithoutReinsertingTheDocument() throws Exception {
        Curriculum program = fixture();
        program.setRevision(null);
        MongoMappingContext context = new MongoMappingContext();
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.afterPropertiesSet();
        when(mongo.getConverter()).thenReturn(converter);
        var service = new CurriculumServiceImpl(repository, majors, courses, mongo, logs,
            mock(vn.edu.huflit.portal.notification.repository.NotificationRepository.class),
            mock(vn.edu.huflit.portal.repository.UserRepository.class), creditPlan);
        var input = vn.edu.huflit.portal.curriculum.dto.UpdateCurriculumRequest.builder()
            .curriculumName("Updated legacy draft").build();
        assertThrows(IllegalStateException.class, () -> service.updateCurriculum(program.getId().toHexString(), input));
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(Update.class), any(FindAndModifyOptions.class), eq(Curriculum.class));
        assertEquals("DRAFT", query.getValue().getQueryObject().get("status"));
        assertTrue(query.getValue().getQueryObject().containsKey("revision"));
        assertNull(query.getValue().getQueryObject().get("revision"));
        verify(repository, never()).insert(any(Curriculum.class));
        verify(repository, never()).save(any(Curriculum.class));
    }

    @Test
    void firstTreeSaveMaterializesIndependentLegacyTracks() {
        Curriculum program = fixture();
        program.setSpecializationTrees(Map.of());
        program.setSemesters(List.of(new Curriculum.SemesterItem(1, List.of(item("COMMON")))));
        program.setRequirementGroups(List.of(group("CORE", "MANDATORY_COMMON", 3, "COMMON")));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Curriculum.class)))
            .thenReturn(program);
        var changed = tree("A", 4);
        new CurriculumTreeService(repository, mongo).save(program.getId().toHexString(), "AA", changed);
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(Curriculum.class));
        var trees = (Map<?, ?>) update.getValue().getUpdateObject().get("$set", org.bson.Document.class).get("specializationTrees");
        assertEquals(changed, trees.get("AA"));
        assertEquals(1, ((Curriculum.SpecializationTree) trees.get("BB")).getSemesters().getFirst().getSemesterNumber());
        assertNotSame(program.getSemesters().getFirst().getCourses().getFirst(),
            ((Curriculum.SpecializationTree) trees.get("BB")).getSemesters().getFirst().getCourses().getFirst());
    }

    @Test
    void cloneCreatesIndependentTreesAndIncrementsTheVersionWithoutChangingTheSource() throws Exception {
        Curriculum program = fixture();
        program.setVersion(4); program.setCurriculumName("IT curriculum"); program.setStatus("ACTIVE");
        MongoMappingContext context = new MongoMappingContext();
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.afterPropertiesSet();
        when(mongo.getConverter()).thenReturn(converter);
        when(repository.findByMajorId(program.getMajorId())).thenReturn(List.of(program));
        when(repository.insert(any(Curriculum.class))).thenAnswer(call -> call.getArgument(0));
        var service = new CurriculumServiceImpl(repository, majors, courses, mongo, logs,
            mock(vn.edu.huflit.portal.notification.repository.NotificationRepository.class),
            mock(vn.edu.huflit.portal.repository.UserRepository.class), creditPlan);
        var input = vn.edu.huflit.portal.curriculum.dto.CloneCurriculumRequest.builder()
            .newCurriculumCode("IT-2026-V5").build();

        var cloned = service.cloneCurriculum(program.getId().toHexString(), input);
        assertEquals(5, cloned.getVersion());
        assertEquals("DRAFT", cloned.getStatus());
        cloned.getSpecializationTrees().get("AA").getSemesters().getFirst().setSemesterNumber(9);
        assertEquals(1, program.getSpecializationTrees().get("AA").getSemesters().getFirst().getSemesterNumber());
        assertEquals("ACTIVE", program.getStatus());
        assertEquals(4, program.getVersion());
        input.setNewCohort("K2027");
        input.setNewCurriculumCode("IT-2027");
        var nextCohort = service.cloneCurriculum(program.getId().toHexString(), input);
        assertEquals(1, nextCohort.getVersion());
        assertEquals("K2027", nextCohort.getCohort());
        nextCohort.getSpecializationTrees().get("AA").getRequirementGroups().getFirst().setRequiredCredits(6);
        assertEquals(3, program.getSpecializationTrees().get("AA").getRequirementGroups().getFirst().getRequiredCredits());
    }

    @Test
    void legacyScopesStillResolveAndPublishedTreesCannotBeSaved() {
        Curriculum program = fixture();
        program.setSpecializationTrees(Map.of());
        program.setSemesters(List.of(new Curriculum.SemesterItem(1, List.of(item("COMMON"),
            item("A").toBuilder().specializationCodes(List.of("AA")).build(),
            item("B").toBuilder().specializationCodes(List.of("BB")).build()))));
        assertEquals(List.of("COMMON", "A"), CurriculumTracks.select(program, "AA").getSemesters().getFirst()
            .getCourses().stream().map(Curriculum.CurriculumCourseItem::getCourseCode).toList());
        assertThrows(IllegalArgumentException.class, () -> CurriculumTracks.select(program, "UNKNOWN"));
        program.setStatus("ACTIVE");
        assertThrows(IllegalArgumentException.class, () ->
            new CurriculumTreeService(repository, mongo).save(program.getId().toHexString(), "AA", tree("A", 1)));
    }
}
