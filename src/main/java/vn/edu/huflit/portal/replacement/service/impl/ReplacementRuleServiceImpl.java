package vn.edu.huflit.portal.replacement.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.entity.Notification;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.notification.repository.NotificationRepository;
import vn.edu.huflit.portal.repository.UserRepository;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementRuleService;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReplacementRuleServiceImpl implements ReplacementRuleService {
    private final ReplacementRuleRepository repository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

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
        ReplacementRule saved = repository.save(r);
        notifyAffectedStudents(actor, saved, false);
        return saved;
    }
    @Override public ReplacementRule update(User actor, String id, ReplacementRule input) {
        validate(input);
        ReplacementRule current = repository.findById(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc thay thế"));
        current.setRuleType(input.getRuleType()); current.setOldCourseCodes(input.getOldCourseCodes());
        current.setNewCourseCodes(input.getNewCourseCodes()); current.setMajorId(input.getMajorId());
        current.setApplicableCohorts(input.getApplicableCohorts()); current.setEffectiveDate(input.getEffectiveDate());
        current.setReason(input.getReason()); current.setNote(input.getNote());
        if (StringUtils.hasText(input.getStatus())) current.setStatus(input.getStatus().trim().toUpperCase());
        current.setUpdatedAt(new Date());
        ReplacementRule saved = repository.save(current);
        notifyAffectedStudents(actor, saved, true);
        return saved;
    }
    private void validate(ReplacementRule r) {
        if (r == null) throw new IllegalArgumentException("Thiếu dữ liệu quy tắc thay thế");
        if (!StringUtils.hasText(r.getRuleType())) throw new IllegalArgumentException("Thiếu loại quy tắc thay thế");
        r.setRuleType(r.getRuleType().trim().toUpperCase());
        if (!List.of("1-1", "1-N", "N-1").contains(r.getRuleType()))
            throw new IllegalArgumentException("ruleType chỉ nhận 1-1, 1-N hoặc N-1");

        r.setOldCourseCodes(r.getOldCourseCodes() == null ? new ArrayList<>() : r.getOldCourseCodes().stream()
                .filter(Objects::nonNull).map(String::trim).filter(StringUtils::hasText).distinct().toList());
        r.setNewCourseCodes(r.getNewCourseCodes() == null ? new ArrayList<>() : r.getNewCourseCodes().stream()
                .filter(Objects::nonNull).map(String::trim).filter(StringUtils::hasText).distinct().toList());
        r.setApplicableCohorts(r.getApplicableCohorts() == null ? new ArrayList<>() : r.getApplicableCohorts().stream()
                .filter(Objects::nonNull).map(String::trim).filter(StringUtils::hasText).distinct().toList());

        if (r.getOldCourseCodes().isEmpty()) throw new IllegalArgumentException("Phải có ít nhất một học phần cũ");
        if (r.getNewCourseCodes().isEmpty()) throw new IllegalArgumentException("Phải có ít nhất một học phần thay thế");
        if (r.getMajorId() == null || r.getApplicableCohorts().isEmpty())
            throw new IllegalArgumentException("Phải xác định ngành và ít nhất một khóa áp dụng");
        if ("1-1".equals(r.getRuleType()) && (r.getOldCourseCodes().size() != 1 || r.getNewCourseCodes().size() != 1))
            throw new IllegalArgumentException("Quy tắc 1-1 phải có đúng 1 học phần cũ và 1 học phần mới");
        if ("1-N".equals(r.getRuleType()) && (r.getOldCourseCodes().size() != 1 || r.getNewCourseCodes().size() < 2))
            throw new IllegalArgumentException("Quy tắc 1-N phải có 1 học phần cũ và ít nhất 2 học phần mới");
        if ("N-1".equals(r.getRuleType()) && (r.getOldCourseCodes().size() < 2 || r.getNewCourseCodes().size() != 1))
            throw new IllegalArgumentException("Quy tắc N-1 phải có ít nhất 2 học phần cũ và 1 học phần mới");
    }

    private void notifyAffectedStudents(User actor, ReplacementRule rule, boolean updated) {
        if (rule == null || !"ACTIVE".equalsIgnoreCase(rule.getStatus()) || rule.getMajorId() == null
                || rule.getApplicableCohorts() == null || rule.getApplicableCohorts().isEmpty()) return;

        Map<ObjectId, User> recipients = new LinkedHashMap<>();
        for (String cohort : rule.getApplicableCohorts()) {
            for (User student : userRepository.findByCohortAndMajorId(cohort, rule.getMajorId())) {
                if (student.getId() != null && "STUDENT".equalsIgnoreCase(student.getRole())
                        && !"INACTIVE".equalsIgnoreCase(student.getStatus())) {
                    recipients.put(student.getId(), student);
                }
            }
        }
        if (recipients.isEmpty()) return;

        String oldCodes = String.join(", ", rule.getOldCourseCodes() == null ? List.of() : rule.getOldCourseCodes());
        String newCodes = String.join(", ", rule.getNewCourseCodes() == null ? List.of() : rule.getNewCourseCodes());
        String action = updated ? "được cập nhật" : "mới được tạo";
        Notification notification = Notification.builder()
                .type("COURSE_REPLACEMENT")
                .title(updated ? "Cập nhật quy tắc thay thế học phần" : "Quy tắc thay thế học phần mới")
                .message("Quy tắc thay thế " + action + ": [" + oldCodes + "] → [" + newCodes + "]. "
                        + (StringUtils.hasText(rule.getReason()) ? rule.getReason().trim() : "Vui lòng kiểm tra CTĐT áp dụng."))
                .replacementRuleId(rule.getId())
                .recipientIds(new ArrayList<>(recipients.keySet()))
                .readBy(new ArrayList<>())
                .createdBy(actor == null ? rule.getCreatedBy() : actor.getId())
                .createdAt(new Date())
                .build();
        notificationRepository.save(notification);
    }

    private ObjectId parseId(String id) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("ID không hợp lệ");
        return new ObjectId(id);
    }
}
