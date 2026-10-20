package vn.edu.huflit.portal.curriculum.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.curriculum.model.Curriculum;

import java.util.List;
import java.util.Optional;

@Repository
public interface CurriculumRepository extends MongoRepository<Curriculum, ObjectId> {
    boolean existsByCurriculumCode(String curriculumCode);
    Optional<Curriculum> findByCurriculumCode(String curriculumCode);
    Optional<Curriculum> findByMajorIdAndCohort(ObjectId majorId, String cohort);
    List<Curriculum> findByMajorId(ObjectId majorId);
    List<Curriculum> findByStatus(String status);
    List<Curriculum> findByCohort(String cohort);
}
