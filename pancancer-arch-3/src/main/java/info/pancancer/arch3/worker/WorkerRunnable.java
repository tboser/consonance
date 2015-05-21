package info.pancancer.arch3.worker;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import com.rabbitmq.client.QueueingConsumer;
import info.pancancer.arch3.Base;
import info.pancancer.arch3.beans.Job;
import info.pancancer.arch3.beans.Status;
import info.pancancer.arch3.beans.StatusState;
import info.pancancer.arch3.utils.Utilities;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.commons.exec.CommandLine;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class represents a WorkerRunnable, in the Architecture 3 design.
 *
 * A WorkerRunnable can receive job messages from a queue and execute a seqware workflow based on the contents of the job message. Created
 * by boconnor on 15-04-18.
 */
public class WorkerRunnable implements Runnable {

    private static final String NO_MESSAGE_FROM_QUEUE_MESSAGE = " [x] Job request came back null/empty! ";
    protected final Logger log = LoggerFactory.getLogger(getClass());
    private JSONObject settings = null;
    private Channel resultsChannel = null;
    private Channel jobChannel = null;
    private String queueName = null;
    private String jobQueueName;
    private String resultsQueueName;
    private String vmUuid = null;
    private int maxRuns = 1;
    private String userName;
    private boolean testMode;
    private static final int DEFAULT_PRESLEEP = 1;
    private static final int DEFAULT_POSTSLEEP = 1;
    public static final String POSTWORKER_SLEEP = "postworkerSleep";
    public static final String PREWORKER_SLEEP = "preworkerSleep";
    public static final String HEARTBEAT_RATE = "heartbeatRate";

    /**
     * Create a new Worker.
     *
     * @param configFile
     *            - The name of the configuration file to read.
     * @param vmUuid
     *            - The UUID of the VM on which this worker is running.
     * @param maxRuns
     *            - The maximum number of workflows this Worker should execute.
     */
    public WorkerRunnable(String configFile, String vmUuid, int maxRuns) {
        this(configFile, vmUuid, maxRuns, false);
    }

    /**
     * Create a new Worker.
     *
     * @param configFile
     *            - The name of the configuration file to read.
     * @param vmUuid
     *            - The UUID of the VM on which this worker is running.
     * @param maxRuns
     *            - The maximum number of workflows this Worker should execute.
     * @param testMode
     *            the value of testMode
     */
    public WorkerRunnable(String configFile, String vmUuid, int maxRuns, boolean testMode) {
        this.maxRuns = maxRuns;
        settings = Utilities.parseConfig(configFile);
        this.queueName = (String) settings.get("rabbitMQQueueName");
        if (this.queueName == null) {
            throw new NullPointerException(
                    "Queue name was null! Please ensure that you have properly configured \"rabbitMQQueueName\" in your config file.");
        }
        this.jobQueueName = this.queueName + "_jobs";
        this.resultsQueueName = this.queueName + "_results";
        this.userName = (String) settings.get("hostUserName");
        this.vmUuid = vmUuid;
        this.maxRuns = maxRuns;
        this.testMode = testMode;
    }

    @Override
    public void run() {

        int max = maxRuns;

        try {

            // the VM UUID
            log.info(" WORKER VM UUID: '" + vmUuid + "'");

            // read from
            jobChannel = Utilities.setupQueue(settings, this.jobQueueName);
            if (jobChannel == null) {
                throw new NullPointerException(
                        "jobChannel is null for queue: "
                                + this.jobQueueName
                                + ". Something bad must have happened while trying to set up the queue connections. Please ensure that your configuration is correct.");
            }

            // write to
            // TODO: Add some sort of "local debug" mode so that developers working on their local
            // workstation can declare the queue if it doesn't exist. Normally, the results queue is
            // created by the Coordinator.
            resultsChannel = Utilities.setupExchange(settings, this.resultsQueueName);

            QueueingConsumer consumer = new QueueingConsumer(jobChannel);
            jobChannel.basicConsume(this.jobQueueName, false, consumer);

            // TODO: need threads that each read from orders and another that reads results
            while (max > 0 /* || maxRuns <= 0 */) {
                // log.debug("max is: "+max);
                log.info(" WORKER IS PREPARING TO PULL JOB FROM QUEUE " + this.jobQueueName);

                max--;

                // loop once
                // TODO: this will be configurable so it could process multiple jobs before exiting

                // get the job order
                // int messages = jobChannel.queueDeclarePassive(queueName + "_jobs").getMessageCount();
                // System.out.println("THERE ARE CURRENTLY "+messages+" JOBS QUEUED!");

                QueueingConsumer.Delivery delivery = consumer.nextDelivery();
                log.info(vmUuid + "  received " + delivery.getEnvelope().toString());
                // jchannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                if (delivery.getBody() != null) {
                    String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    if (message.trim().length() > 0) {

                        log.info(" [x] Received JOBS REQUEST '" + message + "' @ " + vmUuid);

                        Job job = new Job().fromJSON(message);

                        // TODO: this will obviously get much more complicated when integrated with Docker
                        // launch VM
                        Status status = new Status(vmUuid, job.getUuid(), StatusState.RUNNING, Utilities.JOB_MESSAGE_TYPE,
                                "job is starting", getFirstNonLoopbackAddress().toString());
                        status.setStderr("");
                        status.setStdout("");
                        String statusJSON = status.toJSON();

                        log.info(" WORKER LAUNCHING JOB");
                        // TODO: this is where I would create an INI file and run the local command to run a seqware workflow, in it's own
                        // thread, harvesting STDERR/STDOUT periodically
                        String workflowOutput;
                        if (testMode) {
                            workflowOutput = "everything is awesome";
                        } else {
                            workflowOutput = launchJob(statusJSON, job);
                        }

                        // launchJob(job.getUuid(), job);

                        status = new Status(vmUuid, job.getUuid(), StatusState.SUCCESS, Utilities.JOB_MESSAGE_TYPE, "job is finished",
                                getFirstNonLoopbackAddress().toString());
                        status.setStderr("");
                        status.setStdout(workflowOutput);
                        statusJSON = status.toJSON();

                        log.info(" WORKER FINISHING JOB");

                        finishJob(statusJSON);
                    } else {
                        log.info(NO_MESSAGE_FROM_QUEUE_MESSAGE);
                    }
                    log.info(vmUuid + " acknowledges " + delivery.getEnvelope().toString());
                    jobChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                } else {
                    log.info(NO_MESSAGE_FROM_QUEUE_MESSAGE);
                }
            }
            log.info(" \n\n\nWORKER FOR VM UUID HAS FINISHED!!!: '" + vmUuid + "'\n\n");
            // turns out this is needed when multiple threads are reading from the same
            // queue otherwise you end up with multiple unacknowledged messages being undeliverable to other workers!!!
            if (resultsChannel != null) {
                resultsChannel.close();
                resultsChannel.getConnection().close();
            }
            if (jobChannel != null) {
                jobChannel.close();
                jobChannel.getConnection().close();
            }
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            ex.printStackTrace();
        }
    }

    /**
     * Write the content of the job object to an INI file which will be used by the workflow.
     *
     * @param job
     *            - the job object which must contain a HashMap, which will be used to write an INI file.
     * @return A Path object pointing to the new file will be returned.
     * @throws IOException
     */
    private Path writeINIFile(Job job) throws IOException {
        log.info("INI is: " + job.getIniStr());
        EnumSet<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE);
        FileAttribute<?> attrs = PosixFilePermissions.asFileAttribute(perms);
        Path pathToINI = Files.createTempFile("seqware_", ".ini", attrs);
        log.info("INI file: " + pathToINI.toString());
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(pathToINI.toFile()), StandardCharsets.UTF_8))) {
            bw.write(job.getIniStr());
            bw.flush();
        }
        return pathToINI;
    }

    // TODO: obviously, this will need to launch something using Youxia in the future
    /**
     * This function will execute a workflow, based on the content of the Job object that is passed in.
     *
     * @param message
     *            - The message that will be published on the queue when the worker starts running the job.
     * @param job
     *            - The job contains information about what workflow to execute, and how.
     * @return The complete stdout and stderr from the workflow execution will be returned.
     */
    private String launchJob(String message, Job job) {
        String workflowOutput = "";
        ExecutorService exService = Executors.newFixedThreadPool(2);
        WorkflowRunner workflowRunner = new WorkflowRunner();
        try {

            Path pathToINI = writeINIFile(job);
            resultsChannel.basicPublish(this.resultsQueueName, this.resultsQueueName, MessageProperties.PERSISTENT_TEXT_PLAIN,
                    message.getBytes(StandardCharsets.UTF_8));

            CommandLine cli = new CommandLine("docker");
            cli.addArguments(new String[] { "run", "--rm", "-h", "master", "-t", "-v", "/var/run/docker.sock:/var/run/docker.sock", "-v",
                    job.getWorkflowPath() + ":/workflow", "-v", pathToINI + ":/ini", "-v", "/datastore:/datastore", "-v",
                    "/home/" + this.userName + "/.ssh/gnos.pem:/home/ubuntu/.ssh/gnos.pem", "seqware/seqware_whitestar_pancancer",
                    "seqware", "bundle", "launch", "--dir", "/workflow", "--ini", "/ini", "--no-metadata" });

            WorkerHeartbeat heartbeat = new WorkerHeartbeat();
            heartbeat.setQueueName(this.resultsQueueName);
            heartbeat.setReportingChannel(resultsChannel);
            if (settings.containsKey(HEARTBEAT_RATE)) {
                heartbeat.setSecondsDelay(Double.parseDouble((String) settings.get(HEARTBEAT_RATE)));
            }
            heartbeat.setJobUuid(job.getUuid());
            heartbeat.setVmUuid(this.vmUuid);
            heartbeat.setNetworkID(getFirstNonLoopbackAddress().toString());
            heartbeat.setStatusSource(workflowRunner);
            // heartbeat.setMessageBody(heartbeatStatus.toJSON());

            long presleep = WorkerRunnable.DEFAULT_PRESLEEP;
            if (settings.containsKey(PREWORKER_SLEEP)) {
                presleep = Long.parseLong((String) settings.get(PREWORKER_SLEEP));
            }
            long postsleep = WorkerRunnable.DEFAULT_POSTSLEEP;
            if (settings.containsKey(POSTWORKER_SLEEP)) {
                postsleep = Long.parseLong((String) settings.get(POSTWORKER_SLEEP));
            }

            long presleepMillis = Base.ONE_SECOND_IN_MILLISECONDS * presleep;
            long postsleepMillis = Base.ONE_SECOND_IN_MILLISECONDS * postsleep;

            workflowRunner.setCli(cli);
            workflowRunner.setPreworkDelay(presleepMillis);
            workflowRunner.setPostworkDelay(postsleepMillis);
            // submit both
            Future<?> submit = exService.submit(heartbeat);
            Future<String> workflowResult = exService.submit(workflowRunner);
            // make sure both are complete
            workflowOutput = workflowResult.get();
            submit.get();

            log.info("Docker execution result: " + workflowOutput);
        } catch (SocketException e) {
            // This comes from trying to get the IP address.
            log.error(e.getMessage(), e);
        } catch (IOException e) {
            // This could be caused by a problem writing the file, or publishing a message to the queue.
            log.error(e.getMessage(), e);
        } catch (ExecutionException | InterruptedException e) {
            // This comes from trying to get the workflow execution result.
            log.error("Error executing workflow: " + e.getMessage(), e);
        } finally {
            exService.shutdownNow();
        }
        return workflowOutput;
    }

    /**
     * Get the IP address of this machine, preference is given to returning an IPv4 address, if there is one.
     *
     * @return An InetAddress object.
     * @throws SocketException
     */
    private static InetAddress getFirstNonLoopbackAddress() throws SocketException {
        for (NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress addr : Collections.list(i.getInetAddresses())) {
                if (!addr.isLoopbackAddress()) {
                    // Prefer IP v4
                    if (addr instanceof Inet4Address) {
                        return addr;
                    }
                }

            }
            // If we got here it means we never found an IP v4 address, so we'll have to return the IPv6 address.
            for (InetAddress addr : Collections.list(i.getInetAddresses())) {
                // InetAddress addr = (InetAddress) en2.nextElement();
                if (!addr.isLoopbackAddress()) {
                    return addr;
                }
            }
        }
        return null;
    }

    /**
     * Publish a message stating that the job is finished.
     *
     * @param message
     *            - The actual message to publish.
     */
    private void finishJob(String message) {
        log.info("Publishing worker results to results channel " + this.resultsQueueName + ": " + message);
        try {
            resultsChannel.basicPublish(this.resultsQueueName, this.resultsQueueName, MessageProperties.PERSISTENT_TEXT_PLAIN,
                    message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error(e.toString());
        }
    }
}
