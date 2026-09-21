package com.omnissa.access.approval.ui;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The operator's choice for the login-page notice, read once and logged once.
 *
 * <p>Both switches change what an unauthenticated visitor is told about this
 * tool, so the startup log records the choice the way it records the
 * anonymous-ingest acknowledgement: a disabled disclaimer is a WARN line, and a
 * custom notice that the disable flag renders moot is a WARN naming the notice
 * — that combination is far more likely a stale flag than an intent.
 */
@Component
public class LoginNoticeSettings {

    private static final Logger logger = LoggerFactory.getLogger(LoginNoticeSettings.class);

    private final boolean disclaimerDisabled;
    private final String customTitle;
    private final String customText;

    public LoginNoticeSettings(@Value("${omnissa.ui.disclaimer-disabled:false}") boolean disclaimerDisabled,
                               @Value("${omnissa.ui.login-notice-title:}") String customTitle,
                               @Value("${omnissa.ui.login-notice:}") String customText) {
        this.disclaimerDisabled = disclaimerDisabled;
        this.customTitle = customTitle;
        this.customText = customText;
    }

    public LoginNotice notice() {
        return LoginNotice.resolve(disclaimerDisabled, customTitle, customText);
    }

    @PostConstruct
    void logChoice() {
        boolean custom = LoginNotice.isCustom(customText);
        if (disclaimerDisabled) {
            logger.warn("The legal and non-production disclaimer on the login page is DISABLED "
                    + "(OMNISSA_UI_DISCLAIMER_DISABLED=true). The login page shows no notice.");
            if (custom) {
                logger.warn("OMNISSA_UI_LOGIN_NOTICE is set but IGNORED because OMNISSA_UI_DISCLAIMER_DISABLED=true "
                        + "— disabling wins. Set it to false to show the custom notice.");
            }
        } else if (custom) {
            logger.info("The login page shows a custom notice titled '{}' in place of the legal and "
                    + "non-production disclaimer (OMNISSA_UI_LOGIN_NOTICE).", notice().title());
        }
    }
}
