package vn.edu.huflit.portal.replacement.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.util.StringUtils;
import vn.edu.huflit.portal.entity.ReplacementRule;
import vn.edu.huflit.portal.changelog.service.ChangeLogService;
import vn.edu.huflit.portal.changelog.model.ChangeLog;
import vn.edu.huflit.portal.entity.Notification;
import vn.edu.huflit.portal.entity.User;
import vn.edu.huflit.portal.notification.repository.NotificationRepository;
import vn.edu.huflit.portal.repository.UserRepository;
import vn.edu.huflit.portal.course.repository.CourseRepository;
import vn.edu.huflit.portal.major.repository.MajorRepository;
import vn.edu.huflit.portal.curriculum.repository.CurriculumRepository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;
import vn.edu.huflit.portal.replacement.repository.ReplacementRuleRepository;
import vn.edu.huflit.portal.replacement.service.ReplacementRuleService;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReplacementRuleServiceImpl implements ReplacementRuleService {
    private final ReplacementRuleRepository repository;
    private final ChangeLogService changeLogService;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final MajorRepository majorRepository;
    private final CurriculumRepository curriculumRepository;
    private final MongoTemplate mongoTemplate;

    private boolean published(ReplacementRule r) {
        return "PUBLISHED".equalsIgnoreCase(r.getLifecycleStatus())
                || (r.getLifecycleStatus() == null && "ACTIVE".equalsIgnoreCase(r.getStatus()));
    }
    private boolean draft(ReplacementRule r) { return "DRAFT".equalsIgnoreCase(r.getLifecycleStatus()); }
    private ObjectId family(ReplacementRule r) { return r.getRuleFamilyId() == null ? r.getId() : r.getRuleFamilyId(); }
    private void audit(User actor, ReplacementRule r, String action) {
        changeLogService.recordChange("ReplacementRule", r.getId(), action, List.of(), actor == null ? null : actor.getId());
    }
    @Override public List<ReplacementRule> findApplicable(User user) {
        if (user == null || user.getMajorId() == null || !StringUtils.hasText(user.getCohort())) return List.of();
        Date now = new Date();
        Map<ObjectId, ReplacementRule> latest = new LinkedHashMap<>();
        for (ReplacementRule r : repository.findByMajorIdAndApplicableCohortsContaining(user.getMajorId(), user.getCohort())) {
            if (!published(r) || !"ACTIVE".equalsIgnoreCase(r.getStatus())
                    || (r.getEffectiveDate() != null && r.getEffectiveDate().after(now))) continue;
            ObjectId key = family(r);
            ReplacementRule prev = latest.get(key);
            if (prev == null || compareVersion(r, prev) > 0) latest.put(key, r);
        }
        return new ArrayList<>(latest.values());
    }
    private int compareVersion(ReplacementRule a, ReplacementRule b) {
        int va = a.getVersionNumber() == null ? 1 : a.getVersionNumber();
        int vb = b.getVersionNumber() == null ? 1 : b.getVersionNumber();
        int c = Integer.compare(va, vb);
        if (c != 0) return c;
        Date da = a.getPublishedAt() == null ? a.getCreatedAt() : a.getPublishedAt();
        Date db = b.getPublishedAt() == null ? b.getCreatedAt() : b.getPublishedAt();
        return Comparator.nullsFirst(Date::compareTo).compare(da, db);
    }
    @Override public ReplacementRule getApplicable(User user, String id) {
        ObjectId oid = parseId(id);
        return findApplicable(user).stream().filter(r -> oid.equals(r.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc thay thế áp dụng cho sinh viên"));
    }
    @Override public List<ReplacementRule> findAll() { return repository.findAll(); }
    @Override public ReplacementRule create(User actor, ReplacementRule r) {
        r.setStatus("INACTIVE");
        validate(r);
        r.setId(null); r.setCreatedBy(actor.getId());
        r.setLifecycleStatus("DRAFT"); r.setVersionNumber(1);
        r.setPublishedAt(null); r.setPreviousVersionId(null); r.setRuleFamilyId(null);
        Date now = new Date(); r.setCreatedAt(now); r.setUpdatedAt(now);
        ReplacementRule saved = repository.save(r);
        saved.setRuleFamilyId(saved.getId());
        saved = repository.save(saved);
        audit(actor, saved, "CREATE_DRAFT");
        return saved;
    }
    @Override public ReplacementRule update(User actor, String id, ReplacementRule input) {
        ReplacementRule current = repository.findById(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc thay thế"));
        if (!draft(current)) throw new IllegalArgumentException("Quy tắc đã công bố không thể sửa; hãy tạo phiên bản mới");
        input.setStatus("INACTIVE"); validate(input);
        current.setRuleType(input.getRuleType()); current.setOldCourseCodes(input.getOldCourseCodes());
        current.setNewCourseCodes(input.getNewCourseCodes()); current.setMajorId(input.getMajorId());
        current.setApplicableCohorts(input.getApplicableCohorts()); current.setEffectiveDate(input.getEffectiveDate());
        current.setReason(input.getReason()); current.setNote(input.getNote());
        current.setUpdatedAt(new Date());
        ReplacementRule saved = repository.save(current);
        audit(actor, saved, "UPDATE_DRAFT");
        return saved;
    }
    @Override public ReplacementRule createRevision(User actor, String publishedId) {
        ReplacementRule old = repository.findById(parseId(publishedId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc"));
        if (!published(old)) throw new IllegalArgumentException("Chỉ có thể tạo phiên bản từ quy tắc đã công bố");
        ObjectId familyId = family(old);
        List<ReplacementRule> versions = history(publishedId);
        if (versions.stream().anyMatch(this::draft)) throw new IllegalArgumentException("Đã có phiên bản nháp cho quy tắc này");
        int next = versions.stream().map(ReplacementRule::getVersionNumber).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(1) + 1;
        ReplacementRule revision = ReplacementRule.builder()
                .ruleFamilyId(familyId).previousVersionId(old.getId()).versionNumber(next)
                .ruleType(old.getRuleType()).oldCourseCodes(new ArrayList<>(old.getOldCourseCodes()))
                .newCourseCodes(new ArrayList<>(old.getNewCourseCodes())).majorId(old.getMajorId())
                .applicableCohorts(new ArrayList<>(old.getApplicableCohorts()))
                .effectiveDate(null).reason(old.getReason()).note(old.getNote())
                .status("INACTIVE").lifecycleStatus("DRAFT").createdBy(actor.getId())
                .createdAt(new Date()).updatedAt(new Date()).build();
        ReplacementRule saved = repository.save(revision);
        audit(actor, saved, "CREATE_REVISION");
        return saved;
    }
    @Override public ReplacementRule publish(User actor, String draftId) {
        ReplacementRule r = repository.findById(parseId(draftId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc"));
        if (!draft(r)) throw new IllegalArgumentException("Chỉ công bố bản nháp");
        // Validate before writing. Validation also normalizes the course/cohort lists.
        r.setStatus("ACTIVE");
        validate(r);
        Date now = new Date();
        if (r.getEffectiveDate() == null) r.setEffectiveDate(now);
        if (r.getPreviousVersionId() != null) {
            ReplacementRule previous = repository.findById(r.getPreviousVersionId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiên bản trước"));
            if (!published(previous)) throw new IllegalArgumentException("Phiên bản trước chưa công bố");
            if (previous.getEffectiveDate() != null && !r.getEffectiveDate().after(previous.getEffectiveDate()))
                throw new IllegalArgumentException("Ngày hiệu lực mới phải sau phiên bản trước");
        }
        // Check every cohort; the former implementation checked only the first one.
        for (ReplacementRule other : repository.findAll()) {
            if (Objects.equals(other.getId(), r.getId()) || !published(other)
                    || !"ACTIVE".equalsIgnoreCase(other.getStatus())
                    || Objects.equals(family(other), family(r))
                    || !Objects.equals(other.getMajorId(), r.getMajorId())) continue;
            if (!Collections.disjoint(other.getApplicableCohorts() == null ? List.of() : other.getApplicableCohorts(),
                    r.getApplicableCohorts())
                    && !Collections.disjoint(other.getOldCourseCodes() == null ? List.of() : other.getOldCourseCodes(),
                    r.getOldCourseCodes()))
                throw new IllegalArgumentException("Có quy tắc độc lập xung đột trên cùng môn gốc và khóa áp dụng");
        }
        Query query = Query.query(Criteria.where("_id").is(r.getId())
                .and("lifecycleStatus").is("DRAFT")
                .and("oldCourseCodes").is(r.getOldCourseCodes())
                .and("newCourseCodes").is(r.getNewCourseCodes())
                .and("applicableCohorts").is(r.getApplicableCohorts()));
        Update update = new Update().set("status", "ACTIVE")
                .set("lifecycleStatus", "PUBLISHED")
                .set("effectiveDate", r.getEffectiveDate())
                .set("publishedAt", now).set("updatedAt", now)
                .set("publicationAudit.auditStatus", "PENDING")
                .set("publicationAudit.notificationStatus", "PENDING");
        ReplacementRule saved = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), ReplacementRule.class);
        if (saved == null) throw new IllegalStateException("Quy tắc đã thay đổi hoặc đã công bố; vui lòng tải lại");
        // The publication is already durable. Record delivery failures for repair
        // rather than returning an error that would encourage duplicate publication.
        try {
            audit(actor, saved, "PUBLISH");
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("publicationAudit.auditStatus", "RECORDED"), ReplacementRule.class);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Replacement audit failed {}", saved.getId(), ex);
        }
        try {
            notifyAffectedStudents(actor, saved, r.getPreviousVersionId() != null);
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("publicationAudit.notificationStatus", "RECORDED"), ReplacementRule.class);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Replacement notification failed {}", saved.getId(), ex);
        }
        return saved;
    }
    @Override public List<ReplacementRule> history(String id) {
        ReplacementRule r = repository.findById(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc"));
        List<ReplacementRule> versions = new ArrayList<>(repository.findByRuleFamilyId(family(r)));
        if (versions.stream().noneMatch(v -> v.getId().equals(r.getId()))) versions.add(r);
        versions.sort(Comparator.comparing(v -> v.getVersionNumber() == null ? 1 : v.getVersionNumber()));
        return versions;
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

        if (r.getMajorId() == null || !majorRepository.existsById(r.getMajorId()))
            throw new IllegalArgumentException("Ngành đào tạo không tồn tại");
        if (!List.of("ACTIVE", "INACTIVE").contains(r.getStatus() == null ? "ACTIVE" : r.getStatus().toUpperCase()))
            throw new IllegalArgumentException("Trạng thái quy tắc không hợp lệ");
        for (String code : r.getOldCourseCodes()) {
            if (!courseRepository.existsByCourseCode(code)) throw new IllegalArgumentException("Học phần cũ không tồn tại: " + code);
        }
        for (String code : r.getNewCourseCodes()) {
            if (!courseRepository.existsByCourseCode(code)) throw new IllegalArgumentException("Học phần mới không tồn tại: " + code);
            if (r.getOldCourseCodes().contains(code)) throw new IllegalArgumentException("Học phần cũ và mới không được trùng: " + code);
        }
        List<Curriculum> programs = curriculumRepository.findByMajorId(r.getMajorId());
        for (String cohort : r.getApplicableCohorts()) {
            if (!cohort.matches("K20[0-9]{2}") || programs.stream().noneMatch(c -> cohort.equals(c.getCohort())))
                throw new IllegalArgumentException("Khóa áp dụng không thuộc CTĐT của ngành: " + cohort);
        }
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
        // Deterministic key makes retries idempotent even after a crash between
        // inserting the notification and marking the publication delivered.
        // Respect pre-existing notifications created by older application versions.
        if (notificationRepository.existsByReplacementRuleId(rule.getId())) return;
        ObjectId notificationId = deterministicNotificationId(rule.getId());
        Query notificationQuery = Query.query(Criteria.where("_id").is(notificationId));
        Update notificationUpdate = new Update()
            .setOnInsert("type", notification.getType())
            .setOnInsert("title", notification.getTitle())
            .setOnInsert("message", notification.getMessage())
            .setOnInsert("replacementRuleId", rule.getId())
            .setOnInsert("createdBy", notification.getCreatedBy())
            .setOnInsert("createdAt", notification.getCreatedAt())
            .setOnInsert("readBy", new ArrayList<ObjectId>())
            .addToSet("recipientIds").each(recipients.keySet().toArray());
        mongoTemplate.upsert(notificationQuery, notificationUpdate, Notification.class);
    }

    private ObjectId deterministicNotificationId(ObjectId ruleId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("replacement-notification:" + ruleId.toHexString())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new ObjectId(Arrays.copyOf(digest, 12));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // Safe for recovery jobs: published rules can be retried without republishing.
    public void retryPublicationSideEffects(ReplacementRule rule) {
        if (!published(rule)) return;
        User actor = rule.getCreatedBy() == null ? null : userRepository.findById(rule.getCreatedBy()).orElse(null);
        if (!"RECORDED".equalsIgnoreCase(publicationStatus(rule, "auditStatus"))) {
            // Do not duplicate an audit event already written before a crash.
            boolean exists = mongoTemplate.exists(Query.query(Criteria.where("entityType").is("ReplacementRule")
                .and("entityId").is(rule.getId()).and("action").is("PUBLISH")), ChangeLog.class);
            if (!exists) audit(actor, rule, "PUBLISH");
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(rule.getId())),
                new Update().set("publicationAudit.auditStatus", "RECORDED"), ReplacementRule.class);
        }
        if (!"RECORDED".equalsIgnoreCase(publicationStatus(rule, "notificationStatus"))) {
            notifyAffectedStudents(actor, rule, rule.getPreviousVersionId() != null);
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(rule.getId())),
                new Update().set("publicationAudit.notificationStatus", "RECORDED"), ReplacementRule.class);
        }
    }

    private String publicationStatus(ReplacementRule rule, String field) {
        org.bson.Document doc = mongoTemplate.getCollection("replacementRules")
            .find(new org.bson.Document("_id", rule.getId())).first();
        if (doc == null) return null;
        org.bson.Document audit = doc.get("publicationAudit", org.bson.Document.class);
        return audit == null ? null : audit.getString(field);
    }

    private ObjectId parseId(String id) {
        if (!ObjectId.isValid(id)) throw new IllegalArgumentException("ID không hợp lệ");
        return new ObjectId(id);
    }
}
