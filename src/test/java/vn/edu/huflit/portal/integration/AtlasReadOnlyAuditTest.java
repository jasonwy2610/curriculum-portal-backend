package vn.edu.huflit.portal.integration;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.event.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.huflit.portal.academic.repository.AcademicRecordRepository;
import vn.edu.huflit.portal.academic.service.impl.GradeConversionServiceImpl;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.service.*;
import vn.edu.huflit.portal.entity.*;
import vn.edu.huflit.portal.major.model.Major;
import vn.edu.huflit.portal.major.repository.MajorRepository;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;
import vn.edu.huflit.portal.repository.UserRepository;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in audit. Reads only the named database; evaluates a bounded snapshot through production services. */
@EnabledIfSystemProperty(named = "portal.atlasReadOnly", matches = "true")
class AtlasReadOnlyAuditTest {
    private final Map<String,Object> report = new LinkedHashMap<>();
    private final Map<String,Integer> violations = new TreeMap<>();
    private final List<Map<String,Object>> examples = new ArrayList<>();
    private final Map<String,Long> commands = new TreeMap<>();
    private int writesObserved;

    static void auditLocalDataset(MongoDatabase database) throws Exception {
        if (!database.getName().startsWith("portal_integration_") || !database.getName().endsWith("_bigdata"))
            throw new IllegalArgumentException("Local audit requires the isolated integration dataset");
        var audit = new AtlasReadOnlyAuditTest();
        audit.report.put("databaseVerified", database.getName());
        audit.report.put("mode", "LOCAL_ONLY; not an Atlas result");
        audit.inspect(database);
        Files.writeString(Path.of("target", "local-v2-readonly.json"), JsonMapper.builder().build()
            .writerWithDefaultPrettyPrinter().writeValueAsString(audit.report));
        assertEquals("DATA_CHECKS_PASS_BROWSER_E2E_PENDING", audit.report.get("verdict"), "See the local audit report for data violations");
    }

    @Test void auditTheExplicitAtlasDatabaseWithoutApplicationStartupOrWriteRepositories() throws Exception {
        ((ch.qos.logback.classic.Logger)LoggerFactory.getLogger("org.mongodb.driver")).setLevel(ch.qos.logback.classic.Level.OFF);
        String configured = System.getenv("PORTAL_ATLAS_READ_URI");
        assertNotNull(configured, "Configure the read-only Atlas connection outside source code");
        String expectedDatabase = System.getenv("PORTAL_ATLAS_READ_DATABASE");
        assertTrue(Set.of("portal_huflit_v2", "training_program_db").contains(String.valueOf(expectedDatabase)),
            "Explicitly select a user-authorized audit database outside source code");
        ConnectionString connection;
        try { connection = new ConnectionString(configured); }
        catch (RuntimeException invalid) { throw new AssertionError("Invalid connection configuration; sensitive details omitted"); }
        assertEquals(expectedDatabase, connection.getDatabase(), "Stop: configured database must match the explicit target");
        Set<String> connectionFailureTypes = java.util.concurrent.ConcurrentHashMap.newKeySet();
        MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(connection)
            .applicationName("PortalReadOnlyAudit").retryWrites(false).readConcern(ReadConcern.MAJORITY)
            .readPreference(ReadPreference.primary()).applyToConnectionPoolSettings(p -> p.maxSize(2))
            .applyToClusterSettings(c -> c.serverSelectionTimeout(20, TimeUnit.SECONDS).addClusterListener(new ClusterListener() {
                @Override public void clusterDescriptionChanged(ClusterDescriptionChangedEvent event) {
                    for (var server : event.getNewDescription().getServerDescriptions())
                        for (Throwable failure = server.getException(); failure != null; failure = failure.getCause())
                            connectionFailureTypes.add(failure.getClass().getSimpleName());
                }
            }))
            .addCommandListener(new CommandListener() {
                @Override public synchronized void commandStarted(CommandStartedEvent event) {
                    String name = event.getCommandName(); commands.merge(name, 1L, Long::sum);
                    if (Set.of("insert", "update", "delete", "findAndModify", "createIndexes", "drop", "dropDatabase", "renameCollection").contains(name)) writesObserved++;
                }
            }).build();
        try (MongoClient client = MongoClients.create(settings)) {
            MongoDatabase database = client.getDatabase(expectedDatabase);
            assertEquals(expectedDatabase, database.getName());
            database.runCommand(new Document("ping", 1));
            report.put("databaseVerified", expectedDatabase); report.put("mode", "read-only; sequential snapshot evaluation");
            inspect(database);
            assertEquals(0, writesObserved, "Atlas audit must never issue write commands");
            assertEquals("DATA_CHECKS_PASS_BROWSER_E2E_PENDING", report.get("verdict"), "See the Atlas audit report for data violations");
        } catch (MongoException unavailable) {
            throw new AssertionError("Atlas read-only connection/query failed (" + unavailable.getClass().getSimpleName() + "); connection details omitted");
        } finally {
            report.put("observedCommands", commands); report.put("writeCommandsObserved", writesObserved);
            report.put("connectionFailureTypes", new TreeSet<>(connectionFailureTypes));
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target", "atlas-readonly.json"), JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    private void inspect(MongoDatabase database) throws Exception {
        var context = new MongoMappingContext(); context.setAutoIndexCreation(false); context.afterPropertiesSet();
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context); converter.afterPropertiesSet();
        Map<String,List<Document>> raw = new LinkedHashMap<>();
        Map<String,Long> counts = new LinkedHashMap<>();
        Map<String,Double> readMs = new LinkedHashMap<>();
        for (String name : List.of("majors", "courses", "curriculums", "users", "academicRecords", "replacementRules", "recognitionEvidence")) {
            long start = System.nanoTime();
            counts.put(name, database.getCollection(name).countDocuments());
            raw.put(name, database.getCollection(name).find().batchSize(500).into(new ArrayList<>()));
            readMs.put(name, elapsed(start));
        }
        report.put("counts", counts); report.put("snapshotReadMs", readMs);
        for (var expected : Map.of("majors", 3L, "courses", 207L, "curriculums", 12L,
                "users", 1001L, "academicRecords", 13710L, "replacementRules", 9L).entrySet())
            if (!Objects.equals(counts.get(expected.getKey()), expected.getValue())) issue("countMismatch:" + expected.getKey(), counts.get(expected.getKey()));
        var majors = map(raw.get("majors"), Major.class, converter);
        var courses = map(raw.get("courses"), Course.class, converter);
        var programs = map(raw.get("curriculums"), Curriculum.class, converter);
        var users = map(raw.get("users"), User.class, converter);
        var records = map(raw.get("academicRecords"), AcademicRecord.class, converter);
        var rules = map(raw.get("replacementRules"), ReplacementRule.class, converter);
        var evidence = map(raw.get("recognitionEvidence"), RecognitionEvidence.class, converter);
        for (var u : users) {
            if (!Set.of("ADMIN", "STUDENT").contains(String.valueOf(u.getRole()))) issue("invalidUserRole", u.getId());
            if (!Set.of("ACTIVE", "INACTIVE").contains(String.valueOf(u.getStatus()))) issue("invalidUserStatus", u.getId());
        }
        for (var r : records) if (!Set.of("PASSED", "FAILED", "NOT_STUDIED").contains(String.valueOf(r.getStatus()))) issue("invalidRecordStatus", r.getId());
        for (var p : programs) if (p.getVersion() == null || p.getVersion() < 1
            || !Set.of("DRAFT", "ACTIVE", "ARCHIVED", "INACTIVE").contains(String.valueOf(p.getStatus()))) issue("invalidCurriculumVersionOrStatus", p.getId());
        var students = users.stream().filter(u -> "STUDENT".equals(u.getRole())).toList();
        var gpaOracle = new IndependentGpaAudit();
        if (Boolean.getBoolean("portal.acceptanceAudit")) {
            var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
            var samples = new ArrayList<User>();
            users.stream().filter(u -> "ADMIN".equals(u.getRole())).limit(1).forEach(samples::add);
            for (String cohort : List.of("K2023", "K2024", "K2025", "K2026"))
                students.stream().filter(u -> cohort.equals(u.getCohort())).limit(2).forEach(samples::add);
            int matching = 0;
            for (var sample : samples) if (encoder.matches(System.getenv("PORTAL_BIGDATA_SAMPLE_PASSWORD"), sample.getPasswordHash())) matching++;
            report.put("bcryptSamples", Map.of("tested", samples.size(), "matched", matching, "adminIncluded", samples.stream().anyMatch(u -> "ADMIN".equals(u.getRole()))));
            if (matching != samples.size() || samples.size() != 9) issue("bcryptSampleMismatch", "sample authentication");
        }
        var byProgram = programs.stream().collect(Collectors.toMap(Curriculum::getId, Function.identity()));
        var byStudent = students.stream().collect(Collectors.toMap(User::getId, Function.identity()));
        var byCourse = courses.stream().collect(Collectors.toMap(Course::getCourseCode, Function.identity(), (a,b) -> { issue("duplicateCourseCode", a.getCourseCode()); return a; }));
        Set<String> studentCodes = new HashSet<>();
        students.forEach(s -> { if (s.getStudentCode() == null || !studentCodes.add(s.getStudentCode())) issue("missingOrDuplicateStudentCode", s.getId()); });
        var programRepo = snapshot(CurriculumRepository.class, programs);
        var ruleRepo = snapshot(ReplacementRuleRepository.class, rules);
        var validator = new CurriculumRequirementValidationService(programRepo, snapshot(CourseRepository.class, courses), snapshot(MajorRepository.class, majors));
        var applicability = new ReplacementApplicability(ruleRepo);
        var evaluation = new CurriculumEvaluationService(programRepo, snapshot(CourseRepository.class, courses),
            snapshot(AcademicRecordRepository.class, records), applicability, new GradeConversionServiceImpl(), snapshot(RecognitionEvidenceRepository.class, evidence));
        var transcripts = new vn.edu.huflit.portal.academic.service.StudentTranscriptService(
            snapshot(AcademicRecordRepository.class, records), snapshot(CourseRepository.class, courses), evaluation, new GradeConversionServiceImpl(), programRepo);
        int[] transcriptCounts = new int[4]; // students, attempts, missing catalog fields, GPA differences
        List<Map<String,Object>> validation = new ArrayList<>(); int validPrograms = 0, treeCount = 0, validTrees = 0;
        for (var p : programs) {
            treeCount += CurriculumTracks.trees(p).size();
            var checked = validator.validate(p); if (checked.valid()) validPrograms++;
            for (var spec : CurriculumTracks.safe(p.getSpecializations())) {
                boolean treeValid = CurriculumTracks.trees(p).containsKey(spec.getCode())
                    && checked.errors().stream().noneMatch(e -> e.startsWith("[" + spec.getCode() + "]"));
                if (treeValid && checked.valid()) validTrees++;
            }
            validation.add(Map.of("curriculumCode", p.getCurriculumCode(), "cohort", p.getCohort(), "version", p.getVersion(),
                "status", p.getStatus(), "valid", checked.valid(), "errors", checked.errors(), "credits", checked.creditSummariesBySpecialization()));
        }
        report.put("curriculumValidation", validation); report.put("validCurricula", validPrograms);
        report.put("specializationTrees", treeCount); report.put("validTrees", validTrees);
        report.put("students", students.size()); report.put("specializations", majors.stream().mapToInt(m -> m.getSpecializations() == null ? 0 : m.getSpecializations().size()).sum());
        var cohorts = students.stream().collect(Collectors.groupingBy(s -> String.valueOf(s.getCohort()), TreeMap::new, Collectors.counting()));
        report.put("cohorts", cohorts);
        if (!cohorts.keySet().equals(Set.of("K2023", "K2024", "K2025", "K2026")) || cohorts.values().stream().anyMatch(n -> n != 250)) issue("cohortPopulationMismatch", "expected 250 per cohort");
        Map<ObjectId,List<AcademicRecord>> byRecords = records.stream().collect(Collectors.groupingBy(AcademicRecord::getStudentId));
        Map<ObjectId,List<ReplacementApplicability.Window>> windows = new HashMap<>();
        Map<ObjectId,Set<String>> courseScopes = new HashMap<>();
        for (var s : students) try {
            var selected = CurriculumTracks.forStudent(byProgram.get(s.getCurriculumId()), s);
            Set<String> codes = selected.getSemesters().stream().flatMap(t -> t.getCourses().stream()).map(Curriculum.CurriculumCourseItem::getCourseCode).collect(Collectors.toSet());
            courseScopes.put(s.getId(), codes); windows.put(s.getId(), applicability.windows(s, selected, Instant.now()));
        } catch (RuntimeException invalid) { issue("invalidStudentAssignment", s.getId()); }
        Map<ObjectId,Set<String>> attemptIds = new HashMap<>(); Set<ObjectId> retakingStudents = new HashSet<>(); int retakeRecords = 0;
        for (var record : records) {
            if (!byStudent.containsKey(record.getStudentId()) || !byProgram.containsKey(record.getCurriculumId()) || !byCourse.containsKey(record.getCourseCode())) issue("unknownRecordReference", record.getId());
            if (record.getAttempts() != null && record.getAttempts().size() > 1) { retakeRecords++; retakingStudents.add(record.getStudentId()); }
            for (var a : CurriculumTracks.safe(record.getAttempts())) {
                if (a.getAttemptId() == null || a.getAttemptId().isBlank()) issue("missingAttemptId", record.getId());
                else if (!attemptIds.computeIfAbsent(record.getStudentId(), x -> new HashSet<>()).add(a.getAttemptId())) issue("duplicateAttemptId", record.getId());
                if (a.getCompletedAt() == null) issue("missingCompletedAt", record.getId());
                else if (a.getCompletedAt().toInstant().isAfter(Instant.now())) issue("futureCompletedAt", record.getId());
                if (a.getScore10() == null || !Double.isFinite(a.getScore10()) || a.getScore10() < 0 || a.getScore10() > 10) issue("invalidScore", record.getId());
                if (a.getSemesterCode() == null || !a.getSemesterCode().matches("20[0-9]{2}-[123]")) issue("invalidSemester", record.getId());
                if (!courseScopes.getOrDefault(record.getStudentId(), Set.of()).contains(record.getCourseCode())
                    && windows.getOrDefault(record.getStudentId(), List.of()).stream().noneMatch(w -> a.getCompletedAt() != null
                        && w.verifies(a.getCompletedAt().toInstant()) && w.rule().getNewCourseCodes().contains(record.getCourseCode()))) issue("attemptOutsideTreeOrReplacementWindow", record.getId());
            }
        }
        List<Double> timings = new ArrayList<>(); Map<String,Map<String,Integer>> grouped = new TreeMap<>();
        Map<String,Integer> recognizedByRule = new TreeMap<>(); int completed = 0, runtimeFailures = 0, prefixChecks = 0;
        for (var s : students) {
            long start = System.nanoTime();
            try {
                var result = evaluation.evaluate(s);
                if (Boolean.getBoolean("portal.transcriptAudit")) {
                    var transcript = transcripts.get(s); transcriptCounts[0]++; transcriptCounts[1] += transcript.attempts().size();
                    transcriptCounts[2] += (int)transcript.attempts().stream().filter(a -> a.courseName() == null || a.courseName().isBlank() || a.credits() == null).count();
                    if (!Objects.equals(result.get("cumulativeGpa4"), transcript.summary().cumulativeGpa4())) transcriptCounts[3]++;
                    for (var semester : transcript.semesters()) if (!Objects.equals(((Map<?,?>)result.get("semesterGpa4")).get(semester.semesterCode()), semester.gpa4())) transcriptCounts[3]++;
                }
                if (Boolean.getBoolean("portal.acceptanceAudit"))
                    gpaOracle.compare(s, byProgram.get(s.getCurriculumId()), byRecords.getOrDefault(s.getId(), List.of()), byCourse, rules, result);
                boolean complete = Boolean.TRUE.equals(result.get("requirementsComplete")); if (complete) completed++;
                var major = majors.stream().filter(m -> Objects.equals(m.getId(), s.getMajorId())).findFirst().orElse(null);
                for (String key : List.of("cohort:" + s.getCohort(), "major:" + (major == null ? "unknown" : major.getCode()), "specialization:" + s.getChosenSpecializationCode())) {
                    var group = grouped.computeIfAbsent(key, x -> new TreeMap<>()); group.merge("students", 1, Integer::sum);
                    group.merge(complete ? "requirementsComplete" : "requirementsIncomplete", 1, Integer::sum);
                }
                if (((Number)result.get("recognizedCredits")).intValue() > ((Number)result.get("requiredCredits")).intValue()) issue("creditCapViolation", s.getId());
                if (result.get("cumulativeGpa4") instanceof Number g && (!Double.isFinite(g.doubleValue()) || g.doubleValue() < 0 || g.doubleValue() > 4)) issue("invalidCumulativeGpa", s.getId());
                @SuppressWarnings("unchecked") List<Map<String,Object>> progress = (List<Map<String,Object>>)result.get("groupProgress");
                Set<String> spent = new HashSet<>();
                for (var group : progress) {
                    if (((Number)group.get("recognizedCredits")).intValue() > ((Number)group.get("requiredCredits")).intValue()) issue("groupCreditCapViolation", s.getId());
                    for (Object code : (List<?>)group.get("recognizedCourseCodes")) if (!spent.add(String.valueOf(code))) issue("duplicateGraduationCourse", s.getId());
                }
                for (Object rule : (List<?>)result.get("recognizedReplacementRuleIds")) recognizedByRule.merge(String.valueOf(rule), 1, Integer::sum);
                prefixChecks += verifyElectivePrefix(s, byProgram.get(s.getCurriculumId()), byRecords.getOrDefault(s.getId(), List.of()), byCourse, result);
            } catch (RuntimeException invalid) { runtimeFailures++; issue("evaluationRuntime:" + invalid.getClass().getSimpleName(), s.getId()); }
            timings.add(elapsed(start));
        }
        List<Map<String,Object>> ruleResults = new ArrayList<>(); var timeline = ReplacementApplicability.timeline(rules);
        for (var r : rules) {
            boolean structureValid = r.getOldCourseCodes() != null && !r.getOldCourseCodes().isEmpty()
                && r.getNewCourseCodes() != null && !r.getNewCourseCodes().isEmpty();
            if (structureValid) {
                int oldSize = r.getOldCourseCodes().size(), newSize = r.getNewCourseCodes().size();
                structureValid = switch (String.valueOf(r.getRuleType())) {
                    case "1-1" -> oldSize == 1 && newSize == 1;
                    case "1-N" -> oldSize == 1 && newSize > 1;
                    case "N-1" -> oldSize > 1 && newSize == 1;
                    default -> false;
                };
                structureValid &= new HashSet<>(r.getOldCourseCodes()).size() == oldSize
                    && new HashSet<>(r.getNewCourseCodes()).size() == newSize
                    && Collections.disjoint(r.getOldCourseCodes(), r.getNewCourseCodes())
                    && byCourse.keySet().containsAll(r.getOldCourseCodes()) && byCourse.keySet().containsAll(r.getNewCourseCodes());
            }
            if (!structureValid) issue("invalidRuleStructure", r.getId());
            var window = timeline.stream().filter(w -> r.getId().equals(w.rule().getId())).findFirst();
            long applicable = windows.values().stream().filter(list -> list.stream().anyMatch(w -> w.rule().getId().equals(r.getId()) && w.verifies(Instant.now()))).count();
            ruleResults.add(Map.of("id", r.getId().toHexString(), "type", String.valueOf(r.getRuleType()), "lifecycle", String.valueOf(r.getLifecycleStatus()),
                "structurallyValid", structureValid, "effectiveDate", String.valueOf(r.getEffectiveDate()),
                "endsAt", String.valueOf(r.getEndsAt()), "currentWindow", window.isPresent() && window.get().verifies(Instant.now()), "currentlyApplicableStudents", applicable,
                "studentsWithPreviewOrHistoricalRecognition", recognizedByRule.getOrDefault(r.getId().toHexString(), 0)));
        }
        report.put("rules", ruleResults); report.put("retakeRecords", retakeRecords); report.put("retakingStudents", retakingStudents.size());
        if (Boolean.getBoolean("portal.transcriptAudit")) {
            report.put("transcriptAudit", Map.of("students", transcriptCounts[0], "attempts", transcriptCounts[1],
                "attemptsMissingCatalogFields", transcriptCounts[2], "gpaDifferences", transcriptCounts[3],
                "catalogCoursesMissingName", courses.stream().filter(c -> c.getCourseName() == null || c.getCourseName().isBlank()).count(),
                "catalogCoursesMissingCredits", courses.stream().filter(c -> c.getCredits() == null || c.getCredits() <= 0).count()));
            if (transcriptCounts[0] != students.size() || transcriptCounts[2] != 0 || transcriptCounts[3] != 0)
                issue("transcriptAuditMismatch", "See transcriptAudit");
        }
        if (Boolean.getBoolean("portal.acceptanceAudit")) {
            report.put("independentGpa", gpaOracle.report());
            if (!Objects.equals(gpaOracle.report().get("mismatched"), 0)) issue("independentGpaMismatch", "See independentGpa differences");
        }
        report.put("evaluatedStudents", students.size() - runtimeFailures); report.put("evaluationRuntimeFailures", runtimeFailures);
        report.put("requirementsComplete", completed); report.put("requirementsIncomplete", students.size() - runtimeFailures - completed);
        report.put("officialGraduationDecision", "not executed; preview does not persist recognition evidence");
        report.put("groupStatistics", grouped); report.put("independentElectivePrefixChecks", prefixChecks);
        report.put("evaluationAllSnapshotMs", stats(timings)); report.put("evaluationFirst100SnapshotMs", stats(timings.subList(0, Math.min(100, timings.size()))));
        List<Double> pageTimings = new ArrayList<>(), treeTimings = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime(); database.getCollection("users").find(eq("role", "STUDENT")).projection(new Document("_id", 1).append("studentCode", 1)).sort(new Document("studentCode", 1)).skip(i * 15).limit(15).into(new ArrayList<>()); pageTimings.add(elapsed(start));
            if (!students.isEmpty()) { var s = students.get(i % students.size()); start = System.nanoTime();
                Document p = database.getCollection("curriculums").find(eq("_id", s.getCurriculumId())).first();
                if (p != null) CurriculumTracks.forStudent(converter.read(Curriculum.class, p), s); treeTimings.add(elapsed(start)); }
        }
        report.put("databaseStudentPaginationReadMs", stats(pageTimings)); report.put("databaseCurriculumTreeReadMs", stats(treeTimings));
        report.put("violations", violations); report.put("violationExamples", examples);
        report.put("verdict", violations.isEmpty() && students.size() == 1000 && programs.size() == 12 && validPrograms == 12 && treeCount == 28 && validTrees == 28
            ? "DATA_CHECKS_PASS_BROWSER_E2E_PENDING" : "DATA_CHECKS_FAILED");
    }

    private int verifyElectivePrefix(User student, Curriculum program, List<AcademicRecord> records, Map<String,Course> catalog, Map<String,Object> result) {
        if (!((Map<?,?>)result.get("recognizedReplacements")).isEmpty()) return 0;
        var selected = CurriculumTracks.forStudent(program, student);
        var elective = selected.getRequirementGroups().stream().filter(g -> g.getKind().startsWith("ELECTIVE")).findFirst();
        if (elective.isEmpty()) return 0;
        Map<String,Date> first = new HashMap<>(); boolean unknown = false;
        for (var r : records) if (elective.get().getEligibleCourseCodes().contains(r.getCourseCode()))
            for (var a : CurriculumTracks.safe(r.getAttempts())) if (a.getScore10() != null && a.getScore10() >= 4 && a.getScore10() <= 10) {
                if (a.getCompletedAt() == null) unknown = true;
                else if (!a.getCompletedAt().toInstant().isAfter(Instant.now())) first.merge(r.getCourseCode(), a.getCompletedAt(), (x,y) -> x.before(y) ? x : y);
            }
        if (unknown) return 0;
        List<String> expected = new ArrayList<>(); int credits = 0;
        for (String code : first.keySet().stream().sorted(Comparator.comparing((String c) -> first.get(c)).thenComparing(Comparator.naturalOrder())).toList()) {
            if (credits >= elective.get().getRequiredCredits()) break;
            expected.add(code); credits += catalog.get(code).getCredits();
        }
        @SuppressWarnings("unchecked") var groups = (List<Map<String,Object>>)result.get("groupProgress");
        var actual = groups.stream().filter(g -> Objects.equals(g.get("groupCode"), elective.get().getGroupCode())).findFirst();
        if (actual.isPresent() && !expected.equals(actual.get().get("recognizedCourseCodes"))) issue("electivePrefixMismatch", student.getId());
        return 1;
    }

    private <T> List<T> map(List<Document> documents, Class<T> type, MappingMongoConverter converter) {
        List<T> mapped = new ArrayList<>(); for (var document : documents) try { mapped.add(converter.read(type, document)); }
        catch (RuntimeException invalid) { issue("mappingFailure:" + type.getSimpleName(), document.get("_id")); } return mapped;
    }
    private void issue(String kind, Object id) { violations.merge(kind, 1, Integer::sum); if (examples.size() < 10) examples.add(Map.of("kind", kind, "id", String.valueOf(id))); }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static Map<String,Object> stats(List<Double> values) {
        if (values.isEmpty()) return Map.of(); var sorted = values.stream().sorted().toList();
        return Map.of("samples", values.size(), "average", values.stream().mapToDouble(x -> x).average().orElse(0),
            "median", sorted.get(sorted.size() / 2), "p95", sorted.get(Math.max(0, (int)Math.ceil(sorted.size() * .95) - 1)),
            "total", values.stream().mapToDouble(x -> x).sum());
    }
    @SuppressWarnings("unchecked") private static <R> R snapshot(Class<R> type, List<?> entities) {
        return (R)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("toString")) return "ReadOnlySnapshotRepository";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
            if (name.equals("findAll")) return entities;
            if (name.equals("count")) return (long)entities.size();
            if (name.equals("findById")) return entities.stream().filter(e -> Objects.equals(id(e), args[0])).findFirst();
            if (name.equals("findByCourseCodeIn")) return entities.stream().filter(e -> ((Collection<?>)args[0]).contains(((Course)e).getCourseCode())).toList();
            if (name.equals("findByStudentId")) return entities.stream().filter(e -> Objects.equals(((AcademicRecord)e).getStudentId(), args[0])).toList();
            if (name.equals("findByStudentIdAndCurriculumId")) return entities.stream().filter(e -> {
                var evidence = (RecognitionEvidence)e; return Objects.equals(evidence.getStudentId(), args[0]) && Objects.equals(evidence.getCurriculumId(), args[1]); }).toList();
            throw new UnsupportedOperationException("Read-only snapshot forbids repository method " + name);
        });
    }
    private static Object id(Object entity) {
        if (entity instanceof Curriculum c) return c.getId(); if (entity instanceof Major m) return m.getId();
        if (entity instanceof User u) return u.getId(); if (entity instanceof Course c) return c.getId();
        if (entity instanceof ReplacementRule r) return r.getId(); return null;
    }
}
