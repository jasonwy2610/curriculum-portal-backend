package vn.edu.huflit.portal.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.entity.Curriculum;

import java.util.Optional;
import java.util.List;

@Repository
public interface CurriculumRepository extends MongoRepository<Curriculum, ObjectId> {
    Optional<Curriculum> findByCurriculumCode(String curriculumCode);
    Optional<Curriculum> findByMajorIdAndCohort(ObjectId majorId, String cohort);
    List<Curriculum> findByMajorId(ObjectId majorId);
    List<Curriculum> findByStatus(String status);
}