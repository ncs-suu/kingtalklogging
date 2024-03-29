package com.kingtalk.logging;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

class MessagingAdapter {
    private static final String TAG = "MessagingAdapter";
    private final static String MESSAGING_CLASS_NAME = "com.kingtalk.logging.LoggingMessaging";

    static boolean isMessagingAvailable() {
        boolean messagingAvailable = false;
        try {
            Class.forName(MESSAGING_CLASS_NAME);
            messagingAvailable = true;
        }
        catch (ClassNotFoundException ignored) {}
        return messagingAvailable;
    }

    static boolean init(Activity activity, Class<? extends Activity> activityClass, String sender, String[] buttonNames, Boolean disableUI, Integer customIconResId, Boolean addMetadataToPushIntents, int customLargeIconRes, int customAccentColor) {
        try {
            final Class<?> cls = Class.forName(MESSAGING_CLASS_NAME);
            final Method method = cls.getMethod("init", Activity.class, Class.class, String.class, String[].class, Boolean.class, Integer.class, Boolean.class, Integer.class, Integer.class);
            method.invoke(null, activity, activityClass, sender, buttonNames, disableUI, customIconResId, addMetadataToPushIntents, customLargeIconRes, customAccentColor);
            return true;
        }
        catch (Throwable logged) {
            Log.e(TAG, "Couldn't init Logging Messaging", logged);
            return false;
        }
    }

    static void storeConfiguration(Context context, String serverURL, String appKey, String deviceID, DeviceId.Type idMode) {
        try {
            final Class<?> cls = Class.forName(MESSAGING_CLASS_NAME);
            final Method method = cls.getMethod("storeConfiguration", Context.class, String.class, String.class, String.class, DeviceId.Type.class);
            method.invoke(null, context, serverURL, appKey, deviceID, idMode);
        }
        catch (Throwable logged) {
            Log.e(TAG, "Couldn't store configuration in Logging Messaging", logged);
        }
    }
}
