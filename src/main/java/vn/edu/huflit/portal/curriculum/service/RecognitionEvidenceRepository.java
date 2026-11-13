package vn.edu.huflit.portal.curriculum.service;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface RecognitionEvidenceRepository extends MongoRepository<RecognitionEvidence,ObjectId> {
    List<RecognitionEvidence> findByStudentIdAndCurriculumId(ObjectId studentId, ObjectId curriculumId);
}
