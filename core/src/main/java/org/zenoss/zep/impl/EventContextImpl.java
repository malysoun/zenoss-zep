/*
 * Copyright (C) 2010-2011, Zenoss Inc.  All Rights Reserved.
 */
package org.zenoss.zep.impl;

import org.zenoss.protobufs.zep.Zep.ZepRawEvent;
import org.zenoss.zep.ClearFingerprintGenerator;
import org.zenoss.zep.EventContext;

import java.util.HashSet;
import java.util.Set;

public class EventContextImpl implements EventContext {

    private Set<String> clearClasses = new HashSet<String>();
    private ClearFingerprintGenerator clearFingerprintGenerator = null;

    public EventContextImpl() {
    }

    public EventContextImpl(ZepRawEvent rawEvent) {
        if (rawEvent == null) {
            throw new NullPointerException();
        }
        this.clearClasses.addAll(rawEvent.getClearEventClassList());
    }

    @Override
    public Set<String> getClearClasses() {
        return this.clearClasses;
    }

    @Override
    public void setClearClasses(Set<String> clearClasses) {
        if (clearClasses == null) {
            throw new NullPointerException();
        }
        this.clearClasses = clearClasses;
    }

    @Override
    public ClearFingerprintGenerator getClearFingerprintGenerator() {
        return this.clearFingerprintGenerator;
    }

    @Override
    public void setClearFingerprintGenerator(ClearFingerprintGenerator clearFingerprintGenerator) {
        this.clearFingerprintGenerator = clearFingerprintGenerator;
    }

    @Override
    public String toString() {
        return String.format("EventContextImpl [clearClasses=%s, clearFingerprintGenerator=%s]", clearClasses,
                this.clearFingerprintGenerator);
    }

}
