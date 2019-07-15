package com.kingtalk.logging;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * This class holds the data for a single Count.ly custom event instance.
 * It also knows how to read & write itself to the Count.ly custom event JSON syntax.
 * See the following link for more info:
 * https://count.ly/resources/reference/custom-events
 */
class Event {
    private static final String SEGMENTATION_KEY = "segmentation";
    private static final String KEY_KEY = "key";
    private static final String COUNT_KEY = "count";
    private static final String SUM_KEY = "sum";
    private static final String DUR_KEY = "dur";
    private static final String TIMESTAMP_KEY = "timestamp";
    private static final String DAY_OF_WEEK = "dow";
    private static final String HOUR = "hour";

    public String key;
    Map<String, String> segmentation;
    Map<String, Integer> segmentationInt;
    Map<String, Double> segmentationDouble;
    public int count;
    double sum;
    double dur;
    long timestamp;
    int hour;
    int dow;

    Event () {}

    Event(String key) {
        this.key = key;
        this.timestamp = Logging.currentTimestampMs();
        this.hour = Logging.currentHour();
        this.dow = Logging.currentDayOfWeek();
    }

    /**
     * Creates and returns a JSONObject containing the event data from this object.
     * @return a JSONObject containing the event data from this object
     */
    JSONObject toJSON() {
        final JSONObject json = new JSONObject();

        try {
            json.put(KEY_KEY, key);
            json.put(COUNT_KEY, count);
            json.put(TIMESTAMP_KEY, timestamp);
            json.put(HOUR, hour);
            json.put(DAY_OF_WEEK, dow);

            JSONObject jobj = new JSONObject();
            if (segmentation != null) {
                for (Map.Entry<String, String> pair : segmentation.entrySet()) {
                    jobj.put(pair.getKey(), pair.getValue());
                }
            }

            if(segmentationInt != null){
                for (Map.Entry<String, Integer> pair : segmentationInt.entrySet()) {
                    jobj.put(pair.getKey(), pair.getValue());
                }
            }

            if(segmentationDouble != null){
                for (Map.Entry<String, Double> pair : segmentationDouble.entrySet()) {
                    jobj.put(pair.getKey(), pair.getValue());
                }
            }

            if(segmentation != null || segmentationInt != null || segmentationDouble != null) {
                json.put(SEGMENTATION_KEY, jobj);
            }

            // we put in the sum last, the only reason that a JSONException would be thrown
            // would be if sum is NaN or infinite, so in that case, at least we will return
            // a JSON object with the rest of the fields populated
            json.put(SUM_KEY, sum);

            if (dur > 0) {
                json.put(DUR_KEY, dur);
            }
        }
        catch (JSONException e) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.w(Logging.TAG, "Got exception converting an Event to JSON", e);
            }
        }

        return json;
    }

    /**
     * Factory method to create an Event from its JSON representation.
     * @param json JSON object to extract event data from
     * @return Event object built from the data in the JSON or null if the "key" value is not
     *         present or the empty string, or if a JSON exception occurs
     * @throws NullPointerException if JSONObject is null
     */
    static Event fromJSON(final JSONObject json) {
        Event event = new Event();

        try {
            if (!json.isNull(KEY_KEY)) {
                event.key = json.getString(KEY_KEY);
            }
            event.count = json.optInt(COUNT_KEY);
            event.sum = json.optDouble(SUM_KEY, 0.0d);
            event.dur = json.optDouble(DUR_KEY, 0.0d);
            event.timestamp = json.optLong(TIMESTAMP_KEY);
            event.hour = json.optInt(HOUR);
            event.dow = json.optInt(DAY_OF_WEEK);

            if (!json.isNull(SEGMENTATION_KEY)) {
                JSONObject segm = json.getJSONObject(SEGMENTATION_KEY);

                final HashMap<String, String> segmentation = new HashMap<>();
                final HashMap<String, Integer> segmentationInt = new HashMap<>();
                final HashMap<String, Double> segmentationDouble = new HashMap<>();

                final Iterator nameItr = segm.keys();
                while (nameItr.hasNext()) {
                    final String key = (String) nameItr.next();
                    if (!segm.isNull(key)) {
                        Object obj = segm.opt(key);

                        if(obj instanceof Double){
                            //in case it's a double
                            segmentationDouble.put(key, segm.getDouble(key));
                        } else if(obj instanceof Integer){
                            //in case it's a integer
                            segmentationInt.put(key, segm.getInt(key));
                        } else {
                            //assume it's String
                            segmentation.put(key, segm.getString(key));
                        }
                    }
                }
                event.segmentation = segmentation;
                event.segmentationDouble = segmentationDouble;
                event.segmentationInt = segmentationInt;
            }
        }
        catch (JSONException e) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.w(Logging.TAG, "Got exception converting JSON to an Event", e);
            }
            event = null;
        }

        return (event != null && event.key != null && event.key.length() > 0) ? event : null;
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof Event)) {
            return false;
        }

        final Event e = (Event) o;

        return (Objects.equals(key, e.key)) &&
               timestamp == e.timestamp &&
               hour == e.hour &&
               dow == e.dow &&
               (Objects.equals(segmentation, e.segmentation));
    }

    @Override
    public int hashCode() {
        return (key != null ? key.hashCode() : 1) ^
               (segmentation != null ? segmentation.hashCode() : 1) ^
               (timestamp != 0 ? (int)timestamp : 1);
    }
}
