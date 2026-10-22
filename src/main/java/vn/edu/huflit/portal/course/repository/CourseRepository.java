package vn.edu.huflit.portal.course.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.course.model.Course;

import java.util.Optional;
import java.util.List;

@Repository
public interface CourseRepository extends MongoRepository<Course, ObjectId> {
    Optional<Course> findByCourseCode(String courseCode);
    List<Course> findByCourseCodeIn(List<String> courseCodes);
    boolean existsByCourseCode(String courseCode);
}
