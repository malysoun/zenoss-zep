package org.zenoss.zep.dao;

import org.zenoss.protobufs.zep.Zep.Event;
import org.zenoss.protobufs.zep.Zep.EventSummary.Builder;
import org.zenoss.zep.plugins.EventPreCreateContext;

import java.util.Collections;
import java.util.List;

/**
 * Bean used to pass an event/context tuple to DAO for batch processing a list of events/contexts
 */
public class EventWithContext {
    public Event event;
    private final EventPreCreateContext context;
    public boolean createClearHash = false;
    public List<String> clearUUIDs = Collections.emptyList();
    public boolean dropped =false;
    public String fingerprint;
    public byte[] fingerprintHash;
    public Builder summary;
    public List<Builder> oldSummaryList;
    public List<Event> dedupingEvents;

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
