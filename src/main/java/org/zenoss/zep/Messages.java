/*
 * This program is part of Zenoss Core, an open source monitoring platform.
 * Copyright (C) 2011, Zenoss Inc.
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published by
 * the Free Software Foundation.
 *
 * For complete information please visit: http://www.zenoss.com/oss/
 */
package org.zenoss.zep;

import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * Helper class for dealing with translated messages.
 */
public class Messages {

    private final MessageSource source;

    /**
     * Creates a Messages instance wrapping the Spring MessageSource.
     *
     * @param source MessageSource.
     */
    public Messages(MessageSource source) {
        this.source = source;
    }

    /**
     * Returns the message with the specified arguments substituted.
     *
     * @param code Message code.
     * @param args Arguments used to build the message.
     * @return The substituted message in the default locale.
     */
    public String getMessage(String code, Object... args) {
        return source.getMessage(code, args, Locale.getDefault());
    }

    /**
     * Returns the message with the arguments substituted in the given locale.
     *
     * @param locale The message locale.
     * @param code The message code.
     * @param args Arguments to the message.
     * @return The substituted message in the default locale.
     */
    public String getMessageForLocale(Locale locale, String code, Object... args) {
        return source.getMessage(code, args, locale);
    }
}