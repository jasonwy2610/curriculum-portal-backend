package vn.edu.huflit.portal.changelog.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.changelog.model.ChangeLog;

import java.util.List;

@Repository
public interface ChangeLogRepository extends MongoRepository<ChangeLog, ObjectId> {
    List<ChangeLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, ObjectId entityId);
    List<ChangeLog> findByEntityTypeOrderByCreatedAtDesc(String entityType);
    List<ChangeLog> findAllByOrderByCreatedAtDesc();
}
