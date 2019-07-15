package com.kingtalk.logging;

import org.json.JSONArray;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

class EventQueue {
    private final LoggingStore loggingStore_;

    /**
     * Constructs an EventQueue.
     * @param loggingStore backing store to be used for local event queue persistence
     */
    EventQueue(final LoggingStore loggingStore) {
        loggingStore_ = loggingStore;
    }

    /**
     * Returns the number of events in the local event queue.
     * @return the number of events in the local event queue
     */
    int size() {
        return loggingStore_.events().length;
   }

    /**
     * Removes all current events from the local queue and returns them as a
     * URL-encoded JSON string that can be submitted to a ConnectionQueue.
     * @return URL-encoded JSON string of event data from the local event queue
     */
    String events() {
        String result;

        final List<Event> events = loggingStore_.eventsList();

        final JSONArray eventArray = new JSONArray();
        for (Event e : events) {
            eventArray.put(e.toJSON());
        }

        result = eventArray.toString();

        loggingStore_.removeEvents(events);

        try {
            result = java.net.URLEncoder.encode(result, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // should never happen because Android guarantees UTF-8 support
        }

        return result;
    }

    /**
     * Records a custom Count.ly event to the local event queue.
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation values for the custom event, may be null
     * @param count count associated with the custom event, should be more than zero
     * @param sum sum associated with the custom event, if not used, pass zero.
     *            NaN and infinity values will be quietly ignored.
     * @throws IllegalArgumentException if key is null or empty
     */
    void recordEvent(final String key, final Map<String, String> segmentation, final Map<String, Integer> segmentationInt, final Map<String, Double> segmentationDouble, final int count, final double sum, final double dur) {
        final long timestamp = Logging.currentTimestampMs();
        final int hour = Logging.currentHour();
        final int dow = Logging.currentDayOfWeek();
        loggingStore_.addEvent(key, segmentation, segmentationInt, segmentationDouble, timestamp, hour, dow, count, sum, dur);
    }

    void recordEvent(final Event event) {
        event.hour = Logging.currentHour();
        event.dow = Logging.currentDayOfWeek();
        loggingStore_.addEvent(event);
    }


        // for unit tests
    LoggingStore getLoggingStore() {
        return loggingStore_;
    }
}
