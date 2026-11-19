package vn.edu.huflit.portal.integration;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.impl.GradeConversionServiceImpl;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.service.*;
import vn.edu.huflit.portal.entity.*;
import vn.edu.huflit.portal.major.repository.MajorRepository;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;
import vn.edu.huflit.portal.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/** Imports only JSON into a newly-created, explicitly-named local test database. Never executes bundled scripts. */
final class BigDataCompatibilityAudit {
    static void run(Path archive, MongoTemplate mongo, Path output) throws Exception {
        if (!mongo.getDb().getName().startsWith("portal_integration_") || !mongo.getDb().getName().endsWith("_bigdata"))
            throw new IllegalStateException("BigData audit requires a separate integration database");
        if (mongo.getDb().listCollectionNames().first() != null) throw new IllegalStateException("Audit database must be new and empty");
        Map<String,Object> report = new LinkedHashMap<>();
        Map<String,Integer> counts = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            for (String collection : List.of("majors", "courses", "curriculums", "users", "academicRecords", "replacementRules")) {
                var entry = zip.getEntry(collection + ".json");
                if (entry == null) throw new IllegalArgumentException("Missing dataset collection: " + collection);
                String text;
                try (var input = zip.getInputStream(entry)) { text = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\uFEFF", ""); }
                List<Document> documents = Document.parse("{\"items\":" + text + "}").getList("items", Document.class);
                if (!documents.isEmpty()) mongo.getCollection(collection).insertMany(documents);
                counts.put(collection, documents.size());
            }
            List<Map<String,Object>> scripts = new ArrayList<>();
            for (String filename : List.of("seed-portal-atlas.js", "verify-portal-atlas.js")) {
                var entry = zip.getEntry(filename);
                if (entry == null) continue;
                String text;
                try (var input = zip.getInputStream(entry)) { text = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                scripts.add(Map.of("file", filename, "executed", false,
                    "destructiveOperationPresent", text.matches("(?s).*(deleteMany|dropDatabase|dropCollection|\\.drop\\s*\\().*"),
                    "checksEmptyCollections", text.contains("countDocuments") || text.contains("estimatedDocumentCount"),
                    "checksDatabaseName", text.contains("db.getName()") && (text.contains("startsWith") || text.contains("match(") || text.contains("test"))));
            }
            report.put("scriptInspection", scripts);
        }
        report.put("collectionCounts", counts);
        MongoRepositoryFactory factory = new MongoRepositoryFactory(mongo);
        var curricula = factory.getRepository(CurriculumRepository.class);
        var courses = factory.getRepository(CourseRepository.class);
        var majors = factory.getRepository(MajorRepository.class);
        var users = factory.getRepository(UserRepository.class);
        var records = factory.getRepository(AcademicRecordRepository.class);
        var rules = factory.getRepository(ReplacementRuleRepository.class);
        var evidence = factory.getRepository(RecognitionEvidenceRepository.class);
        var validator = new CurriculumRequirementValidationService(curricula, courses, majors);
        var applicability = new ReplacementApplicability(rules);
        var evaluation = new CurriculumEvaluationService(curricula, courses, records, applicability, new GradeConversionServiceImpl(), evidence);
        var programs = curricula.findAll();
        var programById = programs.stream().collect(Collectors.toMap(Curriculum::getId, Function.identity()));
        var students = users.findAll().stream().filter(u -> "STUDENT".equals(u.getRole())).toList();
        var studentById = students.stream().collect(Collectors.toMap(User::getId, Function.identity()));
        var catalogCodes = courses.findAll().stream().map(c -> c.getCourseCode()).collect(Collectors.toSet());
        List<Map<String,Object>> validation = new ArrayList<>();
        int validPrograms = 0, missingTrees = 0, invalidAssignments = 0;
        for (var program : programs) {
            if (CurriculumTracks.trees(program).isEmpty()) missingTrees++;
            var result = validator.validate(program);
            if (result.valid()) validPrograms++;
            validation.add(Map.of("curriculumCode", program.getCurriculumCode(), "valid", result.valid(), "errors", result.errors()));
        }
        int missingExplicitTrees = 0, invalidElectiveGroupCounts = 0;
        for (var program : programs) for (var specialization : CurriculumTracks.safe(program.getSpecializations())) {
            if (!CurriculumTracks.trees(program).containsKey(specialization.getCode())) missingExplicitTrees++;
            var selected = CurriculumTracks.select(program, specialization.getCode());
            if (CurriculumTracks.safe(selected.getRequirementGroups()).stream()
                .filter(g -> g.getKind() != null && g.getKind().startsWith("ELECTIVE")).count() != 1) invalidElectiveGroupCounts++;
        }
        Map<ObjectId,Set<String>> treeCourses = new HashMap<>();
        Map<ObjectId,List<ReplacementApplicability.Window>> windows = new HashMap<>();
        Map<String,Integer> cohorts = new TreeMap<>();
        for (var student : students) {
            cohorts.merge(student.getCohort(), 1, Integer::sum);
            try {
                var selected = CurriculumTracks.forStudent(programById.get(student.getCurriculumId()), student);
                Set<String> codes = new HashSet<>();
                for (var semester : CurriculumTracks.safe(selected.getSemesters()))
                    for (var course : CurriculumTracks.safe(semester.getCourses())) codes.add(course.getCourseCode());
                treeCourses.put(student.getId(), codes);
                windows.put(student.getId(), applicability.windows(student, selected, Instant.now()));
            } catch (RuntimeException invalid) { invalidAssignments++; }
        }
        int missingAttemptIds = 0, missingCompletion = 0, futureCompletion = 0, unknownReferences = 0, outsideScope = 0, invalidScores = 0, duplicateAttemptIds = 0;
        Map<ObjectId,Set<String>> attemptIds = new HashMap<>();
        for (var record : records.findAll()) {
            var student = studentById.get(record.getStudentId());
            if (student == null || !catalogCodes.contains(record.getCourseCode()) || !programById.containsKey(record.getCurriculumId())) unknownReferences++;
            for (var attempt : CurriculumTracks.safe(record.getAttempts())) {
                if (attempt.getAttemptId() == null || attempt.getAttemptId().isBlank()) missingAttemptIds++;
                else if (!attemptIds.computeIfAbsent(record.getStudentId(), ignored -> new HashSet<>()).add(attempt.getAttemptId())) duplicateAttemptIds++;
                if (attempt.getScore10() == null || !Double.isFinite(attempt.getScore10()) || attempt.getScore10() < 0 || attempt.getScore10() > 10) invalidScores++;
                if (attempt.getCompletedAt() == null) missingCompletion++;
                else if (attempt.getCompletedAt().toInstant().isAfter(Instant.now())) futureCompletion++;
                if (!treeCourses.getOrDefault(record.getStudentId(), Set.of()).contains(record.getCourseCode())
                    && windows.getOrDefault(record.getStudentId(), List.of()).stream().noneMatch(window ->
                        attempt.getCompletedAt() != null && window.verifies(attempt.getCompletedAt().toInstant())
                            && window.rule().getNewCourseCodes().contains(record.getCourseCode()))) outsideScope++;
            }
        }
        int evaluated = 0, complete = 0;
        Map<String,Integer> evaluationErrors = new TreeMap<>();
        for (var student : students) {
            try {
                var result = evaluation.evaluate(student); evaluated++;
                if (Boolean.TRUE.equals(result.get("requirementsComplete"))) complete++;
            } catch (RuntimeException invalid) { evaluationErrors.merge(invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage(), 1, Integer::sum); }
        }
        report.put("students", students.size()); report.put("cohorts", cohorts);
        report.put("curriculumVersions", programs.stream().collect(Collectors.groupingBy(
            p -> String.valueOf(p.getVersion()), TreeMap::new, Collectors.counting())));
        report.put("replacementRuleTypes", rules.findAll().stream().collect(Collectors.groupingBy(
            r -> String.valueOf(r.getRuleType()), TreeMap::new, Collectors.counting())));
        report.put("curriculaMissingSpecializationTrees", missingTrees);
        report.put("missingExplicitSpecializationTrees", missingExplicitTrees);
        report.put("projectedTreesWithInvalidElectiveGroupCount", invalidElectiveGroupCounts);
        report.put("publicationValidCurricula", validPrograms); report.put("curriculumValidation", validation);
        report.put("invalidAssignments", invalidAssignments); report.put("unknownRecordReferences", unknownReferences);
        report.put("missingAttemptIds", missingAttemptIds); report.put("duplicateAttemptIds", duplicateAttemptIds);
        report.put("missingCompletedAt", missingCompletion); report.put("futureCompletedAt", futureCompletion);
        report.put("invalidScores", invalidScores); report.put("attemptsOutsideCurrentTreeOrReplacementWindow", outsideScope);
        report.put("evaluatedStudents", evaluated); report.put("requirementsComplete", complete); report.put("evaluationErrors", evaluationErrors);
        report.put("recognitionEvidenceCreatedByAudit", evidence.count());
        boolean expectedPopulation = students.size() == 1000 && programs.size() == 12
            && cohorts.keySet().equals(Set.of("K2023", "K2024", "K2025", "K2026")) && cohorts.values().stream().allMatch(n -> n == 250);
        report.put("readyForNewSchemaImport", expectedPopulation && evaluated == students.size() && evaluationErrors.isEmpty()
            && missingTrees == 0 && missingExplicitTrees == 0 && invalidElectiveGroupCounts == 0 && validPrograms == programs.size()
            && invalidAssignments == 0 && outsideScope == 0 && missingAttemptIds == 0 && missingCompletion == 0
            && futureCompletion == 0 && invalidScores == 0 && unknownReferences == 0 && duplicateAttemptIds == 0);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        assertEquals(0, evidence.count(), "Read-only evaluation must not create recognition evidence");
        assertEquals(counts.get("academicRecords").longValue(), records.count());
        assertEquals(counts.get("curriculums").longValue(), curricula.count());
    }
}
