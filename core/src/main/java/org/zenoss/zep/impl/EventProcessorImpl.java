/*****************************************************************************
 * 
 * Copyright (C) Zenoss, Inc. 2010, all rights reserved.
 * 
 * This content is made available according to terms specified in
 * License.zenoss under the directory where your Zenoss product is installed.
 * 
 ****************************************************************************/


package org.zenoss.zep.impl;

import com.codahale.metrics.annotation.Timed;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.zenoss.protobufs.zep.Zep.Event;
import org.zenoss.protobufs.zep.Zep.EventStatus;
import org.zenoss.protobufs.zep.Zep.EventSummary;
import org.zenoss.protobufs.zep.Zep.ZepRawEvent;
import org.zenoss.zep.Counters;
import org.zenoss.zep.EventProcessor;
import org.zenoss.zep.PluginService;
import org.zenoss.zep.ZepException;
import org.zenoss.zep.dao.EventSummaryDao;
import org.zenoss.zep.dao.EventWithContext;
import org.zenoss.zep.plugins.EventPostCreateContext;
import org.zenoss.zep.plugins.EventPostCreatePlugin;
import org.zenoss.zep.plugins.EventPreCreateContext;
import org.zenoss.zep.plugins.EventPreCreatePlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link EventProcessor} which uses
 * {@link PluginService} to load the appropriate plug-ins and process events.
 */
public class EventProcessorImpl implements EventProcessor {

    private static final Logger logger = LoggerFactory.getLogger(EventProcessorImpl.class);

    private static final String EVENT_CLASS_UNKNOWN = "/Unknown";

    private PluginService pluginService;

    private EventSummaryDao eventSummaryDao;

    private Counters counters;

    public void setEventSummaryDao(EventSummaryDao eventSummaryDao) {
        this.eventSummaryDao = eventSummaryDao;
    }

    /**
     * Sets the plug-in service used to look up configured plug-ins.
     * 
     * @param pluginService
     *            The plug-in service to use to look up configured plug-ins.
     */
    public void setPluginService(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    public void setCounters(Counters counters) {
        this.counters = counters;
    }

    private static Event eventFromRawEvent(ZepRawEvent zepRawEvent) {
        Event event = zepRawEvent.getEvent();
        // Default to event class unknown.
        if (event.getEventClass().isEmpty()) {
            event = Event.newBuilder(event).setEventClass(EVENT_CLASS_UNKNOWN).build();
        }
        return event;
    }

    @Override
    @Timed
    public void processEvent(ZepRawEvent zepRawEvent) throws ZepException {
        EventWithContext eventWithContext = rawEventToEventWithContext(zepRawEvent);
        Event event = eventWithContext.getEvent();
        EventPreCreateContext ctx = eventWithContext.getContext();

        String uuid;
        try {
            uuid = this.eventSummaryDao.create(event, ctx);
        } catch (DuplicateKeyException e) {
            // Catch DuplicateKeyException and retry creating the event. Otherwise, the failure
            // will propagate to the AMQP consumer, the message will be rejected (and re-queued),
            // leading to unnecessary load on the AMQP server re-queueing/re-delivering the event.
            if (logger.isDebugEnabled()) {
                logger.info("DuplicateKeyException - retrying event: {}", event);
            } else {
                logger.info("DuplicateKeyException - retrying event: {}", event.getUuid());
            }
            uuid = this.eventSummaryDao.create(event, ctx);
        }

        runPostPlugins(event, uuid);
    }

    @Override
    @Timed
    public void processEventMessages(Collection<org.zenoss.amqp.Message<Message>> messages) throws ZepException {
        logger.debug("processEventMessages: count={}", messages.size());
        List<EventWithContext> eventList = messagesToEvents(messages);

        List<Map.Entry<String, Event>> results = this.eventSummaryDao.batchCreate(eventList);

        for (Map.Entry<String, Event>result : results) {
            String uuid = result.getKey();
            Event event = result.getValue();
            runPostPlugins(event, uuid);
        }

    }

    private List<EventWithContext> messagesToEvents(Collection<org.zenoss.amqp.Message<Message>> messages) throws ZepException {
        Exception lastException = null;

        List<EventWithContext> eventList = new ArrayList<EventWithContext>();
        for (org.zenoss.amqp.Message<Message> message : messages) {
            try {
                Message messageBody = message.getBody();
                if (!(messageBody instanceof ZepRawEvent)) {
                    logger.warn("Unexpected message type: {}", message);
                    continue;
                }

                EventWithContext eventWithContext = rawEventToEventWithContext((ZepRawEvent) messageBody);
                if (eventWithContext != null) {
                    eventList.add(eventWithContext);
                }
            } catch (Exception ex) {
                logger.error("failed to convert message {} to event", message, ex);
                lastException = ex;
            }
        }

        if (lastException != null) {
            throw new ZepException(lastException);
        }

        return eventList;
    }

    private EventWithContext rawEventToEventWithContext(ZepRawEvent zepRawEvent) throws ZepException {
        logger.debug("processEvent: event={}", zepRawEvent);
        // FIXME: This counter logic is incorrect if an exception occurs, and the mistake will be magnified
        //          when processing a batch of events in a single transaction.
        counters.addToProcessedEventCount(1);

        if (zepRawEvent.getEvent().getStatus() == EventStatus.STATUS_DROPPED) {
            logger.debug("Event dropped: {}", zepRawEvent);
            counters.addToDroppedEventCount(1);
            return null;
        } else if (zepRawEvent.getEvent().getUuid().isEmpty()) {
            logger.error("Could not process event, has no uuid: {}",
                    zepRawEvent);
            counters.addToDroppedEventCount(1);
            return null;
        } else if (!zepRawEvent.getEvent().hasCreatedTime()) {
            logger.error("Could not process event, has no created_time: {}",
                    zepRawEvent);
            counters.addToDroppedEventCount(1);
            return null;
        }

        Event event = eventFromRawEvent(zepRawEvent);
        EventPreCreateContext ctx = new EventPreCreateContextImpl(zepRawEvent);

        for (EventPreCreatePlugin plugin : pluginService.getPluginsByType(EventPreCreatePlugin.class)) {
            Event modified = plugin.processEvent(event, ctx);
            if (modified != null && modified.getStatus() == EventStatus.STATUS_DROPPED) {
                logger.debug("Event dropped by {}", plugin.getId());
                counters.addToDroppedEventCount(1);
                return null;
            }

            if (modified != null && !modified.equals(event)) {
                logger.debug("Event modified by {} as {}", plugin.getId(), modified);
                event = modified;
            }
        }

        return new EventWithContext(event, ctx);
    }


    private void runPostPlugins(Event event, String uuid) throws ZepException {
        EventSummary summary = null;
        EventPostCreateContext context = new EventPostCreateContext() {
        };
        for (EventPostCreatePlugin plugin : pluginService.getPluginsByType(EventPostCreatePlugin.class)) {
            if (summary == null && uuid != null) {
                summary = this.eventSummaryDao.findByUuid(uuid);
            }
            try {
                plugin.processEvent(event, summary, context);
            } catch (Exception e) {
                logger.warn("Failed to run post-create plugin: {} on event: {}, summary: {}",
                        new Object[] { plugin.getId(), event, summary }, e);
            }
        }
    }

}
