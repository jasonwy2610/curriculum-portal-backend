package vn.edu.huflit.portal.curriculum.service;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.changelog.model.ChangeLog;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.edu.huflit.portal.entity.User;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CurriculumPublicationService {
    private final CurriculumRepository repository;
    private final CurriculumRequirementValidationService validator;
    private final MongoTemplate mongoTemplate;
    private final ChangeLogService changeLogs;
    private final CurriculumCreditPlanService creditPlanService;

    private Curriculum draft(String id) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("ID CTDT khong hop le");
        Curriculum c = repository.findById(new ObjectId(id)).orElseThrow(() -> new IllegalArgumentException("Khong tim thay CTDT"));
        if (!"DRAFT".equalsIgnoreCase(c.getStatus())) throw new IllegalStateException("Chi CTDT DRAFT duoc phep chinh sua");
        return c;
    }

    public Curriculum configureGroups(String id, List<Curriculum.RequirementGroupItem> groups) {
        if (groups == null) throw new IllegalArgumentException("requirementGroups khong duoc null");
        Curriculum current = draft(id);
        // Atomic conditional update avoids changing a curriculum concurrently published.
        if (!CurriculumTracks.trees(current).isEmpty())
            throw new IllegalArgumentException("CTĐT có cây riêng; hãy lưu nhóm yêu cầu trong từng cây chuyên ngành");
        Query query = Query.query(Criteria.where("_id").is(current.getId()).and("status").is("DRAFT")
            .and("revision").is(current.getRevision()));
        Curriculum saved = mongoTemplate.findAndModify(query, new Update().set("requirementGroups", groups).inc("revision", 1),
                FindAndModifyOptions.options().returnNew(true), Curriculum.class);
        if (saved == null) throw new IllegalStateException("CTDT da thay doi trang thai; vui long tai lai");
        changeLogs.recordChange("Curriculum", saved.getId(), "UPDATE",
                List.of(new ChangeLog.FieldChangeItem("requirementGroups", current.getRequirementGroups(), groups)), actor());
        return saved;
    }

    public Curriculum publish(String id) {
        Curriculum current = draft(id);
        CurriculumRequirementValidationService.ValidationResult result = validator.validate(current);
        if (!result.valid()) throw new IllegalArgumentException("CTDT chua du dieu kien cong bo: " + String.join("; ", result.errors()));
        CurriculumCreditPlanService.CreditPlan plan = creditPlanService.calculate(current)
            .orElseThrow(() -> new IllegalArgumentException("Không thể tính tín chỉ từ cấu trúc CTĐT; kiểm tra các nhóm và chuyên ngành"));
        Map<String, Curriculum.TrackCreditPlan> trackPlans = new LinkedHashMap<>();
        plan.totalsByTrack().forEach((track, total) -> trackPlans.put(track,
            new Curriculum.TrackCreditPlan(total, plan.mandatoryByTrack().get(track),
                total - plan.mandatoryByTrack().get(track))));
        // Compare current document state to avoid overwriting concurrent edits to requirement groups.
        Query query = Query.query(Criteria.where("_id").is(current.getId())
                .and("status").is("DRAFT").and("requirementGroups").is(current.getRequirementGroups())
                .and("revision").is(current.getRevision())
                .and("majorId").is(current.getMajorId()).and("cohort").is(current.getCohort())
                .and("semesters").is(current.getSemesters())
                .and("specializations").is(current.getSpecializations()));
        Date publishedAt = new Date();
        ObjectId publishedBy = actor();
        Update publishUpdate = new Update().set("status", "ACTIVE").inc("revision", 1)
            .set("publicationAudit.publishedAt", publishedAt)
            .set("publicationAudit.publishedBy", publishedBy)
            .set("publicationAudit.validationPassed", true)
            .set("publicationAudit.secondaryLogStatus", "PENDING")
            .set("creditPlansBySpecialization", trackPlans)
            .set("totalCredits", plan.total())
            .set("mandatoryCredits", plan.mandatory())
            .set("electiveCreditsRequired", plan.elective())
            .set("creditRequirements.requiredTotal", plan.total())
            .set("creditRequirements.mandatoryCredits", plan.mandatory())
            .set("creditRequirements.electiveCreditsRequired", plan.elective());
        Curriculum saved = mongoTemplate.findAndModify(query, publishUpdate,
                FindAndModifyOptions.options().returnNew(true), Curriculum.class);
        if (saved == null) throw new IllegalStateException("CTDT da thay doi trong khi kiem tra; thu lai");
        // Publication audit is written atomically with the status. A secondary
        // change-log failure must not report a failed publication to the caller.
        try {
            changeLogs.recordChange("Curriculum", saved.getId(), "PUBLISH",
                List.of(new ChangeLog.FieldChangeItem("status", "DRAFT", "ACTIVE")), publishedBy);
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("publicationAudit.secondaryLogStatus", "RECORDED"), Curriculum.class);
        } catch (RuntimeException logFailure) {
            org.slf4j.LoggerFactory.getLogger(CurriculumPublicationService.class)
                .error("Secondary change log failed for published curriculum {}", saved.getId(), logFailure);
        }
        return saved;
    }

    /** Retry the secondary log after an interrupted publication; publication itself is immutable. */
    public void retryPublicationAudit(Curriculum published) {
        if (published == null || !"ACTIVE".equalsIgnoreCase(published.getStatus())) return;
        boolean alreadyLogged = mongoTemplate.exists(Query.query(Criteria.where("entityType").is("Curriculum")
            .and("entityId").is(published.getId()).and("action").is("PUBLISH")), ChangeLog.class);
        if (!alreadyLogged) {
            changeLogs.recordChange("Curriculum", published.getId(), "PUBLISH",
                List.of(new ChangeLog.FieldChangeItem("status", "DRAFT", "ACTIVE")), null);
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(published.getId())),
            new Update().set("publicationAudit.secondaryLogStatus", "RECORDED"), Curriculum.class);
    }

    private ObjectId actor() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof User u ? u.getId() : null;
    }
}
