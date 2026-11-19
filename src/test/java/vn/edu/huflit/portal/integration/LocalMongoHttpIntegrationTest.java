package vn.edu.huflit.portal.integration;

import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.huflit.portal.PortalApplication;
import vn.edu.huflit.portal.config.JwtTokenProvider;
import vn.edu.huflit.portal.course.model.Course;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.service.RecognitionEvidenceRepository;
import vn.edu.huflit.portal.entity.*;
import vn.edu.huflit.portal.major.model.*;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.repository.UserRepository;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in with portal.realMongo=true and PORTAL_TEST_MONGOD. Never uses application.yml or an existing database. */
@EnabledIfSystemProperty(named = "portal.realMongo", matches = "true")
@SpringBootTest(classes = {PortalApplication.class, LocalMongoHttpIntegrationTest.IsolatedDatabase.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/portal-isolated-integration.properties",
        "server.address=127.0.0.1", "spring.main.banner-mode=off", "logging.level.root=WARN", "logging.level.org.mongodb.driver=OFF"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalMongoHttpIntegrationTest {
    private static final Instant START = Instant.parse("2024-06-01T00:00:00Z");
    private static final String DATABASE = "portal_integration_" + UUID.randomUUID().toString().replace("-", "");
    private static final String SIGNING_MATERIAL = Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(64));
    private static Process mongoProcess;
    private static int mongoPort;
    private static Path databaseDirectory;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Autowired MongoTemplate mongo;
    @Autowired MongoClient client;
    @Autowired Environment environment;
    @Autowired UserRepository users;
    @Autowired CurriculumRepository curricula;
    @Autowired ReplacementRuleRepository rules;
    @Autowired RecognitionEvidenceRepository evidence;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtTokenProvider tokens;
    private String prefix, adminToken, studentToken, ephemeralPassword;
    private User admin, student;
    private Major major;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        startMongo();
        registry.add("jwt.secret", () -> SIGNING_MATERIAL);
        registry.add("jwt.expiration-ms", () -> 3600000);
    }

    private static synchronized void startMongo() throws Exception {
        if (mongoProcess != null) return;
        String executable = System.getenv("PORTAL_TEST_MONGOD");
        if (executable == null || !Files.isRegularFile(Path.of(executable)))
            throw new IllegalStateException("PORTAL_TEST_MONGOD must identify a local mongod binary");
        Path target = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        databaseDirectory = Files.createTempDirectory(target, "isolated-mongo-").toRealPath();
        if (!databaseDirectory.startsWith(target.toRealPath())) throw new IllegalStateException("Unsafe test data directory");
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { mongoPort = socket.getLocalPort(); }
        mongoProcess = new ProcessBuilder(executable, "--dbpath", databaseDirectory.toString(), "--bind_ip", "127.0.0.1",
            "--port", String.valueOf(mongoPort), "--logpath", databaseDirectory.resolve("mongo.log").toString())
            .redirectErrorStream(true).redirectOutput(databaseDirectory.resolve("process.log").toFile()).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (mongoProcess != null) mongoProcess.destroy(); }));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!mongoProcess.isAlive()) throw new IllegalStateException("Isolated mongod exited; inspect target test logs");
            try (var connection = new Socket()) {
                connection.connect(new InetSocketAddress("127.0.0.1", mongoPort), 100); return;
            } catch (java.io.IOException waiting) { Thread.sleep(100); }
        }
        throw new IllegalStateException("Isolated mongod did not start");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabase {
        @Bean MongoClient isolatedClient() {
            return MongoClients.create(MongoClientSettings.builder().applyToClusterSettings(settings ->
                settings.hosts(List.of(new ServerAddress("127.0.0.1", mongoPort)))
                    .serverSelectionTimeout(5, TimeUnit.SECONDS)).build());
        }
        @Bean MongoDatabaseFactory isolatedFactory(MongoClient client) { return new SimpleMongoClientDatabaseFactory(client, DATABASE); }
        @Bean UserDetailsService noDefaultGeneratedAccount() { return name -> { throw new UsernameNotFoundException("Use portal authentication"); }; }
    }

    @BeforeEach void fixture() throws Exception {
        assertEquals(DATABASE, mongo.getDb().getName());
        assertTrue(client.getClusterDescription().getServerDescriptions().stream()
            .allMatch(s -> s.getAddress().getHost().equals("127.0.0.1") && s.getAddress().getPort() == mongoPort));
        prefix = "T" + new ObjectId().toHexString().substring(12);
        ephemeralPassword = UUID.randomUUID().toString();
        major = mongo.insert(Major.builder().code(prefix).name("Integration major").status("ACTIVE")
            .specializations(List.of(new Specialization("AA", "Track A", "ACTIVE"), new Specialization("BB", "Track B", "ACTIVE"))).build());
        admin = users.insert(User.builder().email(prefix + "admin@example.test").role("ADMIN").status("ACTIVE")
            .passwordHash(encoder.encode(ephemeralPassword)).build());
        student = users.insert(User.builder().email(prefix + "student@example.test").role("STUDENT").status("ACTIVE")
            .majorId(major.getId()).cohort("K2024").studentCode(prefix).passwordHash(encoder.encode(ephemeralPassword)).build());
        for (String code : List.of("C", "S", "SB", "E2", "E3", "E4", "NEW", "ALT", "HISTORY"))
            mongo.insert(Course.builder().courseCode(c(code)).courseName("Integration " + code)
                .credits(code.equals("E2") || code.equals("ALT") ? 2 : code.equals("E4") ? 4 : 3).status("ACTIVE").build());
        adminToken = login(admin); studentToken = login(student);
    }

    @AfterAll void stopMongo() throws Exception {
        if (mongoProcess != null) { mongoProcess.destroy(); if (!mongoProcess.waitFor(10, TimeUnit.SECONDS)) mongoProcess.destroyForcibly(); }
    }

    @Test @Order(1) void realJwtLoginRolesCorsAndValidation() throws Exception {
        int anonymous = response("GET", "/api/v1/admin/students", null, null).statusCode();
        assertTrue(anonymous == 401 || anonymous == 403);
        status("GET", "/api/v1/admin/students", studentToken, null, 403);
        status("GET", "/api/v1/student/academic-records", adminToken, null, 403);
        status("GET", "/api/v1/admin/students", adminToken, null, 200);
        status("GET", "/api/v1/student/academic-records", studentToken, null, 200);
        status("GET", "/api/v1/admin/students", "not-a-valid-token", null, anonymous);
        String expired = io.jsonwebtoken.Jwts.builder().setSubject(admin.getEmail())
            .claim("role", "ADMIN").claim("userId", admin.getId().toHexString())
            .setExpiration(Date.from(Instant.now().minusSeconds(60)))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SIGNING_MATERIAL.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .compact();
        status("GET", "/api/v1/admin/students", expired, null, anonymous);
        String misleadingClaim = tokens.generateToken(student.getEmail(), "ADMIN", student.getId().toHexString());
        status("GET", "/api/v1/admin/students", misleadingClaim, null, 403);
        status("POST", "/api/v1/auth/login", null, Map.of("email", admin.getEmail(), "password", UUID.randomUUID().toString()), 400);
        status("POST", "/api/v1/auth/login", null, Map.of(), 400);
        var cors = http.send(HttpRequest.newBuilder(endpoint("/api/v1/admin/academic-records/attempts"))
            .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cors.statusCode()); assertTrue(cors.headers().firstValue("access-control-allow-origin").isPresent());
    }

    @Test @Order(2) void realMongoTreeRoundTripPublicationAssignmentAndNewVersion() throws Exception {
        var draft = draft();
        var stored = curricula.findById(draft.getId()).orElseThrow();
        assertEquals(2, stored.getSpecializationTrees().size());
        assertEquals(c("E2"), stored.getSpecializationTrees().get("AA").getSemesters().get(1).getCourses().getFirst().getCourseCode());
        Map<String,Object> validation = status("GET", path(draft) + "/validation", adminToken, null, 200);
        assertEquals(true, validation.get("valid"));
        publishAndAssign(draft);
        status("PUT", path(draft) + "/specializations/AA/tree", adminToken, tree("AA"), 400);
        status("PUT", path(draft), adminToken, Map.of("curriculumName", "Cannot edit published"), 400);
        Map<String,Object> revision = status("POST", path(draft) + "/clone", adminToken,
            Map.of("newCurriculumCode", prefix + "V2", "newCurriculumName", "Integration revision", "newCohort", "K2024", "newAppliedYear", 2024), 201);
        assertEquals("DRAFT", revision.get("status"));
        String nextId = String.valueOf(revision.get("id"));
        status("POST", "/api/curriculums/" + nextId + "/publish", adminToken, null, 200);
        assertEquals(draft.getId(), users.findById(student.getId()).orElseThrow().getCurriculumId());
        status("PUT", "/api/v1/admin/students/" + student.getId() + "/curriculum-assignment", adminToken,
            Map.of("curriculumId", nextId, "chosenSpecializationCode", "AA"), 400);
    }

    @Test @Order(3) void oneInvalidTreeAndWrongStudentScopeAreRejectedWithoutHistoryDeletion() throws Exception {
        var draft = draft(); var bad = tree("BB");
        bad.getSemesters().getFirst().getCourses().add(item("C", "MANDATORY"));
        status("PUT", path(draft) + "/specializations/BB/tree", adminToken, bad, 200);
        status("POST", path(draft) + "/publish", adminToken, null, 400);
        assertEquals("DRAFT", curricula.findById(draft.getId()).orElseThrow().getStatus());
        status("PUT", path(draft) + "/specializations/BB/tree", adminToken, tree("BB"), 200);
        status("POST", path(draft) + "/publish", adminToken, null, 200);
        student.setCohort("K2025"); users.save(student);
        status("PUT", "/api/v1/admin/students/" + student.getId() + "/curriculum-assignment", adminToken,
            Map.of("curriculumId", draft.getId().toHexString(), "chosenSpecializationCode", "AA"), 400);
        assertNull(users.findById(student.getId()).orElseThrow().getCurriculumId());
    }

    @Test @Order(4) void electivesUseFirstPassChronologyWhileHighestScoreAndSemesterGpaRemainIndependent() throws Exception {
        var program = draft(); publishAndAssign(program);
        score(program, "C", 7, START, "2024-1", 200);
        score(program, "S", 8, START, "2024-1", 200);
        score(program, "E4", 5, START, "2024-1", 200);
        score(program, "E3", 7, START.plusSeconds(1), "2024-1", 200);
        score(program, "E2", 6, START.plusSeconds(2), "2024-1", 200);
        score(program, "E4", 10, START.plusSeconds(100), "2025-1", 200);
        score(program, "SB", 8, START, "2024-1", 400);
        Map<String,Object> evaluation = status("GET", "/api/v1/student/curriculum/evaluation-official", studentToken, null, 200);
        assertEquals(11, number(evaluation.get("recognizedCredits"))); assertEquals(15, number(evaluation.get("gpaCredits")));
        assertEquals(3.3, ((Number)evaluation.get("cumulativeGpa4")).doubleValue());
        assertEquals(true, evaluation.get("officialGraduationEligible"));
        Map<?,?> elective = ((List<Map<?,?>>)evaluation.get("groupProgress")).stream().filter(g -> "FREE".equals(g.get("groupCode"))).findFirst().orElseThrow();
        assertEquals(List.of(c("E4"), c("E3")), elective.get("recognizedCourseCodes"));
        assertEquals(7, number(elective.get("actualSelectedCredits"))); assertEquals(5, number(elective.get("recognizedCredits")));
        assertEquals(2.63, ((Number)((Map<?,?>)evaluation.get("semesterGpa4")).get("2024-1")).doubleValue());
        assertEquals(4.0, ((Number)((Map<?,?>)evaluation.get("semesterGpa4")).get("2025-1")).doubleValue());
    }

    @Test @Order(5) void publishedReplacementBundlesAndRetirementPreserveRealStoredEvidence() throws Exception {
        for (String type : List.of("1-1", "1-N", "N-1")) {
            var program = draft(); publishAndAssignFreshStudent(program);
            var input = ReplacementRule.builder().majorId(major.getId()).applicableCohorts(List.of("K2024"))
                .ruleType(type).oldCourseCodes(type.equals("N-1") ? List.of(c("C"), c("S")) : List.of(c("C")))
                .newCourseCodes(type.equals("1-N") ? List.of(c("NEW"), c("ALT")) : List.of(c("NEW")))
                .effectiveDate(Date.from(START)).reason("Isolated integration verification").build();
            String id = String.valueOf(status("POST", "/api/v1/admin/replacement-rules", adminToken, input, 200).get("id"));
            score(program, "NEW", 7, START, "2024-1", 400);
            status("POST", "/api/v1/admin/replacement-rules/" + id + "/publish", adminToken, null, 200);
            var published = rules.findById(new ObjectId(id)).orElseThrow();
            Instant completion = published.getPublishedAt().toInstant();
            score(program, "NEW", 7, completion, "2026-1", 200);
            if (type.equals("1-N")) {
                var partial = status("GET", "/api/v1/student/curriculum/evaluation-preview", studentToken, null, 200);
                assertEquals(0, number(partial.get("recognizedCredits")));
                score(program, "ALT", 8, completion, "2026-1", 200);
            }
            var result = status("GET", "/api/v1/student/curriculum/evaluation-official", studentToken, null, 200);
            int expected = type.equals("N-1") ? 6 : 3;
            assertEquals(expected, number(result.get("recognizedCredits")));
            var snapshot = evidence.findByStudentIdAndCurriculumId(student.getId(), program.getId()).getFirst();
            status("POST", "/api/v1/admin/replacement-rules/" + id + "/retire", adminToken, Map.of("reason", "Integration retirement"), 200);
            var retired = rules.findById(new ObjectId(id)).orElseThrow();
            score(program, "NEW", 8, retired.getEndsAt().toInstant(), "2026-1", 400);
            var after = status("GET", "/api/v1/student/curriculum/evaluation-official", studentToken, null, 200);
            assertEquals(result.get("recognizedCredits"), after.get("recognizedCredits"));
            assertEquals(result.get("cumulativeGpa4"), after.get("cumulativeGpa4"));
            assertEquals(snapshot, evidence.findByStudentIdAndCurriculumId(student.getId(), program.getId()).getFirst());
            // Keep each rule's old targets unique when publishing the next shape.
            prefix = "T" + new ObjectId().toHexString().substring(12);
            for (String code : List.of("C", "S", "SB", "E2", "E3", "E4", "NEW", "ALT"))
                mongo.insert(Course.builder().courseCode(c(code)).courseName("Integration " + code)
                    .credits(code.equals("E2") || code.equals("ALT") ? 2 : code.equals("E4") ? 4 : 3).status("ACTIVE").build());
        }
    }

    @Test @Order(6) void electiveSourcesCannotAuthorizeOutsideMandatoryReplacementEntry() throws Exception {
        var program = draft(); publishAndAssign(program);
        var rule = ReplacementRule.builder().id(new ObjectId()).majorId(major.getId()).applicableCohorts(List.of("K2024"))
            .status("ACTIVE").lifecycleStatus("PUBLISHED").ruleType("1-N").effectiveDate(Date.from(START)).publishedAt(Date.from(START))
            .oldCourseCodes(List.of(c("C"))).newCourseCodes(List.of(c("E2"), c("NEW"))).build();
        rules.insert(rule);
        score(program, "NEW", 7, START, "2024-1", 400);
        var result = status("GET", "/api/v1/student/curriculum/evaluation-official", studentToken, null, 200);
        assertTrue(((Map<?,?>)result.get("recognizedReplacements")).isEmpty());
    }

    @Test @Order(7) void flatHistoricalCurriculumAndOutsideRecordsRemainReadableWithoutRewriting() throws Exception {
        var program = draft(); var aa = tree("AA"); program.setSpecializationTrees(Map.of());
        program.setSpecializations(List.of(new Curriculum.SpecializationItem("AA", "Track A")));
        program.setSemesters(aa.getSemesters()); program.setRequirementGroups(aa.getRequirementGroups());
        program.setStatus("ACTIVE"); curricula.save(program);
        student.setCurriculumId(program.getId()); student.setChosenSpecializationCode("AA"); users.save(student);
        AcademicRecord.AttemptItem attempt = new AcademicRecord.AttemptItem(); attempt.setAttemptId(UUID.randomUUID().toString());
        attempt.setCompletedAt(Date.from(START)); attempt.setScore10(7.0); attempt.setSemesterCode("2024-1");
        var historical = mongo.insert(AcademicRecord.builder().studentId(student.getId()).curriculumId(program.getId())
            .courseCode(c("HISTORY")).attempts(List.of(attempt)).status("PASSED").highestScore10(7.0).build());
        Document before = mongo.getCollection("academicRecords").find(new Document("_id", historical.getId())).first();
        status("GET", "/api/v1/student/curriculum", studentToken, null, 200);
        status("GET", "/api/v1/student/academic-records", studentToken, null, 200);
        assertEquals(before, mongo.getCollection("academicRecords").find(new Document("_id", historical.getId())).first());
        assertTrue(curricula.findById(program.getId()).orElseThrow().getSpecializationTrees().isEmpty());
    }

    @Test @Order(8) void supersededAndExpiredRulesKeepStoredEvidenceWithoutDoubleRecognition() throws Exception {
        var program = draft(); publishAndAssign(program);
        var rule = rules.insert(ReplacementRule.builder().id(new ObjectId()).versionNumber(1)
            .majorId(major.getId()).applicableCohorts(List.of("K2024")).ruleType("1-1")
            .oldCourseCodes(List.of(c("C"))).newCourseCodes(List.of(c("NEW")))
            .effectiveDate(Date.from(START)).publishedAt(Date.from(START)).status("ACTIVE").lifecycleStatus("PUBLISHED").reason("Historical fixture").build());
        score(program, "NEW", 7, START, "2024-1", 200);
        var before = status("GET", "/api/v1/student/curriculum/evaluation-official", studentToken, null, 200);
        Document stored = mongo.getCollection("recognitionEvidence").find(new Document("ruleId", rule.getId())).first();
        assertNotNull(stored);
        var revision = status("POST", "/api/v1/admin/replacement-rules/" + rule.getId() + "/revisions", adminToken, null, 200);
        String revisionId = String.valueOf(revision.get("id"));
        status("POST", "/api/v1/admin/replacement-rules/" + revisionId + "/publish", adminToken, null, 200);
        var superseded = status("GET", "/api/v1/student/curriculum/evaluation-official", studentToken, null, 200);
        assertEquals(3, number(superseded.get("recognizedCredits")));
        assertEquals(1, ((List<?>)superseded.get("historicalRecognitionEvidence")).size());
        var display = status("GET", "/api/v1/student/curriculum", studentToken, null, 200);
        var semester = (Map<?,?>)((List<?>)display.get("semesters")).getFirst();
        var courseView = (Map<?,?>)((List<?>)semester.get("courses")).getFirst();
        var evidenceView = ((List<?>)courseView.get("replacementRules")).stream().map(v -> (Map<?,?>)v)
            .filter(v -> Boolean.TRUE.equals(v.get("recognized"))).findFirst().orElseThrow();
        assertEquals(rule.getId().toHexString(), evidenceView.get("ruleId"));
        assertEquals(true, evidenceView.get("historicalEvidence"));
        assertNotNull(evidenceView.get("recognizedAt"));
        score(program, "C", 8, START, "2024-1", 200); // Direct pass competes with the historical entitlement.
        var after = status("GET", "/api/v1/student/curriculum/evaluation-official", studentToken, null, 200);
        assertEquals(3, number(before.get("recognizedCredits"))); assertEquals(3, number(after.get("recognizedCredits")));
        assertEquals(1, ((List<?>)after.get("historicalRecognitionEvidence")).size());
        assertEquals(stored, mongo.getCollection("recognitionEvidence").find(new Document("_id", stored.get("_id"))).first());
        assertEquals(1, evidence.findByStudentIdAndCurriculumId(student.getId(), program.getId()).size());
        status("POST", "/api/v1/admin/replacement-rules/" + revisionId + "/retire", adminToken, Map.of("reason", "Local expiry verification"), 200);
        status("GET", "/api/v1/student/curriculum", studentToken, null, 200);
        assertEquals(stored, mongo.getCollection("recognitionEvidence").find(new Document("_id", stored.get("_id"))).first());
    }

    @Test @Order(9) void authenticatedTranscriptIncludesCatalogAndOfficialSemesterGpaAndIsReadOnly() throws Exception {
        String endpoint="/api/v1/student/academic-records/transcript";
        var empty=status("GET",endpoint,studentToken,null,200);
        assertTrue(((List<?>)empty.get("attempts")).isEmpty());
        var p=draft();publishAndAssign(p);
        score(p,"C",7,START,"2024-1",200);score(p,"C",10,START.plusSeconds(1),"2025-1",200);
        score(p,"E4",3,START,"2024-1",200);score(p,"E2",5,START,"2024-1",200);score(p,"E3",7,START,"2024-1",200);
        long before=evidence.count();var v=status("GET",endpoint,studentToken,null,200);
        var summary=(Map<?,?>)v.get("summary");assertEquals(2.0,((Number)summary.get("cumulativeGpa4")).doubleValue());
        assertEquals(8,number(summary.get("academicEarnedCredits")));assertEquals(8,number(summary.get("graduationRecognizedCredits")));
        assertEquals(1,number(summary.get("failedCourseCount")));assertEquals(5,((List<?>)v.get("attempts")).size());
        var semester=(Map<?,?>)((List<?>)v.get("semesters")).getFirst();
        assertEquals(1.75,((Number)semester.get("gpa4")).doubleValue());assertEquals(12,number(semester.get("attemptedCredits")));
        assertEquals(8,number(semester.get("earnedCredits")));assertEquals(before,evidence.count());
        for(var row:(List<?>)v.get("attempts")){var a=(Map<?,?>)row;assertNotNull(a.get("courseName"));assertNotNull(a.get("credits"));}
        assertEquals(v,status("GET",endpoint+"?studentId="+admin.getId(),studentToken,null,200));
        status("GET",endpoint,adminToken,null,403);
        int anonymous=response("GET",endpoint,null,null).statusCode();assertTrue(anonymous==401||anonymous==403);
    }

    @Test @Order(99) void auditProvidedBigDataInAnIndependentLocalDatabase() throws Exception {
        String zip = System.getenv("PORTAL_TEST_BIGDATA_ZIP");
        Assumptions.assumeTrue(zip != null && Files.isRegularFile(Path.of(zip)), "No BigData ZIP supplied");
        BigDataCompatibilityAudit.run(Path.of(zip), new MongoTemplate(client, DATABASE + "_bigdata"), Path.of("target", "bigdata-compatibility.json"));
        if (Boolean.getBoolean("portal.auditV2Snapshot")) AtlasReadOnlyAuditTest.auditLocalDataset(client.getDatabase(DATABASE + "_bigdata"));
    }

    @Test @Order(100) void realBrowserAcceptanceOnIsolatedMongoOnly() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("portal.browserE2e"), "Browser acceptance is explicitly opt-in");
        Map<String,Object> fixtures = new LinkedHashMap<>();
        for (String viewport : List.of("desktop", "mobile")) {
            Map<String,Object> scenarios = new LinkedHashMap<>();
            for (String shape : List.of("workflow", "1-1", "1-N", "N-1")) {
                fixture();
                var p = draft();
                if (!shape.equals("workflow")) {
                    publishAndAssign(p);
                    rules.insert(ReplacementRule.builder().id(new ObjectId()).ruleFamilyId(new ObjectId()).versionNumber(1)
                        .majorId(major.getId()).applicableCohorts(List.of("K2024")).ruleType(shape)
                        .oldCourseCodes(shape.equals("N-1") ? List.of(c("C"), c("S")) : List.of(c("C")))
                        .newCourseCodes(shape.equals("1-N") ? List.of(c("NEW"), c("ALT")) : List.of(c("NEW")))
                        .effectiveDate(Date.from(START)).publishedAt(Date.from(START)).status("ACTIVE").lifecycleStatus("PUBLISHED")
                        .reason("Local browser fixture").build());
                }
                scenarios.put(shape, Map.of("programId", p.getId().toHexString(), "prefix", prefix,
                    "studentId", student.getId().toHexString(), "studentCode", student.getStudentCode(),
                    "adminEmail", admin.getEmail(), "studentEmail", student.getEmail(), "password", ephemeralPassword));
            }
            fixtures.put(viewport, scenarios);
        }
        Path log = Path.of("target", "lenh7-browser.log").toAbsolutePath();
        var builder = new ProcessBuilder("cmd.exe", "/c", "node_modules\\.bin\\playwright.cmd", "test")
            .directory(Path.of("frontend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("PORTAL_E2E_FIXTURES", json.writeValueAsString(fixtures));
        builder.environment().put("VITE_API_BASE_URL", endpoint("").toString());
        for (String secret : List.of("PORTAL_ATLAS_READ_URI", "MONGODB_URI", "JWT_SECRET", "PORTAL_BIGDATA_SAMPLE_PASSWORD"))
            builder.environment().remove(secret);
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            builder.environment().put("PORTAL_E2E_WEB_PORT", String.valueOf(socket.getLocalPort()));
        }
        var process = builder.start();
        try {
            assertTrue(process.waitFor(12, TimeUnit.MINUTES), "Browser tests timed out; see target/lenh7-browser.log");
            assertEquals(0, process.exitValue(), "Browser tests failed; see target/lenh7-browser.log");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private Curriculum draft() {
        return curricula.insert(Curriculum.builder().curriculumCode(prefix + "P").curriculumName("Integration program")
            .majorId(major.getId()).cohort("K2024").appliedYear(2024).status("DRAFT").version(1).revision(0L)
            .specializationSelectionTiming("EARLY").specializationSelectionSemester(1)
            .specializations(List.of(new Curriculum.SpecializationItem("AA", "Track A"), new Curriculum.SpecializationItem("BB", "Track B")))
            .specializationTrees(Map.of("AA", tree("AA"), "BB", tree("BB"))).build());
    }
    private Curriculum.SpecializationTree tree(String track) {
        return new Curriculum.SpecializationTree(List.of(new Curriculum.SemesterItem(1, new ArrayList<>(List.of(item("C", "MANDATORY"), item(track.equals("AA") ? "S" : "SB", "MANDATORY")))),
            new Curriculum.SemesterItem(2, List.of(item("E2", "ELECTIVE"), item("E3", "ELECTIVE"), item("E4", "ELECTIVE")))), List.of(
                group("COMMON", "MANDATORY_COMMON", 3, c("C")), group("SPEC", "MANDATORY_SPECIALIZATION", 3, c(track.equals("AA") ? "S" : "SB")),
                group("FREE", "ELECTIVE_FREE", track.equals("AA") ? 5 : 3, c("E2"), c("E3"), c("E4"))));
    }
    private Curriculum.CurriculumCourseItem item(String code, String type) { return Curriculum.CurriculumCourseItem.builder().courseCode(c(code)).type(type).build(); }
    private Curriculum.RequirementGroupItem group(String code, String kind, int quota, String... courses) {
        return Curriculum.RequirementGroupItem.builder().groupCode(code).kind(kind).requiredCredits(quota).eligibleCourseCodes(List.of(courses)).build();
    }
    private String c(String code) { return prefix + code; }
    private String path(Curriculum p) { return "/api/curriculums/" + p.getId(); }
    private void publishAndAssignFreshStudent(Curriculum p) throws Exception {
        student.setId(new ObjectId()); student.setEmail(prefix + "fresh@example.test"); student.setCurriculumId(null); student.setChosenSpecializationCode(null); users.insert(student);
        studentToken = login(student); publishAndAssign(p);
    }
    private void publishAndAssign(Curriculum p) throws Exception {
        status("POST", path(p) + "/publish", adminToken, null, 200);
        status("PUT", "/api/v1/admin/students/" + student.getId() + "/curriculum-assignment", adminToken,
            Map.of("curriculumId", p.getId().toHexString(), "chosenSpecializationCode", "AA"), 200);
    }
    private Map<String,Object> score(Curriculum p, String code, double grade, Instant completed, String semester, int expected) throws Exception {
        return status("POST", "/api/v1/admin/academic-records/attempts", adminToken,
            Map.of("studentId", student.getId().toHexString(), "curriculumId", p.getId().toHexString(), "courseCode", c(code),
                "attempt", Map.of("score10", grade, "semesterCode", semester, "completedAt", completed.toString())), expected);
    }
    private String login(User user) throws Exception {
        return String.valueOf(status("POST", "/api/v1/auth/login", null, Map.of("email", user.getEmail(), "password", ephemeralPassword), 200).get("token"));
    }
    private URI endpoint(String path) { return URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path); }
    private HttpResponse<String> response(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> status(String method, String path, String token, Object body, int expected) throws Exception {
        var response = response(method, path, token, body);
        if (response.statusCode() != expected) throw new AssertionError(method + " " + path + " returned " + response.statusCode() + ", expected " + expected);
        if (expected == 403 || expected == 401 || response.body().isBlank()) return Map.of();
        Map<String,Object> envelope = json.readValue(response.body(), Map.class);
        Object data = envelope.get("data"); return data instanceof Map<?,?> ? (Map<String,Object>)data : envelope;
    }
    private int number(Object value) { return ((Number)value).intValue(); }
}
