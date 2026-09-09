package com.gatehousemc.config;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EnvExpander {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvExpander() {}

    public static String expand(String value, Map<String, String> environment) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        int searchFrom = 0;
        int placeholderStart;
        while ((placeholderStart = value.indexOf("${", searchFrom)) >= 0) {
            matcher.region(placeholderStart, value.length());
            if (!matcher.lookingAt()) {
                throw new IllegalArgumentException("Malformed environment placeholder");
            }
            searchFrom = matcher.end();
        }

        matcher.reset();
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = environment.get(matcher.group(1));
            if (replacement == null) throw new IllegalArgumentException("Environment variable is not set: " + matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
