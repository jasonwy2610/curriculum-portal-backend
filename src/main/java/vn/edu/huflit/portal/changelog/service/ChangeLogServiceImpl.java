package vn.edu.huflit.portal.changelog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import vn.edu.huflit.portal.changelog.dto.ChangeLogResponse;
import vn.edu.huflit.portal.changelog.model.ChangeLog;
import vn.edu.huflit.portal.changelog.repository.ChangeLogRepository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeLogServiceImpl implements ChangeLogService {

    private final ChangeLogRepository changeLogRepository;
    private final MongoTemplate mongoTemplate;

    @Override
    public ChangeLog recordChange(String entityType, ObjectId entityId, String action,
                                  List<ChangeLog.FieldChangeItem> changes, ObjectId changedBy) {
        if (entityType == null || entityType.trim().isEmpty()) {
            throw new IllegalArgumentException("entityType không được để trống");
        }
        if (entityId == null) {
            throw new IllegalArgumentException("entityId không được để trống");
        }
        if (action == null || action.trim().isEmpty()) {
            throw new IllegalArgumentException("action không được để trống");
        }

        ChangeLog changeLog = ChangeLog.builder()
                .entityType(entityType.trim())
                .entityId(entityId)
                .action(action.trim().toUpperCase())
                .changes(changes != null ? changes : new ArrayList<>())
                .changedBy(changedBy)
                .createdAt(new Date())
                .build();

        ChangeLog saved = changeLogRepository.save(changeLog);
        log.info("Recorded audit log: [{}] {} on {} id={} by={}",
                saved.getAction(), saved.getEntityType(), saved.getEntityId(), saved.getChangedBy());
        return saved;
    }

    @Override
    public List<ChangeLogResponse> getChangeLogs(String entityType, String entityId, String action) {
        Query query = new Query();
        List<Criteria> criteriaList = new ArrayList<>();

        if (entityType != null && !entityType.trim().isEmpty()) {
            criteriaList.add(Criteria.where("entityType").is(entityType.trim()));
        }

        if (entityId != null && !entityId.trim().isEmpty()) {
            if (ObjectId.isValid(entityId.trim())) {
                criteriaList.add(Criteria.where("entityId").is(new ObjectId(entityId.trim())));
            } else {
                return new ArrayList<>();
            }
        }

        if (action != null && !action.trim().isEmpty()) {
            criteriaList.add(Criteria.where("action").is(action.trim().toUpperCase()));
        }

        if (!criteriaList.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        }

        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));

        List<ChangeLog> list = mongoTemplate.find(query, ChangeLog.class);
        return list.stream()
                .map(ChangeLogResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<ChangeLogResponse> getCurriculumChangeLogs(String curriculumId) {
        if (curriculumId == null || !ObjectId.isValid(curriculumId.trim())) {
            throw new RuntimeException("ID chương trình đào tạo không hợp lệ: " + curriculumId);
        }
        List<ChangeLog> list = changeLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                "Curriculum", new ObjectId(curriculumId.trim()));
        return list.stream()
                .map(ChangeLogResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<ChangeLogResponse> getCourseChangeLogs(String courseId) {
        if (courseId == null || !ObjectId.isValid(courseId.trim())) {
            throw new RuntimeException("ID học phần không hợp lệ: " + courseId);
        }
        List<ChangeLog> list = changeLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                "Course", new ObjectId(courseId.trim()));
        return list.stream()
                .map(ChangeLogResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
