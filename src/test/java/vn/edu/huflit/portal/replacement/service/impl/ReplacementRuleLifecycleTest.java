package vn.edu.huflit.portal.replacement.service.impl;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.major.repository.MajorRepository;
import vn.edu.huflit.portal.notification.repository.NotificationRepository;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;
import vn.edu.huflit.portal.repository.UserRepository;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplacementRuleLifecycleTest {
    private final ReplacementRuleRepository rules = mock(ReplacementRuleRepository.class);
    private final ChangeLogService audit = mock(ChangeLogService.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final MajorRepository majors = mock(MajorRepository.class);
    private final CurriculumRepository curricula = mock(CurriculumRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final ReplacementRuleServiceImpl service = new ReplacementRuleServiceImpl(
        rules, audit, mock(NotificationRepository.class), mock(UserRepository.class),
        courses, majors, curricula, mongo, new ReplacementApplicability(rules));
    private final User actor = User.builder().id(new ObjectId()).build();
    private final ObjectId major = new ObjectId();

    @Test
    void createIgnoresClientSuppliedLifecycleAndRetirementFields() {
        ReplacementRule input = rule("PUBLISHED", "ACTIVE");
        ObjectId suppliedId = input.getId();
        input.setEndsAt(new Date());
        input.setRetiredBy(actor.getId());
        allowCatalog();
        when(rules.insert(input)).thenReturn(input);

        ReplacementRule created = service.create(actor, input);
        assertNotEquals(suppliedId, created.getId());
        assertEquals(created.getId(), created.getRuleFamilyId());
        assertEquals("DRAFT", created.getLifecycleStatus());
        assertEquals("INACTIVE", created.getStatus());
        assertNull(created.getEndsAt());
        assertNull(created.getRetiredBy());
        assertNull(created.getPublishedAt());
    }

    @Test
    void publishedVersionCannotBeEditedAndDraftWriteRequiresAnUnchangedDraft() {
        ReplacementRule published = rule("PUBLISHED", "ACTIVE");
        assertThrows(IllegalArgumentException.class, () -> service.update(actor, published.getId().toHexString(),
            rule("DRAFT", "INACTIVE")));
        verifyNoInteractions(mongo);

        ReplacementRule draft = rule("DRAFT", "INACTIVE");
        allowCatalog();
        assertThrows(IllegalStateException.class, () -> service.update(actor, draft.getId().toHexString(), draft));
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(Update.class), any(FindAndModifyOptions.class),
            eq(ReplacementRule.class));
        assertEquals("DRAFT", query.getValue().getQueryObject().get("lifecycleStatus"));
        assertEquals(major, query.getValue().getQueryObject().get("majorId"));
    }

    @Test
    void validationAndPublicationEnforceTheSameOverlapCheckWithoutWrites() {
        ReplacementRule draft = rule("DRAFT", "INACTIVE");
        ReplacementRule other = rule("PUBLISHED", "ACTIVE");
        Date start = Date.from(Instant.now().plusSeconds(3600));
        draft.setEffectiveDate(start);
        allowCatalog();
        when(rules.findAll()).thenReturn(List.of(draft, other));

        assertThrows(IllegalArgumentException.class, () -> service.validateForPublication(draft.getId().toHexString()));
        assertThrows(IllegalArgumentException.class, () -> service.publish(actor, draft.getId().toHexString()));
        other.setEndsAt(start);
        assertEquals(start, service.validateForPublication(draft.getId().toHexString()).getEffectiveDate());
        verifyNoInteractions(mongo, audit);
    }

    @Test
    void publishingFutureRevisionKeepsDraftSnapshotGuardAndLeavesPredecessorUntouched() {
        ReplacementRule previous = rule("PUBLISHED", "ACTIVE");
        ReplacementRule draft = rule("DRAFT", "INACTIVE");
        draft.setRuleFamilyId(previous.getId());
        draft.setPreviousVersionId(previous.getId());
        draft.setVersionNumber(2);
        Date scheduled = Date.from(Instant.now().plusSeconds(3600));
        draft.setEffectiveDate(scheduled);
        when(rules.findByRuleFamilyId(previous.getId())).thenReturn(List.of(draft));
        when(rules.findAll()).thenReturn(List.of(previous, draft));
        allowCatalog();
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(ReplacementRule.class))).thenReturn(draft);

        service.publish(actor, draft.getId().toHexString());
        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(query.capture(), update.capture(), any(FindAndModifyOptions.class),
            eq(ReplacementRule.class));
        Document matching = query.getValue().getQueryObject();
        Document written = update.getValue().getUpdateObject().get("$set", Document.class);
        assertEquals(draft.getId(), matching.get("_id"));
        assertEquals("DRAFT", matching.get("lifecycleStatus"));
        assertEquals(scheduled, matching.get("effectiveDate"));
        assertEquals("PUBLISHED", written.get("lifecycleStatus"));
        assertEquals(scheduled, written.get("effectiveDate"));
        assertTrue(((Date) written.get("publishedAt")).before(scheduled));
        assertNull(previous.getEndsAt());
    }

    @Test
    void retirementWritesAnExclusiveEndAndPreservesPublishedState() {
        ReplacementRule published = rule("PUBLISHED", "ACTIVE");
        Date end = Date.from(Instant.now().plusSeconds(3600));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
            eq(ReplacementRule.class))).thenReturn(published);
        service.retire(actor, published.getId().toHexString(), end, "  Hết áp dụng  ");

        var update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class),
            eq(ReplacementRule.class));
        Document fields = update.getValue().getUpdateObject().get("$set", Document.class);
        assertEquals(end, fields.get("endsAt"));
        assertEquals(actor.getId(), fields.get("retiredBy"));
        assertEquals("Hết áp dụng", fields.get("retirementReason"));
        assertFalse(fields.containsKey("lifecycleStatus"));
        assertFalse(fields.containsKey("status"));
        assertFalse(fields.containsKey("oldCourseCodes"));
        assertThrows(IllegalArgumentException.class, () -> service.retire(actor,
            published.getId().toHexString(), Date.from(Instant.EPOCH), null));
    }

    @Test
    void cancellingScheduledSuccessorCannotRestoreAnOverlapWithAnotherFamily() {
        ReplacementRule previous = rule("PUBLISHED", "ACTIVE");
        ReplacementRule scheduled = rule("PUBLISHED", "ACTIVE");
        ReplacementRule independent = rule("PUBLISHED", "ACTIVE");
        Date transition = Date.from(Instant.now().plusSeconds(3600));
        scheduled.setRuleFamilyId(previous.getId());
        scheduled.setVersionNumber(2);
        scheduled.setEffectiveDate(transition);
        scheduled.setApplicableCohorts(List.of("K2025"));
        independent.setEffectiveDate(transition);
        when(rules.findByRuleFamilyId(previous.getId())).thenReturn(List.of(scheduled));
        when(rules.findAll()).thenReturn(List.of(previous, scheduled, independent));

        assertThrows(IllegalArgumentException.class,
            () -> service.retire(actor, scheduled.getId().toHexString(), null, null));
        verifyNoInteractions(mongo);
    }

    @Test
    void legacyRootRemainsInHistoryAndCannotForkAnExistingSuccessor() {
        ReplacementRule root = rule("PUBLISHED", "ACTIVE");
        ReplacementRule next = rule("PUBLISHED", "ACTIVE");
        next.setRuleFamilyId(root.getId());
        next.setPreviousVersionId(root.getId());
        next.setVersionNumber(2);
        when(rules.findByRuleFamilyId(root.getId())).thenReturn(List.of(next));

        assertEquals(List.of(root, next), service.history(next.getId().toHexString()));
        assertThrows(IllegalArgumentException.class, () -> service.createRevision(actor, root.getId().toHexString()));
        when(rules.insert(any(ReplacementRule.class))).thenAnswer(call -> call.getArgument(0));
        ReplacementRule draft = service.createRevision(actor, next.getId().toHexString());
        assertEquals(next.getId(), draft.getPreviousVersionId());
        assertEquals(3, draft.getVersionNumber());
        assertEquals("DRAFT", draft.getLifecycleStatus());
        assertNull(draft.getEndsAt());
    }

    private void allowCatalog() {
        when(majors.existsById(major)).thenReturn(true);
        when(courses.existsByCourseCode("OLD")).thenReturn(true);
        when(courses.existsByCourseCode("NEW")).thenReturn(true);
        when(curricula.findByMajorId(major)).thenReturn(List.of(Curriculum.builder().cohort("K2024").build()));
    }

    private ReplacementRule rule(String lifecycle, String status) {
        ReplacementRule rule = ReplacementRule.builder().id(new ObjectId()).lifecycleStatus(lifecycle).status(status)
            .ruleType("1-1").oldCourseCodes(List.of("OLD")).newCourseCodes(List.of("NEW"))
            .majorId(major).applicableCohorts(List.of("K2024"))
            .effectiveDate(Date.from(Instant.parse("2024-01-01T00:00:00Z"))).build();
        when(rules.findById(rule.getId())).thenReturn(Optional.of(rule));
        return rule;
    }
}
