/*
 * Copyright (C) 2010, Zenoss Inc.  All Rights Reserved.
 */
package org.zenoss.zep.dao;

import java.util.List;

import org.zenoss.protobufs.zep.Zep.Event;
import org.zenoss.protobufs.zep.Zep.EventDetailSet;
import org.zenoss.protobufs.zep.Zep.EventNote;
import org.zenoss.protobufs.zep.Zep.EventSummary;
import org.zenoss.zep.ZepException;

/**
 * DAO which provides an interface to the event archive.
 */
public interface EventArchiveDao extends Partitionable, Purgable {
    /**
     * Creates an entry in the event archive table for the specified event
     * occurrence.
     * 
     * @param event
     *            Event occurrence.
     * @return The UUID of the created event summary.
     * @throws ZepException
     *             If an error occurs.
     */
    public String create(Event event) throws ZepException;

    /**
     * Deletes the entry in the event archive table with the specified UUID.
     * 
     * @param uuid
     *            UUID of entry in event archive table.
     * @return The number of rows affected by the query.
     * @throws ZepException
     *             If an error occurs.
     */
    public int delete(String uuid) throws ZepException;

    /**
     * Returns the event summary in the archive table with the specified UUID.
     * 
     * @param uuid
     *            UUID of entry in event summary table.
     * @return The event summary entry, or null if not found.
     * @throws ZepException
     *             If an error occurs.
     */
    public EventSummary findByUuid(String uuid) throws ZepException;

    /**
     * Retrieves event summary entries with the specified UUIDs.
     * 
     * @param uuids
     *            UUIDs to find.
     * @return The matching event summary entries.
     * @throws ZepException
     *             If an error occurs.
     */
    public List<EventSummary> findByUuids(List<String> uuids)
            throws ZepException;

    /**
     * Add a note to the event.
     *
     * @param uuid The event UUID.
     * @param note
     *            The note to add.
     * @return The number of rows affected by the query.
     * @throws ZepException
     *             If an error occurs.
     */
    public int addNote(String uuid, EventNote note) throws ZepException;

    /**
     * Updates the event with the specified UUID, to add/merge/update
     * detail values given in details parameter.
     *
     * @param uuid
     *            UUID of event to update.
     * @param details
     *            list of name-value pairs of details to add/merge/update
     *            (setting a detail to '' or null will delete it from the
     *            list of event details)
     * @return The number of affected events.
     * @throws ZepException
     *             If an error occurs.
     */
    public int updateDetails(String uuid, EventDetailSet details)
            throws ZepException;
}
