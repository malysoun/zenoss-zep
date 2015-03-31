/*****************************************************************************
 *
 * Copyright (C) Zenoss, Inc. 2010, all rights reserved.
 *
 * This content is made available according to terms specified in
 * License.zenoss under the directory where your Zenoss product is installed.
 *
 ****************************************************************************/


package org.zenoss.zep.impl;

import com.google.protobuf.Message;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.dao.TransientDataAccessException;
import org.zenoss.amqp.AmqpException;
import org.zenoss.amqp.Channel;
import org.zenoss.amqp.Consumer;
import org.zenoss.zep.EventProcessor;
import org.zenoss.zep.ZepUtils;
import org.zenoss.zep.dao.impl.DaoUtils;
import org.zenoss.zep.events.EventIndexQueueSizeEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

public class RawEventQueueListener extends AbstractQueueListener
        implements ApplicationListener<EventIndexQueueSizeEvent>, ApplicationEventPublisherAware {

    private static final Logger logger = LoggerFactory.getLogger(RawEventQueueListener.class);

    private static final int DEFAULT_PREFETCH_SIZE = 0;
    private static final int DEFAULT_PREFETCH_COUNT = 100;
    private static final int DEFAULT_BATCH_SIZE = 20;

    protected final String ackMessageTimerName     = this.getClass().getName() + ".ackMessage";
    protected final String handleMessageTimerName  = this.getClass().getName() + ".handleMessage";
    protected final String receiveMessageTimerName = this.getClass().getName() + ".receiveMessage";
    protected final String rejectMessageTimerName  = this.getClass().getName() + ".rejectMessage";

    private int prefetchCount = DEFAULT_PREFETCH_COUNT;

    private int batchSize = DEFAULT_BATCH_SIZE;

    private EventProcessor eventProcessor;

    public void setPrefetchCount(int prefetchCount) {
        this.prefetchCount = prefetchCount;
    }

    private List<org.zenoss.amqp.Message<Message>> batch = new ArrayList<org.zenoss.amqp.Message<Message>>(batchSize);

    /**
     * Set the size of batches to read for this listener
     *
     * @param batchSize batch size
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    private boolean throttleConsumer = true;
    private volatile boolean indexQueueLag = false;
    private int indexQueueThreshold = 100000;
    private int consumerSleepTime = 100;

    @Override
    public void onApplicationEvent(EventIndexQueueSizeEvent event) {
        if (this.throttleConsumer && event.getTableName().startsWith("event_summary")) {
            int localThreshold = this.indexQueueThreshold;
            if (localThreshold == 0) {
                // autoset the threshold
                // event.getLimit() contains the current batch size
                localThreshold = Math.max(event.getLimit(), 100) * 2;
            }

            if (event.getSize() > localThreshold) {
                // enable the throttle
                if (this.indexQueueLag != true) {
                    logger.warn("Enabling zenevents consumer throttling.");
                    this.indexQueueLag = true;
                }
            } else {
                if (this.indexQueueLag != false) {
                    logger.info("Disabling zenevents consumer throttling.");
                    this.indexQueueLag = false;
                }
            }
        }
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    protected void configureChannel(Channel channel) throws AmqpException {
        logger.debug("Using prefetch count: {} for queue: {}", this.prefetchCount, getQueueIdentifier());
        channel.setQos(0, Math.min(this.prefetchCount, this.batchSize));
    }

    @Override
    protected String getQueueIdentifier() {
        return "$ZepZenEvents";
    }

    public void setEventProcessor(EventProcessor eventProcessor) {
        this.eventProcessor = eventProcessor;
    }

    private ApplicationEventPublisher applicationEventPublisher;

    public void setThrottleConsumer(boolean throttleConsumer) {
        this.throttleConsumer = throttleConsumer;
    }

    public void setIndexQueueThreshold(int indexQueueThreshold) {
        this.indexQueueThreshold = indexQueueThreshold;
    }

    public void setConsumerSleepTime(int consumerSleepTime) {
        this.consumerSleepTime = consumerSleepTime;
    }

    @Override
    public void handle(com.google.protobuf.Message message) throws Exception {
        throw new UnsupportedOperationException("this method is not supported");
    }

    /**
     * Method which is called when no more messages remain on the queue at the moment.
     */
    @Override
    public void queueEmptied() throws Exception {
        this.processBatch(this.consumer);
    }

    @Override
    protected void receive(final org.zenoss.amqp.Message<Message> message,
                           final Consumer<Message> consumer) throws Exception {

        metricRegistry.timer(receiveMessageTimerName).time(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                batch.add(message);
                if (batch.size() >= batchSize) {
                    RawEventQueueListener.this.processBatch(consumer);
                }
                return null;
            }
        });
    }

    private void processBatch(final Consumer<com.google.protobuf.Message> consumer) throws Exception {
        final List<org.zenoss.amqp.Message<Message>> messages = ImmutableList.copyOf(batch);
        batch.clear();

        this.executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    DaoUtils.deadlockRetry(new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            metricRegistry.timer(handleMessageTimerName).time(new Callable<Object>() {
                                @Override
                                public Object call() throws Exception {
                                    handleMessages(messages);
                                    return null;
                                }
                            });
                            return null;
                        }
                    });
                    metricRegistry.timer(ackMessageTimerName).time(new Callable<Object>() {
                        @Override
                        public Object call() throws Exception {
                            for (org.zenoss.amqp.Message<Message> message : messages) {
                                try {
                                    consumer.ackMessage(message);
                                } catch (AmqpException e) {
                                    logger.warn("Failed acknowledging message: {}", message, e);
                                }
                            }
                            return null;
                        }
                    });
                } catch (Exception e) {
                    if (ZepUtils.isExceptionOfType(e, TransientDataAccessException.class)) {
                        /* Re-queue the message if we get a temporary database failure */
                        logger.debug("Transient database exception", e);
                        requeueAllMessages(consumer, messages);
                    } else {
                        for (org.zenoss.amqp.Message<Message> message : messages) {
                            if (!message.getEnvelope().isRedeliver()) {
                                /* Attempt one redelivery of the message */
                                logger.debug("First failure processing message: {}", message, e);
                                rejectMessage(consumer, message, true);
                            } else {
                                /* TODO: Dead letter queue or other safety net? */
                                logger.warn("Failed processing message: {}", message, e);
                                rejectMessage(consumer, message, false);
                            }
                        }
                    }
                }
            }
        });
    }

    private void handleMessages(Collection<org.zenoss.amqp.Message<Message>> messages) throws Exception {

        //
        // FIXME: Does it make mroe sense to move this into the receive() method?
        if (this.indexQueueLag && this.throttleConsumer) {
            Thread.sleep(this.consumerSleepTime);
        }

        logger.debug("handleBatch: count={} batchSize={}", messages.size(), batchSize);
        this.eventProcessor.processEventMessages(messages);
    }

    private void requeueAllMessages(Consumer<Message> consumer, List<org.zenoss.amqp.Message<Message>> failedMessages) {
        for (org.zenoss.amqp.Message<Message> message : failedMessages) {
            logger.debug("Re-queueing message due to transient failure: {}", message);
            rejectMessage(consumer, message, true);
        }
    }

    protected void rejectMessage(final Consumer<?> consumer, final org.zenoss.amqp.Message<?> message, final boolean requeue) {
        try {
            metricRegistry.timer(rejectMessageTimerName).time(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    try {
                        consumer.rejectMessage(message, requeue);
                    } catch (AmqpException e) {
                        logger.warn("Failed rejecting message", e);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
