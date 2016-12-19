package com.griddynamics.jagger.jenkins.jaas.plugin;

import com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity;
import com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity.TestExecutionStatus;
import com.griddynamics.jagger.jenkins.jaas.plugin.util.JaggerTestExecutionValidation;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.VariableResolver;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity.TestExecutionStatus.PENDING;
import static com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity.TestExecutionStatus.RUNNING;
import static com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity.TestExecutionStatus.TIMEOUT;
import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.startsWith;

public class JaggerTestExecutionBuilder extends Builder {
    private static final int STATUS_POLLING_TIMEOUT_IN_SECONDS = 5;
    private final String jaasEndpoint;
    private final String testProjectUrl;
    private final String envId;
    private final String loadScenarioId;
    private final String executionStartTimeoutInSeconds;

    /*  When using dynamic build parameters constructor is called only the first time,
        that's why these evaluated parameters are needed. They are evaluated on every execution
        and must be cleared before every execution.
    */
    private String evaluatedJaasEndpoint;
    private String evaluatedTestProjectUrl;
    private String evaluatedEnvId;
    private String evaluatedLoadScenarioId;
    private String evaluatedTimeout;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public JaggerTestExecutionBuilder(String jaasEndpoint, String testProjectUrl, String envId, String loadScenarioId,
                                      String executionStartTimeoutInSeconds) {
        this.jaasEndpoint = jaasEndpoint;
        this.testProjectUrl = testProjectUrl;
        this.envId = envId;
        this.loadScenarioId = loadScenarioId;
        this.executionStartTimeoutInSeconds = executionStartTimeoutInSeconds;
    }

    public String getJaasEndpoint() {
        return jaasEndpoint;
    }

    public String getTestProjectUrl() {
        return testProjectUrl;
    }

    public String getEnvId() {
        return envId;
    }

    public String getLoadScenarioId() {
        return loadScenarioId;
    }

    public String getExecutionStartTimeoutInSeconds() {
        return executionStartTimeoutInSeconds;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        parseBuildParams(build, listener);
        startTestExecution(listener);
        return true;
    }

    private void parseBuildParams(AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException {
        final EnvVars envVars = build.getEnvironment(listener);
        final VariableResolver<String> buildVariableResolver = build.getBuildVariableResolver();

        clearEvaluated();
        evaluatedJaasEndpoint = evaluate(jaasEndpoint, buildVariableResolver, envVars);
        evaluatedTestProjectUrl = evaluate(testProjectUrl, buildVariableResolver, envVars);
        evaluatedEnvId = evaluate(envId, buildVariableResolver, envVars);
        evaluatedLoadScenarioId = evaluate(loadScenarioId, buildVariableResolver, envVars);
        evaluatedTimeout = evaluate(executionStartTimeoutInSeconds, buildVariableResolver, envVars);
    }

    private void clearEvaluated() {
        evaluatedJaasEndpoint = null;
        evaluatedEnvId = null;
        evaluatedLoadScenarioId = null;
        evaluatedTestProjectUrl = null;
        evaluatedTimeout = null;
    }

    private String evaluate(String value, VariableResolver<String> vars, Map<String, String> env) {
        String evaluated = Util.replaceMacro(Util.replaceMacro(value, vars), env);
        return StringUtils.isNotBlank(evaluated) ? evaluated : null;
    }

    public void startTestExecution(TaskListener listener) throws InterruptedException, IOException {
        final RestTemplate restTemplate = new RestTemplate();
        final PrintStream logger = listener.getLogger();

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 1: Creating TestExecution request...");
        TestExecutionEntity testExecutionEntity = createTestExecution(logger);

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 2: Sending request to JaaS...");
        TestExecutionEntity sentExecution = sendTestExecutionToJaas(logger, testExecutionEntity, restTemplate);

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 3: Waiting test to start execution...");
        waitTestExecutionStarted(logger, sentExecution.getId(), restTemplate);

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 4: Waiting test to finish execution...");
        waitTestExecutionFinished(logger, sentExecution.getId(), restTemplate);

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 5: Publishing Test execution results...");
        logger.println("Test execution report can be found by the link " + evaluatedJaasEndpoint + "/report?sessionId=valid-session-id");
    }

    private TestExecutionEntity createTestExecution(PrintStream logger) {
        TestExecutionEntity testExecutionEntity = new TestExecutionEntity();
        testExecutionEntity.setEnvId(evaluatedEnvId);
        testExecutionEntity.setLoadScenarioId(evaluatedLoadScenarioId);
        if (StringUtils.isNotEmpty(evaluatedTimeout))
            testExecutionEntity.setExecutionStartTimeoutInSeconds(Long.parseLong(evaluatedTimeout));
        testExecutionEntity.setTestProjectURL(evaluatedTestProjectUrl);

        logger.println(format("JaaS endpoint: %s", evaluatedJaasEndpoint));
        logger.println("Test execution properties:");
        logger.println(format("    Environment ID: %s", evaluatedEnvId));
        if (StringUtils.isNotEmpty(evaluatedLoadScenarioId))
            logger.println(format("    Load scenario ID: %s", evaluatedLoadScenarioId));
        if (StringUtils.isNotEmpty(evaluatedTestProjectUrl))
            logger.println(format("    Test project URL: %s", evaluatedTestProjectUrl));
        if (StringUtils.isNotEmpty(evaluatedTimeout))
            logger.println(format("    Execution start timeout in seconds: %s", evaluatedTimeout));
        return testExecutionEntity;
    }

    private TestExecutionEntity sendTestExecutionToJaas(PrintStream logger, TestExecutionEntity testExecutionEntity, RestTemplate restTemplate) throws AbortException {
        try {
            RequestEntity<TestExecutionEntity> requestEntity = RequestEntity.post(new URI(evaluatedJaasEndpoint + "/executions")).body(testExecutionEntity);
            ResponseEntity<TestExecutionEntity> responseEntity = restTemplate.exchange(requestEntity, TestExecutionEntity.class);
            logger.println("Request successfully sent!\n");
            logger.println("Response status: " + responseEntity.getStatusCodeValue());
            logger.println("Response body: \n" + responseEntity.getBody());
            return responseEntity.getBody();
        } catch (URISyntaxException e) {
            logger.println();
            throw new AbortException("Invalid JaaS endpoint URL: " + e.getMessage());
        } catch (RestClientException ex) {
            logger.println();
            throw new AbortException("Error occurred while sending Test execution to JaaS: " + ex.getMessage());
        }
    }

    private void waitTestExecutionStarted(PrintStream logger, Long executionId, RestTemplate restTemplate) throws AbortException {
        TestExecutionStatus executionStatus = PENDING;
        while (executionStatus == PENDING) {
            executionStatus = pollExecutionStatus(logger, executionId, restTemplate);
            try {
                TimeUnit.SECONDS.sleep(STATUS_POLLING_TIMEOUT_IN_SECONDS);
            } catch (InterruptedException e) {
                logger.println();
                throw new AbortException(format("Error occurred while waiting Test execution with id=%s to start: %s", executionId, e.getMessage()));
            }
        }

        if (executionStatus == TIMEOUT) {
            logger.println();
            throw new AbortException(format("Test execution with id=%s cancelled by timeout.", executionId));
        }
    }

    private void waitTestExecutionFinished(PrintStream logger, Long executionId, RestTemplate restTemplate) throws AbortException {
        TestExecutionStatus executionStatus;
        do {
            executionStatus = pollExecutionStatus(logger, executionId, restTemplate);
            try {
                TimeUnit.SECONDS.sleep(STATUS_POLLING_TIMEOUT_IN_SECONDS);
            } catch (InterruptedException e) {
                logger.println();
                throw new AbortException(format("Error occurred while waiting Test execution with id=%s to finish: %s", executionId, e.getMessage()));
            }
        } while (executionStatus == RUNNING);

        logger.println(format("Test execution with id=%s successfully finished!", executionId));
    }

    private TestExecutionStatus pollExecutionStatus(PrintStream logger, Long executionId, RestTemplate restTemplate) throws AbortException {
        TestExecutionStatus executionStatus;
        try {
            logger.print(format("Polling status of test execution with id=%s ... ", executionId));
            RequestEntity<?> requestEntity = RequestEntity.get(new URI(evaluatedJaasEndpoint + "/executions/" + executionId)).build();
            ResponseEntity<TestExecutionEntity> responseEntity = restTemplate.exchange(requestEntity, TestExecutionEntity.class);
            executionStatus = responseEntity.getBody().getStatus();
            logger.println(executionStatus);
        } catch (URISyntaxException e) {
            logger.println();
            throw new AbortException("Invalid JaaS endpoint URL: " + e.getMessage());
        } catch (RestClientException ex) {
            logger.println();
            throw new AbortException(format("Error occurred while polling status of Test execution with id=%s: %s", executionId, ex.getMessage()));
        }
        return executionStatus;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Jagger Test Execution";
        }

        public FormValidation doCheckJaasEndpoint(@QueryParameter String value) throws IOException, ServletException {
            if (startsWith(value, "$")) {
                return FormValidation.ok();
            }
            return JaggerTestExecutionValidation.checkUrl(value);
        }

        public FormValidation doCheckTestProjectUrl(@QueryParameter String value) throws IOException, ServletException {
            if (startsWith(value, "$")) {
                return FormValidation.ok();
            }
            if (StringUtils.isEmpty(value)) {
                return FormValidation.ok();
            }
            return JaggerTestExecutionValidation.checkUrl(value);
        }

        public FormValidation doCheckEnvId(@QueryParameter String value) throws IOException, ServletException {
            if (startsWith(value, "$")) {
                return FormValidation.ok();
            }
            if (StringUtils.isEmpty(value)) {
                return FormValidation.error("Environment ID is mandatory!");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckExecutionStartTimeoutInSeconds(@QueryParameter String value) throws IOException, ServletException {
            if (startsWith(value, "$")) {
                return FormValidation.ok();
            }
            return JaggerTestExecutionValidation.checkExecutionStartTimeoutInSeconds(value);
        }
    }
}