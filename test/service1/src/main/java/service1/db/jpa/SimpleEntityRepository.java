package service1.db.jpa;

import org.springframework.data.repository.CrudRepository;
import service1.db.jpa.model.SimpleEntity;

public interface SimpleEntityRepository extends CrudRepository<SimpleEntity, String> {
}
