package com.kingtalk.logging;

import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * This class provides several static methods to retrieve information about
 * the current device and operating environment.
 *
 * It is important to call setDeviceID early, before logging any session or custom
 * event data.
 */
class DeviceInfo {
    /**
     * Returns the display name of the current operating system.
     */
    @SuppressWarnings("SameReturnValue")
    static String getOS() {
        return "Android";
    }

    /**
     * Returns the current operating system version as a displayable string.
     */
    @SuppressWarnings("SameReturnValue")
    static String getOSVersion() {
        return android.os.Build.VERSION.RELEASE;
    }

    /**
     * Returns the current device model.
     */
    @SuppressWarnings("SameReturnValue")
    static String getDevice() {
        return android.os.Build.MODEL;
    }

    static String deepLink;

    /**
     * Returns the non-scaled pixel resolution of the current default display being used by the
     * WindowManager in the specified context.
     * @param context context to use to retrieve the current WindowManager
     * @return a string in the format "WxH", or the empty string "" if resolution cannot be determined
     */
    static String getResolution(final Context context) {
        // user reported NPE in this method; that means either getSystemService or getDefaultDisplay
        // were returning null, even though the documentation doesn't say they should do so; so now
        // we catch Throwable and return empty string if that happens
        String resolution = "";
        try {
            final WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            final Display display = wm.getDefaultDisplay();
            final DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            resolution = metrics.widthPixels + "x" + metrics.heightPixels;
        }
        catch (Throwable t) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.i(Logging.TAG, "Device resolution cannot be determined");
            }
        }
        return resolution;
    }

    /**
     * Maps the current display density to a string constant.
     * @param context context to use to retrieve the current display metrics
     * @return a string constant representing the current display density, or the
     *         empty string if the density is unknown
     */
    private static String getDensity(final Context context) {
        String densityStr;
        final int density = context.getResources().getDisplayMetrics().densityDpi;
        switch (density) {
            case DisplayMetrics.DENSITY_LOW:
                densityStr = "LDPI";
                break;
            case DisplayMetrics.DENSITY_MEDIUM:
                densityStr = "MDPI";
                break;
            case DisplayMetrics.DENSITY_TV:
                densityStr = "TVDPI";
                break;
            case DisplayMetrics.DENSITY_HIGH:
                densityStr = "HDPI";
                break;
            case DisplayMetrics.DENSITY_260:
                densityStr = "XHDPI";
                break;
            case DisplayMetrics.DENSITY_280:
                densityStr = "XHDPI";
                break;
            case DisplayMetrics.DENSITY_300:
                densityStr = "XHDPI";
                break;
            case DisplayMetrics.DENSITY_XHIGH:
                densityStr = "XHDPI";
                break;
            case DisplayMetrics.DENSITY_340:
                densityStr = "XXHDPI";
                break;
            case DisplayMetrics.DENSITY_360:
                densityStr = "XXHDPI";
                break;
            case DisplayMetrics.DENSITY_400:
                densityStr = "XXHDPI";
                break;
            case DisplayMetrics.DENSITY_420:
                densityStr = "XXHDPI";
                break;
            case DisplayMetrics.DENSITY_XXHIGH:
                densityStr = "XXHDPI";
                break;
            case DisplayMetrics.DENSITY_560:
                densityStr = "XXXHDPI";
                break;
            case DisplayMetrics.DENSITY_XXXHIGH:
                densityStr = "XXXHDPI";
                break;
            default:
                densityStr = "other";
                break;
        }
        return densityStr;
    }

    /**
     * Returns the display name of the current network operator from the
     * TelephonyManager from the specified context.
     * @param context context to use to retrieve the TelephonyManager from
     * @return the display name of the current network operator, or the empty
     *         string if it cannot be accessed or determined
     */
    private static String getCarrier(final Context context) {
        String carrier = "";
        final TelephonyManager manager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (manager != null) {
            carrier = manager.getNetworkOperatorName();
        }
        if (carrier == null || carrier.length() == 0) {
            carrier = "";
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.i(Logging.TAG, "No carrier found");
            }
        }
        return carrier;
    }

    static int getTimezoneOffset() {
        return TimeZone.getDefault().getOffset(new Date().getTime()) / 60000;
    }

    /**
     * Returns the current locale (ex. "en_US").
     */
    static String getLocale() {
        final Locale locale = Locale.getDefault();
        return locale.getLanguage() + "_" + locale.getCountry();
    }

    /**
     * Returns the application version string stored in the specified
     * context's package info versionName field, or "1.0" if versionName
     * is not present.
     */
    static String getAppVersion(final Context context) {
        String result = Logging.DEFAULT_APP_VERSION;
        try {
            result = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        }
        catch (PackageManager.NameNotFoundException e) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.i(Logging.TAG, "No app version found");
            }
        }
        return result;
    }

    /**
     * Returns the package name of the app that installed this app
     */
    private static String getStore(final Context context) {
        String result = "";
        try {
            result = context.getPackageManager().getInstallerPackageName(context.getPackageName());
        } catch (Exception e) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.i(Logging.TAG, "Can't get Installer package [getStore]");
            }
        }
        if (result == null || result.length() == 0) {
            result = "";
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.i(Logging.TAG, "No store found [getStore]");
            }
        }
        return result;
    }

    /**
     * Returns a URL-encoded JSON string containing the device metrics
     * to be associated with a begin session event.
     * See the following link for more info:
     * https://count.ly/resources/reference/server-api
     */
    static String getMetrics(final Context context) {
        final JSONObject json = new JSONObject();

        fillJSONIfValuesNotEmpty(json,
                "_device", getDevice(),
                "_os", getOS(),
                "_os_version", getOSVersion(),
                "_carrier", getCarrier(context),
                "_resolution", getResolution(context),
                "_density", getDensity(context),
                "_locale", getLocale(),
                "_app_version", getAppVersion(context),
                "_store", getStore(context),
                "_deep_link", deepLink);

        String result = json.toString();

        try {
            result = java.net.URLEncoder.encode(result, "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
            // should never happen because Android guarantees UTF-8 support
        }

        return result;
    }

    /**
     * Utility method to fill JSONObject with supplied objects for supplied keys.
     * Fills json only with non-null and non-empty key/value pairs.
     * @param json JSONObject to fill
     * @param objects varargs of this kind: key1, value1, key2, value2, ...
     */
    private static void fillJSONIfValuesNotEmpty(final JSONObject json, final String... objects) {
        try {
            if (objects.length > 0 && objects.length % 2 == 0) {
                for (int i = 0; i < objects.length; i += 2) {
                    final String key = objects[i];
                    final String value = objects[i + 1];
                    if (value != null && value.length() > 0) {
                        json.put(key, value);
                    }
                }
            }
        } catch (JSONException ignored) {
            // shouldn't ever happen when putting String objects into a JSONObject,
            // it can only happen when putting NaN or INFINITE doubles or floats into it
        }
    }
}
