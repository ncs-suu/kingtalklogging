package com.kingtalk.logging;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Objects;


class AdvertisingIdAdapter {
    private static final String TAG = "AdvertisingIdAdapter";
    private final static String ADVERTISING_ID_CLIENT_CLASS_NAME = "com.google.android.gms.ads.identifier.AdvertisingIdClient";
    static boolean isAdvertisingIdAvailable() {
        boolean advertisingIdAvailable = false;
        try {
            Class.forName(ADVERTISING_ID_CLIENT_CLASS_NAME);
            advertisingIdAvailable = true;
        }
        catch (ClassNotFoundException ignored) {}
        return advertisingIdAvailable;
    }

    static void setAdvertisingId(final Context context, final LoggingStore store, final DeviceId deviceId) {
        new Thread(() -> {
            try {
                deviceId.setId(DeviceId.Type.ADVERTISING_ID, getAdvertisingId(context));
            } catch (Throwable t) {
                if (t.getCause() != null && t.getCause().getClass().toString().contains("GooglePlayServicesAvailabilityException")) {
                    // recoverable, let device ID be null, which will result in storing all requests to Logging server
                    // and rerunning them whenever Advertising ID becomes available
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.i(TAG, "Advertising ID cannot be determined yet");
                    }
                } else if (t.getCause() != null && t.getCause().getClass().toString().contains("GooglePlayServicesNotAvailableException")) {
                    // non-recoverable, fallback to OpenUDID
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.w(TAG, "Advertising ID cannot be determined because Play Services are not available");
                    }
                    deviceId.switchToIdType(DeviceId.Type.OPEN_UDID, context, store);
                } else {
                    // unexpected
                    Log.e(TAG, "Couldn't get advertising ID", t);
                }
            }
        }).start();
    }

    private static String getAdvertisingId(final Context context) throws Throwable{
        final Class<?> cls = Class.forName(ADVERTISING_ID_CLIENT_CLASS_NAME);
        final Method getAdvertisingIdInfo = cls.getMethod("getAdvertisingIdInfo", Context.class);
        Object info = getAdvertisingIdInfo.invoke(null, context);
        if (info != null) {
            final Method getId = info.getClass().getMethod("getId");
            Object id = getId.invoke(info);
            return (String)id;
        }
        return null;
    }

    private static boolean isLimitAdTrackingEnabled(final Context context) {
        try {
            final Class<?> cls = Class.forName(ADVERTISING_ID_CLIENT_CLASS_NAME);
            final Method getAdvertisingIdInfo = cls.getMethod("getAdvertisingIdInfo", Context.class);
            Object info = getAdvertisingIdInfo.invoke(null, context);
            if (info != null) {
                final Method getId = info.getClass().getMethod("isLimitAdTrackingEnabled");
                Object id = getId.invoke(info);
                return (boolean) id;
            }
        } catch (Exception t) {
            if (t.getCause() != null && t.getCause().getClass().toString().contains("java.lang.ClassNotFoundException") &&
                    Objects.requireNonNull(t.getCause().getMessage()).contains("com.google.android.gms.ads.identifier.AdvertisingIdClient")) {
                if (Logging.sharedInstance().isLoggingEnabled()) {
                    Log.w(TAG, "Play Services are not available, while checking if limited ad tracking enabled");
                }
            }
        }
        return false;
    }

    /**
     * Cache advertising ID for attribution
     */
    static void cacheAdvertisingID(final Context context, final LoggingStore store) {
        new Thread(() -> {
            try {
                if(!isLimitAdTrackingEnabled(context)){
                    String adId = getAdvertisingId(context);
                    store.setCachedAdvertisingId(adId);
                } else {
                    store.setCachedAdvertisingId("");
                }
            } catch (Throwable t) {
                if (t.getCause() != null && t.getCause().getClass().toString().contains("GooglePlayServicesAvailabilityException")) {
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.i(TAG, "Advertising ID cannot be determined yet, while caching");
                    }
                } else if (t.getCause() != null && t.getCause().getClass().toString().contains("GooglePlayServicesNotAvailableException")) {
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.w(TAG, "Advertising ID cannot be determined because Play Services are not available, while caching");
                    }
                } else if (t.getCause() != null && t.getCause().getClass().toString().contains("java.lang.ClassNotFoundException") &&
                        Objects.requireNonNull(t.getCause().getMessage()).contains("com.google.android.gms.ads.identifier.AdvertisingIdClient")) {
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.w(TAG, "Play Services are not available, while caching advertising id");
                    }
                } else {
                    // unexpected
                    Log.e(TAG, "Couldn't get advertising ID, while caching", t);
                }
            }
        }).start();
    }
}
