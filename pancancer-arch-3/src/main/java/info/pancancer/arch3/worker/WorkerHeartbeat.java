package info.pancancer.arch3.worker;

import java.io.IOException;

import com.rabbitmq.client.Channel;

public class WorkerHeartbeat implements Runnable {

    private Channel reportingChannel;
    private String queueName;
    private double secondsDelay = 2.0;
    private String messageBody;

    public volatile boolean stop = false;

    @Override
    public void run() {
        //while (!Thread.currentThread().isInterrupted()) {
        System.out.println("starting heartbeat thread");
        while (!Thread.currentThread().interrupted()) {
            byte[] body = this.getMessageBody().getBytes();
            try {
                reportingChannel.basicPublish("", queueName + "_results", null, body);
                Thread.sleep((long) (secondsDelay * 1000));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // TODO Auto-generated catch block
                //e.printStackTrace();
                System.out.println("Heartbeat shutting down.");
            }
        }
    }

    public Channel getReportingChannel() {
        return reportingChannel;
    }

    public void setReportingChannel(Channel reportingChannel) {
        this.reportingChannel = reportingChannel;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public double getSecondsDelay() {
        return secondsDelay;
    }

    public void setSecondsDelay(double secondsDelay) {
        this.secondsDelay = secondsDelay;
    }

    public String getMessageBody() {
        return messageBody;
    }

    public void setMessageBody(String messageBody) {
        this.messageBody = messageBody;
    }


}
