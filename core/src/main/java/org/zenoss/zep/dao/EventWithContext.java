package org.zenoss.zep.dao;

import org.zenoss.protobufs.zep.Zep.Event;
import org.zenoss.zep.plugins.EventPreCreateContext;

/**
 * Bean used to pass an event/context tuple to DAO for batch processing a list of events/contexts
 */
public class EventWithContext {
    private final Event event;
    private final EventPreCreateContext context;

    public EventWithContext(Event event, EventPreCreateContext context) {
        this.event = event;
        this.context = context;
    }

    public Event getEvent() {
        return event;
    }

    public EventPreCreateContext getContext() {
        return context;
    }
}
