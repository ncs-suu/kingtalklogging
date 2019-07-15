package com.kingtalk.logging;

import android.app.Activity;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;

public interface ILogging {
    String TAG = "KING_TALK_LOGGING";
    String DOMAIN = "https://videoplayback.sohatv.vn/config?package=com.vcc.webplayer&apptype=web&appkey=xvqrcadhebfi0v5vns3f0wwwkv607d08";
    /**
     * Initialize logging with default
     * @param activity your activity
     * @return ILogging
     */
    static ILogging init(final Activity activity){
        return init(activity, 10, 5);
    }

    /**
     * Initialize logging with size of queue and delay time
     * @param activity       your activity
     * @param eventQueueSize size of queue to send event
     * @param timeDelay      delay time to send event
     * @return ILogging
     */
    static ILogging init(final Activity activity, final int eventQueueSize, final long timeDelay) {
        HttpHandler handler = new HttpHandler(activity,eventQueueSize,timeDelay);
        try {
            return handler.execute(DOMAIN).get();
        } catch (ExecutionException e) {
            e.printStackTrace();
            return null;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Send a custom event with key, segmentation and count
     * @param key the name of the custom event, required, must not be the empty string
     */
    void logging(final String key);

    /**
     * Send a custom event with key, segmentation and count
     * @param key   the name of the custom event, required, must not be the empty string
     * @param count count to associate with the event, should be more than zero
     */
    void logging(final String key, final int count);

    /**
     * Send a custom event with key, segmentation and count
     * @param key   the name of the custom event, required, must not be the empty string
     * @param count count to associate with the event, should be more than zero
     * @param sum   sum to associate with the event, should be more than zero
     */
    void logging(final String key, final int count, final double sum);

    /**
     * Records a custom event with the specified segmentation values and count, and a sum of zero.
     * @param key     name of the custom event, required, must not be the empty string
     * @param segment segmentation dictionary to associate with the event, can be null
     */
    void logging(final String key, final HashMap<String, String> segment);

    /**
     * Records a custom event with the specified segmentation values and count, and a sum of zero.
     * @param key     name of the custom event, required, must not be the empty string
     * @param segment segmentation dictionary to associate with the event, can be null
     * @param count   count to associate with the event, should be more than zero
     */
    void logging(final String key, final HashMap<String, String> segment, final int count);

    /**
     * Records a custom event with the specified segmentation values and count, and a sum of zero.
     * @param key     name of the custom event, required, must not be the empty string
     * @param segment segmentation dictionary to associate with the event, can be null
     * @param count   count to associate with the event, should be more than zero
     * @param sum     sum to associate with the event, should be more than zero
     */
    void logging(final String key, final HashMap<String, String> segment, final int count, final double sum);

    /**
     * Records a custom event with the specified segmentation values and count, and a sum of zero.
     * @param key      name of the custom event, required, must not be the empty string
     * @param segment  segmentation dictionary to associate with the event, can be null
     * @param count    count to associate with the event, should be more than zero
     * @param sum      sum to associate with the event, should be more than zero
     * @param duration duration to associate with the event, should be more than zero
     */
    void logging(final String key, final HashMap<String, String> segment, final int count, final double sum, final double duration);
}
