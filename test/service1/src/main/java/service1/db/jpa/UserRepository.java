package service1.db.jpa;

import org.springframework.data.repository.CrudRepository;
import service1.db.jpa.model.UserEntity;

public interface UserRepository extends CrudRepository<UserEntity, String> {
}
