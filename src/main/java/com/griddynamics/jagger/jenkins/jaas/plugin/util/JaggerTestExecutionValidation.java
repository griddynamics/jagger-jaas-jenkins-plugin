package com.griddynamics.jagger.jenkins.jaas.plugin.util;

import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;

import java.net.MalformedURLException;
import java.net.URL;

import static java.lang.String.format;

public class JaggerTestExecutionValidation {

    public static FormValidation checkUrl(String value) {
        try {
            new URL(value);
            return FormValidation.ok();
        } catch (MalformedURLException ex) {
            return FormValidation.error("Invalid url: " + ex.getMessage());
        }
    }

    public static FormValidation checkExecutionStartTimeoutInSeconds(String value) {
        if (StringUtils.isNotEmpty(value)) {
            try {
                int executionStartTimeoutInSeconds = Integer.parseInt(value);
                if (executionStartTimeoutInSeconds < 0) {
                    return FormValidation.error("executionStartTimeoutInSeconds must be >= 0");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error(format("'%s' is not a number", value));
            }
        }
        return FormValidation.ok();
    }
}
