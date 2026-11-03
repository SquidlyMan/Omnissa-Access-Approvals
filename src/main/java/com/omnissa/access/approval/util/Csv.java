package com.omnissa.access.approval.util;

/** RFC-4180 field escaping, shared by the CSV export endpoints. */
public final class Csv {

    /** Leading characters a spreadsheet reads as the start of a formula. */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    private Csv() {
    }

    public static String field(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        // An export is opened in Excel or LibreOffice more often than not, and a
        // cell that begins with =, +, -, @ or a control character is evaluated
        // there as a formula — so a request note of "=HYPERLINK(...)" would run
        // on the auditor's machine. Text values get an apostrophe, the
        // spreadsheet convention for "this is text"; numbers are left alone.
        if (!(value instanceof Number) && !text.isEmpty()
                && FORMULA_STARTERS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
