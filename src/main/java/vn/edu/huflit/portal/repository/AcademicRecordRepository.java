package vn.edu.huflit.portal.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.entity.AcademicRecord;

import java.util.Optional;
import java.util.List;

@Repository
public interface AcademicRecordRepository extends MongoRepository<AcademicRecord, ObjectId> {
    List<AcademicRecord> findByStudentId(ObjectId studentId);
    Optional<AcademicRecord> findByStudentIdAndCourseCode(ObjectId studentId, String courseCode);
    List<AcademicRecord> findByStudentIdAndCurriculumId(ObjectId studentId, ObjectId curriculumId);
}