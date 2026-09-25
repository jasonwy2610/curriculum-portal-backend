package vn.edu.huflit.portal.repository;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import vn.edu.huflit.portal.entity.User;

import java.util.Optional;
import java.util.List;

@Repository
public interface UserRepository extends MongoRepository<User, ObjectId> {
    Optional<User> findByEmail(String email);
    Optional<User> findByStudentCode(String studentCode);
    boolean existsByEmail(String email);
    List<User> findByCohortAndMajorId(String cohort, ObjectId majorId);
}