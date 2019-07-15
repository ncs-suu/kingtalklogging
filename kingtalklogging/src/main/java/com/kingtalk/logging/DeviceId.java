package com.kingtalk.logging;

import android.content.Context;
import android.util.Log;

/**
 * Created by artem on 07/11/14.
 */

class DeviceId {
    /**
     * Enum used throughout Logging which controls what kind of ID Logging should use.
     */
    public enum Type {
        DEVELOPER_SUPPLIED,
        OPEN_UDID,
        ADVERTISING_ID,
    }

    private static final String TAG = "DeviceId";
    private static final String PREFERENCE_KEY_ID_ID = "com.kingtalk.logging.DeviceId.id";
    private static final String PREFERENCE_KEY_ID_TYPE = "com.kingtalk.logging.DeviceId.type";
    private static final String PREFERENCE_KEY_ID_ROLLBACK_ID = "com.kingtalk.logging.DeviceId.rollback.id";
    private static final String PREFERENCE_KEY_ID_ROLLBACK_TYPE = "com.kingtalk.logging.DeviceId.rollback.type";

    private String id;
    private Type type;
    /**
     * Initialize DeviceId with Type of OPEN_UDID or ADVERTISING_ID
     * @param type type of ID generation strategy
     */
    DeviceId(LoggingStore store, Type type) {
        if (type == null) {
            throw new IllegalStateException("Please specify DeviceId.Type, that is which type of device ID generation you want to use");
        } else if (type == Type.DEVELOPER_SUPPLIED) {
            throw new IllegalStateException("Please use another DeviceId constructor for device IDs supplied by developer");
        }
        this.type = type;

        retrieveId(store);
    }

    /**
     * Initialize DeviceId with Developer-supplied id string
     * @param developerSuppliedId Device ID string supplied by developer
     */
    DeviceId(LoggingStore store, String developerSuppliedId) {
        if (developerSuppliedId == null || "".equals(developerSuppliedId)) {
            throw new IllegalStateException("Please make sure that device ID is not null or empty");
        }
        this.type = Type.DEVELOPER_SUPPLIED;
        this.id = developerSuppliedId;

        retrieveId(store);
    }

    private void retrieveId (LoggingStore store) {
        String storedId = store.getPreference(PREFERENCE_KEY_ID_ID);
        if (storedId != null) {
            this.id = storedId;
            this.type = retrieveType(store, PREFERENCE_KEY_ID_TYPE);
        }
    }

    /**
     * Initialize device ID generation, that is start up required services and send requests.
     * Device ID is expected to be available after some time.
     * In some cases, Logging can override ID generation strategy to other one, for example when
     * Google Play Services are not available and user chose Advertising ID strategy, it will fall
     * back to OpenUDID
     * @param context Context to use
     * @param store LoggingStore to store configuration in
     * @param raiseExceptions whether to raise exceptions in case of illegal state or not
     */
    void init(Context context, LoggingStore store, boolean raiseExceptions) {
        Type overriddenType = retrieveOverriddenType(store);

        // Some time ago some ID generation strategy was not available and SDK fell back to
        // some other strategy. We still have to use that strategy.
        if (overriddenType != null && overriddenType != type) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.i(TAG, "Overridden device ID generation strategy detected: " + overriddenType + ", using it instead of " + this.type);
            }
            type = overriddenType;
        }

        switch (type) {
            case DEVELOPER_SUPPLIED:
                // no initialization for developer id
                break;
            case OPEN_UDID:
                if (OpenUDIDAdapter.isOpenUDIDAvailable()) {
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.i(TAG, "Using OpenUDID");
                    }
                    if (!OpenUDIDAdapter.isInitialized()) {
                        OpenUDIDAdapter.sync(context);
                    }
                } else {
                    if (raiseExceptions) throw new IllegalStateException("OpenUDID is not available, please make sure that you have it in your classpath");
                }
                break;
            case ADVERTISING_ID:
                if (AdvertisingIdAdapter.isAdvertisingIdAvailable()) {
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.i(TAG, "Using Advertising ID");
                    }
                    AdvertisingIdAdapter.setAdvertisingId(context, store, this);
                } else if (OpenUDIDAdapter.isOpenUDIDAvailable()) {
                    // Fall back to OpenUDID on devices without google play services set up
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.i(TAG, "Advertising ID is not available, falling back to OpenUDID");
                    }
                    if (!OpenUDIDAdapter.isInitialized()) {
                        OpenUDIDAdapter.sync(context);
                    }
                } else {
                    // just do nothing, without Advertising ID and OpenUDID this user is lost for Logging
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.w(TAG, "Advertising ID is not available, neither OpenUDID is");
                    }
                    if (raiseExceptions) throw new IllegalStateException("OpenUDID is not available, please make sure that you have it in your classpath");
                }
                break;
        }
    }

    private void storeOverriddenType(LoggingStore store, Type type) {
        // Using strings is safer when it comes to extending Enum values list
        store.setPreference(PREFERENCE_KEY_ID_TYPE, type == null ? null : type.toString());
    }

    private Type retrieveOverriddenType(LoggingStore store) {
        return retrieveType(store, PREFERENCE_KEY_ID_TYPE);
    }

    private Type retrieveType(LoggingStore store, String preferenceName) {
        // Using strings is safer when it comes to extending Enum values list
        String typeString = store.getPreference(preferenceName);
        if (typeString == null) {
            return null;
        } else if (typeString.equals(Type.DEVELOPER_SUPPLIED.toString())) {
            return Type.DEVELOPER_SUPPLIED;
        } else if (typeString.equals(Type.OPEN_UDID.toString())) {
            return Type.OPEN_UDID;
        } else if (typeString.equals(Type.ADVERTISING_ID.toString())) {
            return Type.ADVERTISING_ID;
        } else {
            return null;
        }
    }

    String getId() {
        if (id == null && type == Type.OPEN_UDID) {
            id = OpenUDIDAdapter.getOpenUDID();
        }
        return id;
    }

    void setId(Type type, String id) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.w(TAG, "Device ID is " + id + " (type " + type + ")");
        }
        this.type = type;
        this.id = id;
    }

    void switchToIdType(Type type, Context context, LoggingStore store) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.w(TAG, "Switching to device ID generation strategy " + type + " from " + this.type);
        }
        this.type = type;
        storeOverriddenType(store, type);
        init(context, store, false);
    }

    void changeToDeveloperProvidedId(LoggingStore store, String newId) {
        if (id != null && type != null && type != Type.DEVELOPER_SUPPLIED) {
            store.setPreference(PREFERENCE_KEY_ID_ROLLBACK_ID, id);
            store.setPreference(PREFERENCE_KEY_ID_ROLLBACK_TYPE, type.toString());
        }

        String oldId = id == null || !id.equals(newId) ? id : null;

        id = newId;
        type = Type.DEVELOPER_SUPPLIED;

        store.setPreference(PREFERENCE_KEY_ID_ID, id);
        store.setPreference(PREFERENCE_KEY_ID_TYPE, type.toString());

    }

    void changeToId(Context context, LoggingStore store, Type type, String deviceId) {
        this.id = deviceId;
        this.type = type;

        store.setPreference(PREFERENCE_KEY_ID_ID, deviceId);
        store.setPreference(PREFERENCE_KEY_ID_TYPE, type.toString());

        init(context, store, false);
    }

    protected String revertFromDeveloperId(LoggingStore store) {
        store.setPreference(PREFERENCE_KEY_ID_ID, null);
        store.setPreference(PREFERENCE_KEY_ID_TYPE, null);

        String i = store.getPreference(PREFERENCE_KEY_ID_ROLLBACK_ID);
        Type t = retrieveType(store, PREFERENCE_KEY_ID_ROLLBACK_TYPE);

        String oldId = null;

        if (i != null && t != null) {
            oldId = id == null || !id.equals(i) ? id : null;
            this.id = i;
            this.type = t;
            store.setPreference(PREFERENCE_KEY_ID_ROLLBACK_ID, null);
            store.setPreference(PREFERENCE_KEY_ID_ROLLBACK_TYPE, null);
        }

        return oldId;
    }

    Type getType() {
        return type;
    }

    /**
     * Helper method for null safe comparison of current device ID and the one supplied to Logging.init
     * @return true if supplied device ID equal to the one registered before
     */
    static boolean deviceIDEqualsNullSafe(final String id, Type type, final DeviceId deviceId) {
        if (type == null || type == Type.DEVELOPER_SUPPLIED) {
            final String deviceIdId = deviceId == null ? null : deviceId.getId();
            return (deviceIdId == null && id == null) || (deviceIdId != null && deviceIdId.equals(id));
        } else {
            return true;
        }
    }
}
