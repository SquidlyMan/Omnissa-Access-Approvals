package com.omnissa.access.approval.util;

/** RFC-4180 field escaping, shared by the CSV export endpoints. */
public final class Csv {

    private Csv() {
    }

    public static String field(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
