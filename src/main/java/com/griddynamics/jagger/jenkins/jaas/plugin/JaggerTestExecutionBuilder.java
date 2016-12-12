package com.griddynamics.jagger.jenkins.jaas.plugin;

import com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity;
import com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity.TestExecutionStatus;
import com.griddynamics.jagger.jenkins.jaas.plugin.util.JaggerTestExecutionValidation;
import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
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
import java.util.concurrent.TimeUnit;

import static com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity.TestExecutionStatus.PENDING;
import static com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity.TestExecutionStatus.RUNNING;
import static com.griddynamics.jagger.jaas.storage.model.TestExecutionEntity.TestExecutionStatus.TIMEOUT;
import static java.lang.String.format;

public class JaggerTestExecutionBuilder extends Builder {
    private static final int STATUS_POLLING_TIMEOUT_IN_SECONDS = 5;
    private final String jaasEndpoint;
    private final String testProjectUrl;
    private final String envId;
    private final String loadScenarioId;
    private final String executionStartTimeoutInSeconds;

    private final RestTemplate restTemplate = new RestTemplate();

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
        startTestExecution(listener);
        return true;
    }

    public void startTestExecution(TaskListener listener) throws InterruptedException, IOException {
        final PrintStream logger = listener.getLogger();

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 1: Creating TestExecution request...");
        TestExecutionEntity testExecutionEntity = createTestExecution(logger);

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 2: Sending request to JaaS...");
        TestExecutionEntity sentExecution = sendTestExecutionToJaas(logger, testExecutionEntity);

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 3: Waiting test to start execution...");
        waitTestExecutionStarted(logger, sentExecution.getId());

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 4: Waiting test to finish execution...");
        waitTestExecutionFinished(logger, sentExecution.getId());

        logger.println("\n\nJagger JaaS Jenkins Plugin Step 5: Publishing Test execution results...");
        logger.println("Test execution report can be found by the link " + jaasEndpoint + "/report?sessionId=valid-session-id");
    }

    private TestExecutionEntity createTestExecution(PrintStream logger) {
        TestExecutionEntity testExecutionEntity = new TestExecutionEntity();
        testExecutionEntity.setEnvId(envId);
        testExecutionEntity.setLoadScenarioId(loadScenarioId);
        testExecutionEntity.setExecutionStartTimeoutInSeconds(Long.parseLong(executionStartTimeoutInSeconds));
        testExecutionEntity.setTestProjectURL(testProjectUrl);

        logger.println(format("JaaS endpoint: %s", jaasEndpoint));
        logger.println("Test execution properties:");
        logger.println(format("    Environment ID: %s", envId));
        if (StringUtils.isNotEmpty(loadScenarioId))
            logger.println(format("    Load scenario ID: %s", loadScenarioId));
        if (StringUtils.isNotEmpty(testProjectUrl))
            logger.println(format("    Test project URL: %s", testProjectUrl));
        logger.println(format("    Execution start timeout in seconds: %s", executionStartTimeoutInSeconds));
        return testExecutionEntity;
    }

    private TestExecutionEntity sendTestExecutionToJaas(PrintStream logger, TestExecutionEntity testExecutionEntity) throws AbortException {
        try {
            RequestEntity<TestExecutionEntity> requestEntity = RequestEntity.post(new URI(jaasEndpoint + "/executions")).body(testExecutionEntity);
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

    private void waitTestExecutionStarted(PrintStream logger, Long executionId) throws AbortException {
        TestExecutionStatus executionStatus = PENDING;
        while (executionStatus == PENDING) {
            executionStatus = pollExecutionStatus(logger, executionId);
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

    private void waitTestExecutionFinished(PrintStream logger, Long executionId) throws AbortException {
        TestExecutionStatus executionStatus;
        do {
            executionStatus = pollExecutionStatus(logger, executionId);
            try {
                TimeUnit.SECONDS.sleep(STATUS_POLLING_TIMEOUT_IN_SECONDS);
            } catch (InterruptedException e) {
                logger.println();
                throw new AbortException(format("Error occurred while waiting Test execution with id=%s to finish: %s", executionId, e.getMessage()));
            }
        } while (executionStatus == RUNNING);

        logger.println(format("Test execution with id=%s successfully finished!", executionId));
    }

    private TestExecutionStatus pollExecutionStatus(PrintStream logger, Long executionId) throws AbortException {
        TestExecutionStatus executionStatus;
        try {
            logger.print(format("Polling status of test execution with id=%s ... ", executionId));
            RequestEntity<?> requestEntity = RequestEntity.get(new URI(jaasEndpoint + "/executions/" + executionId)).build();
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
            return JaggerTestExecutionValidation.checkUrl(value);
        }

        public FormValidation doCheckTestProjectUrl(@QueryParameter String value) throws IOException, ServletException {
            if (StringUtils.isEmpty(value)) {
                return FormValidation.ok();
            }
            return JaggerTestExecutionValidation.checkUrl(value);
        }

        public FormValidation doCheckEnvId(@QueryParameter String value) throws IOException, ServletException {
            if (StringUtils.isEmpty(value)) {
                return FormValidation.error("Environment ID is mandatory!");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckExecutionStartTimeoutInSeconds(@QueryParameter String value) throws IOException, ServletException {
            return JaggerTestExecutionValidation.checkExecutionStartTimeoutInSeconds(value);
        }
    }
}
