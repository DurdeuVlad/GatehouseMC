package com.gatehousemc.i18n;

/**
 * Static accessor for internationalized messages.
 * Loaded once at startup with the configured language; all user-facing strings
 * go through {@link #get(String, Object...)}.
 */
public final class Messages {
    private static volatile TranslationBundle current;

    private Messages() {}

    public static void load(String language) {
        current = TranslationBundle.load(language);
    }

    private static TranslationBundle ensureLoaded() {
        TranslationBundle bundle = current;
        if (bundle == null) {
            synchronized (Messages.class) {
                bundle = current;
                if (bundle == null) {
                    bundle = TranslationBundle.load("en_us");
                    current = bundle;
                }
            }
        }
        return bundle;
    }

    public static String get(String key) {
        return ensureLoaded().get(key);
    }

    public static String get(String key, Object... args) {
        String template = ensureLoaded().get(key);
        if (args == null || args.length == 0) return template;
        return format(template, args);
    }

    private static String format(String template, Object... args) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            if (template.charAt(i) == '{') {
                int close = template.indexOf('}', i + 1);
                if (close > i) {
                    String indexStr = template.substring(i + 1, close);
                    try {
                        int index = Integer.parseInt(indexStr.trim());
                        if (index >= 0 && index < args.length) {
                            result.append(args[index]);
                        } else {
                            result.append(template, i, close + 1);
                        }
                        i = close + 1;
                        continue;
                    } catch (NumberFormatException ignored) { }
                }
            }
            result.append(template.charAt(i));
            i++;
        }
        return result.toString();
    }
}
