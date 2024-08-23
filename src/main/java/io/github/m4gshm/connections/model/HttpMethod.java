package io.github.m4gshm.connections.model;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

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
        return compared == 0 ? this.method.compareTo(o.method) : compared;
    }
}
