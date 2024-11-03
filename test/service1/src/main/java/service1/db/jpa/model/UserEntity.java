package service1.db.jpa.model;

import lombok.Getter;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Getter
@Table(name = "USER", schema = "AUTH")
public class UserEntity {
    @Id
    private String id;
    private String name;
}
