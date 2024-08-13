package service1.db.mongo;

import org.springframework.data.repository.CrudRepository;
import service1.db.mongo.model.DocumentEntity;

public interface DocumentRepository extends CrudRepository<DocumentEntity, String> {
}
