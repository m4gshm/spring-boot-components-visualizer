package io.github.m4gshm.connections;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

@UtilityClass
public class UriUtils {
    public static final String SCHEME_DELIMITER = "://";
    public static final String PATH_DELIMITER = "/";

    public static String subURI(String base, String child) {
        if (base == null) {
            return child;
        } else if (child.startsWith(base)) {
            return child.substring(base.length());
        }
        throw new IllegalArgumentException("subURI bad uris, base: " + base + ", sub:" + child);
    }

    public static String joinURI(String part, String nextPart) {
        return part.endsWith(PATH_DELIMITER)
                || nextPart.startsWith(PATH_DELIMITER)
                || nextPart.contains(SCHEME_DELIMITER)
                ? part + nextPart
                : part + PATH_DELIMITER + nextPart;
    }

    public static List<String> splitURI(String uri) {
        final String scheme, path;
        int schemeEnd = uri.indexOf(SCHEME_DELIMITER);
        if (schemeEnd >= 0) {
            scheme = uri.substring(0, schemeEnd);
            path = uri.substring(schemeEnd + SCHEME_DELIMITER.length());
        } else {
            scheme = null;
            path = uri;
        }

        var parts = new ArrayList<String>();

        if (!path.isBlank()) {
            var first = true;
            var tokenizer = new StringTokenizer(path, PATH_DELIMITER, false);
            while (tokenizer.hasMoreTokens()) {
                var part = tokenizer.nextToken();
                if (first && scheme != null) {
                    part = scheme + SCHEME_DELIMITER + part;
                } else {
                    part = PATH_DELIMITER + part;
                }
                parts.add(part);
                first = false;
            }
        }
        return parts;
    }
}
