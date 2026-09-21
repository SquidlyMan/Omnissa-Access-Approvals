package com.omnissa.access.approval.ui;

/**
 * What the foot of the login page shows: the legal and non-production
 * disclaimer (the default), an operator's own notice, or nothing.
 *
 * <p>The rule, in one place so the page cannot drift from the documentation:
 * <ul>
 *   <li>disclaimer disabled → nothing, whatever else is set;</li>
 *   <li>otherwise a non-blank custom notice → that notice;</li>
 *   <li>otherwise → the disclaimer.</li>
 * </ul>
 *
 * <p>"Disable wins" is deliberate: one switch that reliably blanks the page.
 * The cost is that a custom notice can be silently suppressed by a stale
 * disable flag, which is why {@link LoginNoticeSettings} logs that case at
 * startup.
 */
public record LoginNotice(String kind, String title, String text) {

    public static final String DEFAULT_TITLE = "Notice";

    public static final LoginNotice DISCLAIMER = new LoginNotice("disclaimer", null, null);
    public static final LoginNotice NONE = new LoginNotice("none", null, null);

    public static LoginNotice resolve(boolean disclaimerDisabled, String customTitle, String customText) {
        if (disclaimerDisabled) {
            return NONE;
        }
        if (isCustom(customText)) {
            String title = customTitle == null || customTitle.isBlank() ? DEFAULT_TITLE : customTitle.trim();
            return new LoginNotice("custom", title, unescape(customText));
        }
        return DISCLAIMER;
    }

    static boolean isCustom(String customText) {
        return customText != null && !customText.isBlank();
    }

    /**
     * Environment values are one line; a literal {@code \n} in the value is the
     * documented way to ask for a line break. Nothing else is interpreted — the
     * page renders the text as text.
     */
    static String unescape(String text) {
        return text.replace("\\n", "\n").strip();
    }
}
