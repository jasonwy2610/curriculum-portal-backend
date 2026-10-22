package vn.edu.huflit.portal.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.service.ReplacementRuleService;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReplacementRuleServiceImpl implements ReplacementRuleService {
    private final ReplacementRuleRepository repository;

    @Override public List<ReplacementRule> findApplicable(User user) {
        if (user == null || user.getMajorId() == null || !StringUtils.hasText(user.getCohort()))
            return List.of();
        return repository.findByMajorIdAndApplicableCohortsContaining(user.getMajorId(), user.getCohort())
                .stream().filter(r -> "ACTIVE".equalsIgnoreCase(r.getStatus()))
                .filter(r -> r.getEffectiveDate() == null || !r.getEffectiveDate().after(new Date()))
                .toList();
    }
    @Override public ReplacementRule getApplicable(User user, String id) {
        ObjectId oid = parseId(id);
        return findApplicable(user).stream().filter(r -> oid.equals(r.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc thay thế áp dụng cho sinh viên"));
    }
    @Override public List<ReplacementRule> findAll() { return repository.findAll(); }
    @Override public ReplacementRule create(User actor, ReplacementRule r) {
        validate(r);
        r.setId(null); r.setCreatedBy(actor.getId());
        if (!StringUtils.hasText(r.getStatus())) r.setStatus("ACTIVE");
        r.setCreatedAt(new Date()); r.setUpdatedAt(new Date());
        return repository.save(r);
    }
    @Override public ReplacementRule update(User actor, String id, ReplacementRule input) {
        validate(input);
        ReplacementRule current = repository.findById(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc thay thế"));
        current.setRuleType(input.getRuleType()); current.setOldCourseCodes(input.getOldCourseCodes());
        current.setNewCourseCodes(input.getNewCourseCodes()); current.setMajorId(input.getMajorId());
        current.setApplicableCohorts(input.getApplicableCohorts()); current.setEffectiveDate(input.getEffectiveDate());
        current.setReason(input.getReason()); current.setNote(input.getNote()); current.setStatus(input.getStatus());
        current.setUpdatedAt(new Date());
        return repository.save(current);
    }
    private void validate(ReplacementRule r) {
        if (r == null || r.getOldCourseCodes() == null || r.getOldCourseCodes().isEmpty())
            throw new IllegalArgumentException("Phải có ít nhất một học phần cũ");
        if (r.getNewCourseCodes() == null || r.getNewCourseCodes().isEmpty())
            throw new IllegalArgumentException("Phải có ít nhất một học phần thay thế");
        if (!List.of("1-1","1-N","N-1").contains(r.getRuleType()))
            throw new IllegalArgumentException("ruleType chỉ nhận 1-1, 1-N hoặc N-1");
        if (r.getMajorId() == null || r.getApplicableCohorts() == null || r.getApplicableCohorts().isEmpty())
            throw new IllegalArgumentException("Phải xác định ngành và khóa áp dụng");
    }
    private ObjectId parseId(String id) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("ID không hợp lệ");
        return new ObjectId(id);
    }
}
