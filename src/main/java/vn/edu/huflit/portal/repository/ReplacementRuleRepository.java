package vn.edu.huflit.portal.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.entity.ReplacementRule;

import java.util.List;

@Repository
public interface ReplacementRuleRepository extends MongoRepository<ReplacementRule, ObjectId> {
    List<ReplacementRule> findByOldCourseCodesContaining(String oldCourseCode);
    List<ReplacementRule> findByMajorIdAndApplicableCohortsContaining(ObjectId majorId, String cohort);
}