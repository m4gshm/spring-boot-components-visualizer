package io.github.m4gshm.components.visualizer.model;
import lombok.var;

import io.github.m4gshm.components.visualizer.eval.result.Result;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import static lombok.AccessLevel.PRIVATE;
import static org.apache.commons.lang3.StringUtils.isBlank;


@Data
@Builder(toBuilder = true)
@RequiredArgsConstructor
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class Interface {
    Object core;
    CharSequence name;
    String id;
    Direction direction;
    InterfaceType type;
    Call call;
    MethodId methodSource;
    Result evalSource;

    public CharSequence getName() {
        var name = this.name != null ? this.name : core != null
                ? core instanceof CharSequence ? (CharSequence) core : core.toString() : null;
        if (name == null) {
            name = "!!NULL!!";
        } else if (isBlank(name)) {
            name = "!!BLANK!!";
        }
        return name;
    }

    public String getId() {
        return id != null ? id : core != null ? core.toString() : String.valueOf(getName());
    }

    public IntrfaceKey toKey() {
        return IntrfaceKey.builder().id(getId()).direction(getDirection()).type(getType()).build();
    }

    public enum Call {
        external, scheduled;
    }

    public static class InterfaceBuilder {

    }

}
