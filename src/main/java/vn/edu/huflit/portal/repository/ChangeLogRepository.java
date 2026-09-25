package vn.edu.huflit.portal.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.entity.ChangeLog;

import java.util.List;

@Repository
public interface ChangeLogRepository extends MongoRepository<ChangeLog, ObjectId> {
    List<ChangeLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, ObjectId entityId);
}