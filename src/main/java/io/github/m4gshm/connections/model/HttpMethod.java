package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.Map;

import static lombok.AccessLevel.PRIVATE;

@Data
@FieldDefaults(makeFinal = true, level = PRIVATE)
public class HttpMethod implements CharSequence, Comparable<HttpMethod> {
    public static final String ALL = "*";

    String method;
    String path;
    String string;

    @Builder(toBuilder = true)
    public HttpMethod(String method, String path) {
        this.path = path;
        this.method = method;
        this.string = method + ':' + path;
    }

    @Override
    public String toString() {
        return string;
    }

    @Override
    public int length() {
        return string.length();
    }

    @Override
    public char charAt(int index) {
        return string.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return string.subSequence(start, end);
    }

    @Override
    public int compareTo(HttpMethod o) {
        var compared = this.path.compareTo(o.path);
        return compared == 0 ? compareHttpMethodName(this.method, o.method) : compared;
    }

    private int compareHttpMethodName(String method1, String method2) {
        var weights = Map.of(
                "", -1,
                ALL, 0,
                "GET", 1,
                "POST", 2,
                "PUT", 3,
                "DELETE", 4
        );
        var m1 = method1 != null ? method1.toUpperCase() : "";
        var m2 = method2 != null ? method2.toUpperCase() : "";

        var w1 = weights.getOrDefault(m1, 100);
        var w2 = weights.getOrDefault(m2, 100);

        var compared = w1.compareTo(w2);
        return compared == 0 && w1 == 100 ? m1.compareTo(m2) : compared;
    }
}
