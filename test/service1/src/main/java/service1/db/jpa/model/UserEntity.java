package service1.db.jpa.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "USER", schema = "AUTH")
public class UserEntity {
    @Id
    private String id;
    private String name;
}
