package com.code.pal.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.sift.AbstractDiscriminator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Routes each log event to a path of the form {@code dd-MM-yyyy/HH-HH+1} based on the
 * event's own timestamp — e.g. an event at 12:30 on 04 Dec 2026 → {@code 04-12-2026/12-13}.
 *
 * <p>Used by the {@code SiftingAppender} in {@code logback-spring.xml}: its return value
 * becomes the {@code ${logfile}} substitution in the nested file appender. Computing the
 * "+1" end hour here is why a plain {@code TimeBasedRollingPolicy} (which can only stamp
 * the start hour) is not enough for a start-end file name.
 */
public class HourFolderDiscriminator extends AbstractDiscriminator<ILoggingEvent> {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd-MM-yyyy").withZone(ZoneId.systemDefault());

    private String key = "logfile";

    @Override
    public String getDiscriminatingValue(ILoggingEvent event) {
        ZonedDateTime t = Instant.ofEpochMilli(event.getTimeStamp()).atZone(ZoneId.systemDefault());
        int hour = t.getHour();
        int endHour = (hour + 1) % 24;   // 23 -> 00 for the 23:00-00:00 window
        return String.format("%s/%02d-%02d", DAY.format(t), hour, endHour);
    }

    @Override
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    @Override
    public void start() {
        started = true;
    }
}
