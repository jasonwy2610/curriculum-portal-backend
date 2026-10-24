package vn.edu.huflit.portal.replacement.service;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import java.util.List;
public interface ReplacementRuleService {
    List<ReplacementRule> findApplicable(User user);
    ReplacementRule getApplicable(User user, String id);
    List<ReplacementRule> findAll();
    ReplacementRule create(User actor, ReplacementRule rule);
    ReplacementRule update(User actor, String id, ReplacementRule rule);
}
