package vn.edu.huflit.portal.curriculum.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Sort;
import org.bson.types.ObjectId;
import java.util.List;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.replacement.service.impl.ReplacementRuleServiceImpl;

/** At-least-once recovery of publication side effects; never republishes entities. */
@Service
@EnableScheduling
@RequiredArgsConstructor
public class PublicationRecoveryService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PublicationRecoveryService.class);
    private final MongoTemplate mongo;
    private final CurriculumPublicationService curricula;
    private final ReplacementRuleServiceImpl replacements;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { recover(); }

    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void recover() {
        // Keyset pagination prevents a permanently failing early record from
        // starving later pending publications. Every matching record is visited
        // once per sweep, including batches after the first 100.
        ObjectId lastCurriculumId = null;
        while (true) {
            Criteria pending = Criteria.where("status").is("ACTIVE")
                .and("publicationAudit.secondaryLogStatus").is("PENDING");
            Query query = Query.query(lastCurriculumId == null ? pending
                : new Criteria().andOperator(pending, Criteria.where("_id").gt(lastCurriculumId)))
                .with(Sort.by(Sort.Direction.ASC, "_id")).limit(100);
            List<Curriculum> batch = mongo.find(query, Curriculum.class);
            if (batch.isEmpty()) break;
            for (Curriculum c : batch) {
                try { curricula.retryPublicationAudit(c); }
                catch (RuntimeException ex) { log.error("Curriculum audit recovery failed {}", c.getId(), ex); }
            }
            lastCurriculumId = batch.get(batch.size() - 1).getId();
            if (lastCurriculumId == null || batch.size() < 100) break;
        }
        ObjectId lastReplacementId = null;
        while (true) {
            Criteria pending = new Criteria().andOperator(
                Criteria.where("lifecycleStatus").is("PUBLISHED"),
                new Criteria().orOperator(
                    Criteria.where("publicationAudit.auditStatus").is("PENDING"),
                    Criteria.where("publicationAudit.notificationStatus").is("PENDING")));
            Query query = Query.query(lastReplacementId == null ? pending
                : new Criteria().andOperator(pending, Criteria.where("_id").gt(lastReplacementId)))
                .with(Sort.by(Sort.Direction.ASC, "_id")).limit(100);
            List<ReplacementRule> batch = mongo.find(query, ReplacementRule.class);
            if (batch.isEmpty()) break;
            for (ReplacementRule r : batch) {
                try { replacements.retryPublicationSideEffects(r); }
                catch (RuntimeException ex) { log.error("Replacement recovery failed {}", r.getId(), ex); }
            }
            lastReplacementId = batch.get(batch.size() - 1).getId();
            if (lastReplacementId == null || batch.size() < 100) break;
        }
    }
}
