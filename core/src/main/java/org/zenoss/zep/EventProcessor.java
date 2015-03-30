/*****************************************************************************
 * 
 * Copyright (C) Zenoss, Inc. 2010, all rights reserved.
 * 
 * This content is made available according to terms specified in
 * License.zenoss under the directory where your Zenoss product is installed.
 * 
 ****************************************************************************/


package org.zenoss.zep;

import com.google.protobuf.Message;
import org.zenoss.protobufs.zep.Zep.ZepRawEvent;

import java.util.Collection;

/**
 * Service for processing incoming events from the raw event queue, processing
 * them (identifying the event class, transforming them, persisting them), and
 * perform any post-processing on the event including alerting or publishing
 * events.
 */
public interface EventProcessor {
    /**
     * Processes the event.
     * 
     * @param event
     *            The raw event.
     * @throws ZepException
     *             If an error occurs processing the event.
     */
    public void processEvent(ZepRawEvent event) throws ZepException;

    /**
     * Processes a collection of events. If one of them can not be processed, then none of them are processed.
     *
     * @param messages
     *          The list of messages to process.
     * @throws ZepException
     *          If any error occurs processing any of the events.
     */
    public void processEventMessages(Collection<org.zenoss.amqp.Message<Message>> messages) throws ZepException;
}
