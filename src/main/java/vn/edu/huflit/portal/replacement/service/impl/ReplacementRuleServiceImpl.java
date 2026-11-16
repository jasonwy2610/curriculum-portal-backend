package vn.edu.huflit.portal.replacement.service.impl;

import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
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
import java.time.Instant;
import java.util.*;
import vn.edu.huflit.portal.replacement.service.ReplacementApplicability;

import static vn.edu.huflit.portal.replacement.service.ReplacementApplicability.family;
import static vn.edu.huflit.portal.replacement.service.ReplacementApplicability.published;

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

    private final ReplacementApplicability applicability;

    private boolean draft(ReplacementRule r) { return "DRAFT".equalsIgnoreCase(r.getLifecycleStatus()); }

    private void audit(User actor, ReplacementRule r, String action) {
        changeLogService.recordChange("ReplacementRule", r.getId(), action, List.of(), actor == null ? null : actor.getId());
    }
    @Override public List<ReplacementRule> findApplicable(User user) {
        if (user == null || user.getCurriculumId() == null) return List.of();
        return curriculumRepository.findById(user.getCurriculumId())
            .map(curriculum -> applicability.current(user, curriculum, Instant.now())).orElseGet(List::of);
    }

    @Override public ReplacementRule getApplicable(User user, String id) {
        ObjectId oid = parseId(id);
        return findApplicable(user).stream().filter(r -> oid.equals(r.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc thay thế áp dụng cho sinh viên"));
    }
    @Override public List<ReplacementRule> findAll() { return repository.findAll(); }
    @Override public ReplacementRule create(User actor, ReplacementRule r) {
        validate(r);
        r.setStatus("INACTIVE");
        r.setId(new ObjectId()); r.setCreatedBy(actor.getId());
        r.setLifecycleStatus("DRAFT"); r.setVersionNumber(1);
        r.setPublishedAt(null); r.setPreviousVersionId(null); r.setRuleFamilyId(r.getId());
        r.setEndsAt(null); r.setSupersededAt(null); r.setRetirementRecordedAt(null);
        r.setRetiredBy(null); r.setRetirementReason(null);
        Date now = new Date(); r.setCreatedAt(now); r.setUpdatedAt(now);
        ReplacementRule saved = repository.insert(r);
        audit(actor, saved, "CREATE_DRAFT");
        return saved;
    }
    @Override public ReplacementRule update(User actor, String id, ReplacementRule input) {
        ReplacementRule current = repository.findById(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc thay thế"));
        if (!draft(current)) throw new IllegalArgumentException("Quy tắc đã công bố không thể sửa; hãy tạo phiên bản mới");
        validate(input);
        ReplacementRule saved = mongoTemplate.findAndModify(draftSnapshot(current),
            businessFields(input).set("updatedAt", new Date()),
            FindAndModifyOptions.options().returnNew(true), ReplacementRule.class);
        if (saved == null) throw new IllegalStateException("Bản nháp đã thay đổi; vui lòng tải lại");
        audit(actor, saved, "UPDATE_DRAFT");
        return saved;
    }
    @Override public ReplacementRule createRevision(User actor, String publishedId) {
        ReplacementRule old = repository.findById(parseId(publishedId))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc"));
        if (!published(old)) throw new IllegalArgumentException("Chỉ có thể tạo phiên bản từ quy tắc đã công bố");
        ObjectId familyId = family(old);
        List<ReplacementRule> versions = versions(old);
        if (versions.stream().anyMatch(this::draft)) throw new IllegalArgumentException("Đã có phiên bản nháp cho quy tắc này");
        ReplacementRule latest = versions.getLast();
        if (!old.getId().equals(latest.getId()))
            throw new IllegalArgumentException("Chỉ tạo bản nháp từ phiên bản công bố mới nhất");
        int next = (latest.getVersionNumber() == null ? 1 : latest.getVersionNumber()) + 1;
        ReplacementRule revision = ReplacementRule.builder()
                .id(deterministicId("replacement-revision:" + familyId + ":" + next))
                .ruleFamilyId(familyId).previousVersionId(old.getId()).versionNumber(next)
                .ruleType(old.getRuleType()).oldCourseCodes(safe(old.getOldCourseCodes()))
                .newCourseCodes(safe(old.getNewCourseCodes())).majorId(old.getMajorId())
                .applicableCohorts(safe(old.getApplicableCohorts()))
                .effectiveDate(null).reason(old.getReason()).note(old.getNote())
                .status("INACTIVE").lifecycleStatus("DRAFT").createdBy(actor.getId())
                .createdAt(new Date()).updatedAt(new Date()).build();
        ReplacementRule saved;
        try {
            saved = repository.insert(revision);
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException("Phiên bản kế tiếp đã được tạo; vui lòng tải lại");
        }
        audit(actor, saved, "CREATE_REVISION");
        return saved;
    }
    @Override public ReplacementRule validateForPublication(String draftId) {
        ReplacementRule rule = loadDraft(draftId);
        checkPublication(rule, new Date());
        return rule;
    }

    @Override public ReplacementRule publish(User actor, String draftId) {
        ReplacementRule r = loadDraft(draftId);
        Query query = draftSnapshot(r);
        Date now = new Date();
        checkPublication(r, now);
        Update update = businessFields(r).set("status", "ACTIVE")
                .set("lifecycleStatus", "PUBLISHED")
                .unset("endsAt").unset("retirementRecordedAt").unset("retiredBy").unset("retirementReason")
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

    @Override public ReplacementRule retire(User actor, String publishedId, Date requestedEnd, String reason) {
        ReplacementRule rule = repository.findById(parseId(publishedId))
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc"));
        if (!published(rule) || !"ACTIVE".equalsIgnoreCase(rule.getStatus()))
            throw new IllegalArgumentException("Chỉ ngừng áp dụng quy tắc đã công bố");
        if (rule.getEndsAt() != null) {
            if (requestedEnd == null || requestedEnd.equals(rule.getEndsAt())) return rule;
            throw new IllegalArgumentException("Quy tắc đã ghi nhận thời điểm kết thúc");
        }
        Date now = new Date();
        Date end = requestedEnd == null ? now : requestedEnd;
        if (end.before(now)) throw new IllegalArgumentException("Không được ngừng áp dụng hồi tố");
        List<ReplacementRule> familyVersions = versions(rule);
        var window = ReplacementApplicability.timeline(familyVersions).stream()
            .filter(item -> rule.getId().equals(item.rule().getId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Quy tắc không có khoảng áp dụng"));
        if (window.end() != null && !end.toInstant().isBefore(window.end()))
            throw new IllegalArgumentException("Phiên bản đã có thời điểm kết thúc sớm hơn hoặc bằng thời điểm yêu cầu");
        if (!end.toInstant().isAfter(window.start())) {
            // Cancelling a scheduled successor can extend the predecessor's application.
            var remaining = familyVersions.stream().filter(v -> !rule.getId().equals(v.getId())).toList();
            for (var restored : ReplacementApplicability.timeline(remaining)) {
                if (restored.start().isBefore(window.start()) && restored.contains(window.start()))
                    checkOverlap(restored.rule(), window.start(), restored.end());
            }
        }

        // Store the retirement and its audit metadata together, without altering published content.
        Query query = Query.query(Criteria.where("_id").is(rule.getId())
            .and("lifecycleStatus").is(rule.getLifecycleStatus()).and("status").is(rule.getStatus())
            .and("endsAt").is(null));
        Update update = new Update().set("endsAt", end).set("retirementRecordedAt", now)
            .set("retiredBy", actor.getId()).set("retirementReason", reason == null ? null : reason.trim())
            .set("updatedAt", now);
        ReplacementRule saved = mongoTemplate.findAndModify(query, update,
            FindAndModifyOptions.options().returnNew(true), ReplacementRule.class);
        if (saved == null) throw new IllegalStateException("Quy tắc đã thay đổi; vui lòng tải lại");
        try {
            changeLogService.recordChange("ReplacementRule", saved.getId(), "RETIRE",
                List.of(new ChangeLog.FieldChangeItem("endsAt", null, end)), actor.getId());
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Replacement retirement audit failed {}", saved.getId(), ex);
        }
        return saved;
    }

    private ReplacementRule loadDraft(String id) {
        ReplacementRule rule = repository.findById(parseId(id))
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc"));
        if (!draft(rule)) throw new IllegalArgumentException("Chỉ công bố bản nháp");
        return rule;
    }

    private void checkPublication(ReplacementRule rule, Date now) {
        validate(rule);
        if (rule.getEffectiveDate() == null) rule.setEffectiveDate(now);
        Instant start = (rule.getEffectiveDate().after(now) ? rule.getEffectiveDate() : now).toInstant();
        List<ReplacementRule> versions = versions(rule);
        ObjectId predecessor = versions.stream().filter(ReplacementApplicability::published)
            .reduce((first, last) -> last).map(ReplacementRule::getId).orElse(null);
        if (!Objects.equals(predecessor, rule.getPreviousVersionId()))
            throw new IllegalArgumentException("Bản nháp phải kế thừa phiên bản công bố mới nhất");
        if (ReplacementApplicability.timeline(versions).stream().anyMatch(window -> !start.isAfter(window.start())))
            throw new IllegalArgumentException("Ngày bắt đầu áp dụng phải sau phiên bản trước");

        checkOverlap(rule, start, null);
    }

    private void checkOverlap(ReplacementRule rule, Instant start, Instant end) {
        boolean conflict = ReplacementApplicability.timeline(repository.findAll()).stream().anyMatch(window -> {
            ReplacementRule other = window.rule();
            return (window.end() == null || start.isBefore(window.end()))
                && (end == null || window.start().isBefore(end))
                && !Objects.equals(family(other), family(rule))
                && Objects.equals(other.getMajorId(), rule.getMajorId())
                && !Collections.disjoint(safe(other.getApplicableCohorts()), rule.getApplicableCohorts())
                && !Collections.disjoint(safe(other.getOldCourseCodes()), rule.getOldCourseCodes());
        });
        if (conflict) throw new IllegalArgumentException("Quy tắc độc lập xung đột trên cùng môn gốc, khóa và thời gian áp dụng");
    }

    private Query draftSnapshot(ReplacementRule rule) {
        Criteria criteria = Criteria.where("_id").is(rule.getId()).and("lifecycleStatus").is("DRAFT");
        businessFields(rule).getUpdateObject().get("$set", org.bson.Document.class)
            .forEach((field, value) -> criteria.and(field).is(value));
        return Query.query(criteria);
    }

    private Update businessFields(ReplacementRule rule) {
        return new Update().set("ruleType", rule.getRuleType()).set("oldCourseCodes", rule.getOldCourseCodes())
            .set("newCourseCodes", rule.getNewCourseCodes()).set("majorId", rule.getMajorId())
            .set("applicableCohorts", rule.getApplicableCohorts()).set("effectiveDate", rule.getEffectiveDate())
            .set("reason", rule.getReason()).set("note", rule.getNote());
    }

    @Override public List<ReplacementRule> history(String id) {
        ReplacementRule r = repository.findById(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy quy tắc"));
        return versions(r);
    }
    private List<ReplacementRule> versions(ReplacementRule r) {
        List<ReplacementRule> versions = new ArrayList<>(repository.findByRuleFamilyId(family(r)));
        if (versions.stream().noneMatch(v -> v.getId().equals(r.getId()))) versions.add(r);
        ObjectId rootId = family(r);
        if (versions.stream().noneMatch(v -> rootId.equals(v.getId())))
            repository.findById(rootId).ifPresent(versions::add);
        versions.sort(Comparator.comparing(v -> v.getVersionNumber() == null ? 1 : v.getVersionNumber()));
        return versions;
    }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
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
        return deterministicId("replacement-notification:" + ruleId.toHexString());
    }

    private ObjectId deterministicId(String key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
