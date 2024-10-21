package service1.db.jpa.model;

import javax.persistence.Entity;
import javax.persistence.Id;

@Entity
public class SimpleEntity {
    @Id
    private String id;
    private String name;
}
