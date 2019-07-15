package com.kingtalk.logging;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.kingtalk.logging.LoggingStarRating.STAR_RATING_EVENT_KEY;

class Logging implements ILogging {

    /**
     * Current version of the Count.ly Android SDK as a displayable string.
     */
    static final String KING_TALK_LOGGING_SDK_VERSION_STRING = "19.02.3";
    /**
     * Used as request meta data on every request
     */
    static final String LOGGING_SDK_NAME = "java-native-android";
    /**
     * Default string used in the begin session metrics if the
     * app version cannot be found.
     */
    static final String DEFAULT_APP_VERSION = "1.0";
    /**
     * Tag used in all logging in the Count.ly SDK.
     */
    public static final String TAG = "KING_TALK_LOGGING";

    /**
     * Broadcast sent when consent set is changed
     */
    static final String CONSENT_BROADCAST = "com.kingtalk.logging.Logging.CONSENT_BROADCAST";

    /**
     * Determines how many custom events can be queued locally before
     * an attempt is made to submit them to a Count.ly server.
     */
    private static int eventQueueSize = 10;
    /**
     * How often onTimer() is called.
     */
    private static long timeDelay = 5;

    static List<String> publicKeyPinCertificates;
    static List<String> certificatePinCertificates;

    private static final Map<String, Event> timedEvents = new HashMap<>();

    /**
     * Enum used in Logging.initMessaging() method which controls what kind of
     * app installation it is. Later (in Logging Dashboard or when calling Logging API method),
     * you'll be able to choose whether you want to send a message to test devices,
     * or to production ones.
     */
    public enum LoggingMessagingMode {
        TEST,
        PRODUCTION,
    }

    // see http://stackoverflow.com/questions/7048198/thread-safe-singletons-in-java
    private static class SingletonHolder {
        @SuppressLint("StaticFieldLeak")
        static final Logging instance = new Logging();
    }

    private ConnectionQueue connectionQueue_;
    @SuppressWarnings("FieldCanBeLocal")
    private final ScheduledExecutorService timerService_;
    private EventQueue eventQueue_;
    private long prevSessionDurationStartTime_;
    private int activityCount_;
    private boolean disableUpdateSessionRequests_;
    private boolean enableLogging_;
    private LoggingMessagingMode messagingMode_;
    private Context context_;

    //user data access
    private static UserData userData;

    //track views
    private String lastView = null;
    private int lastViewStart = 0;
    private boolean firstView = true;
    private boolean autoViewTracker = false;
    private final static String VIEW_EVENT_KEY = "[CLY]_view";

    //overrides
    private boolean isHttpPostForced = false;//when true, all data sent to the server will be sent using HTTP POST

    //app crawlers
    private boolean shouldIgnoreCrawlers = true;//ignore app crawlers by default
    private boolean deviceIsAppCrawler = false;//by default assume that device is not a app crawler
    @SuppressWarnings("ArraysAsListWithZeroOrOneArgument")
    private final List<String> appCrawlerNames = new ArrayList<>(Arrays.asList("Calypso AppCrawler"));//List against which device name is checked to determine if device is app crawler

    //star rating
    @SuppressWarnings("FieldCanBeLocal")
    private LoggingStarRating.RatingCallback starRatingCallback_;// saved callback that is used for automatic star rating

    //push related
    private boolean addMetadataToPushIntents = false;// a flag that indicates if metadata should be added to push notification intents

    //internal flags
    private boolean calledAtLeastOnceOnStart = false;//flag for if the onStart function has been called at least once

    //activity tracking
    private boolean automaticTrackingShouldUseShortName;//flag for using short names

    //attribution
    boolean isAttributionEnabled = true;

    boolean isBeginSessionSent = false;

    //remote config
    //if set to true, it will automatically download remote configs on module startup
    private boolean remoteConfigAutomaticUpdateEnabled;
    private RemoteConfig.RemoteConfigCallback remoteConfigInitCallback;

    //custom request header fields
    private Map<String, String> requestHeaderCustomValues;

    //native crash
    private static final String loggingFolderName = "Logging";
    private static final String loggingNativeCrashFolderName = "CrashDumps";

    //GDPR
    private boolean requiresConsent = false;

    private final Map<String, Boolean> featureConsentValues = new HashMap<>();
    private final Map<String, String[]> groupedFeatures = new HashMap<>();
    private final List<String> collectedConsentChanges = new ArrayList<>();

    private Boolean delayedPushConsent = null;//if this is set, consent for push has to be set before finishing init and sending push changes
    private boolean delayedLocationErasure = false;//if location needs to be cleared at the end of init

    static class LoggingFeatureNames {
        static final String sessions = "sessions";
        static final String events = "events";
        static final String views = "views";
        //public static final String scrolls = "scrolls";
        //public static final String clicks = "clicks";
        //public static final String forms = "forms";
        static final String location = "location";
        static final String crashes = "crashes";
        static final String attribution = "attribution";
        static final String users = "users";
        static final String push = "push";
        static final String starRating = "star-rating";
        //public static final String accessoryDevices = "accessory-devices";
    }

    //a list of valid feature names that are used for checking
    private final String[] validFeatureNames = new String[]{
            LoggingFeatureNames.sessions,
            LoggingFeatureNames.events,
            LoggingFeatureNames.views,
            LoggingFeatureNames.location,
            LoggingFeatureNames.crashes,
            LoggingFeatureNames.attribution,
            LoggingFeatureNames.users,
            LoggingFeatureNames.push,
            LoggingFeatureNames.starRating};

    /**
     * Returns the Logging singleton.
     */
    public static Logging sharedInstance() {
        return SingletonHolder.instance;
    }

    /**
     * Constructs a Logging object.
     * Creates a new ConnectionQueue and initializes the session timer.
     */
    Logging() {
        connectionQueue_ = new ConnectionQueue();
        Logging.userData = new UserData(connectionQueue_);
        timerService_ = Executors.newSingleThreadScheduledExecutor();
        timerService_.scheduleWithFixedDelay(() -> onTimer(), timeDelay, timeDelay, TimeUnit.SECONDS);

        initConsent();
        automaticTrackingShouldUseShortName = false;
        remoteConfigAutomaticUpdateEnabled = false;
        remoteConfigInitCallback = null;
    }


    /**
     * Initializes the Logging SDK. Call from your main Activity's onCreate() method.
     * Must be called before other SDK methods can be used.
     * Device ID is supplied by OpenUDID service if available, otherwise Advertising ID is used.
     * BE CAUTIOUS!!!! If neither OpenUDID, nor Advertising ID is available, Logging will ignore this user.
     * @param context application context
     * @param serverURL URL of the Logging server to submit data to; use "https://try.count.ly" for Logging trial server
     * @param appKey app key for the application being tracked; find in the Logging Dashboard under Management &gt; Applications
     * @return Logging instance for easy method chaining
     * @throws IllegalArgumentException if context, serverURL, appKey, or deviceID are invalid
     * @throws IllegalStateException if the Logging SDK has already been initialized
     */
    Logging init(final Context context, final String serverURL, final String appKey) {
        return init(context, serverURL, appKey, null, OpenUDIDAdapter.isOpenUDIDAvailable() ? DeviceId.Type.OPEN_UDID : DeviceId.Type.ADVERTISING_ID);
    }

    /**
     * Initializes the Logging SDK. Call from your main Activity's onCreate() method.
     * Must be called before other SDK methods can be used.
     * @param context application context
     * @param serverURL URL of the Logging server to submit data to
     * @param appKey app key for the application being tracked; find in the Logging Dashboard under Management &gt; Applications
     * @param deviceID unique ID for the device the app is running on; note that null in deviceID means that Logging will fall back to OpenUDID, then, if it's not available, to Google Advertising ID
     * @return Logging instance for easy method chaining
     * @throws IllegalArgumentException if context, serverURL, appKey, or deviceID are invalid
     * @throws IllegalStateException if init has previously been called with different values during the same application instance
     */
    private Logging init(final Context context, final String serverURL, final String appKey, final String deviceID) {
        return init(context, serverURL, appKey, deviceID, null);
    }

    /**
     * Initializes the Logging SDK. Call from your main Activity's onCreate() method.
     * Must be called before other SDK methods can be used.
     * @param context application context
     * @param serverURL URL of the Logging server to submit data to
     * @param appKey app key for the application being tracked; find in the Logging Dashboard under Management &gt; Applications
     * @param deviceID unique ID for the device the app is running on; note that null in deviceID means that Logging will fall back to OpenUDID, then, if it's not available, to Google Advertising ID
     * @param idMode enum value specifying which device ID generation strategy Logging should use: OpenUDID or Google Advertising ID
     * @return Logging instance for easy method chaining
     * @throws IllegalArgumentException if context, serverURL, appKey, or deviceID are invalid
     * @throws IllegalStateException if init has previously been called with different values during the same application instance
     */
    synchronized Logging init(final Context context, final String serverURL, final String appKey, final String deviceID, DeviceId.Type idMode) {
        return init(context, serverURL, appKey, deviceID, idMode, -1, null, null, null, null);
    }


    /**
     * Initializes the Logging SDK. Call from your main Activity's onCreate() method.
     * Must be called before other SDK methods can be used.
     * @param context application context
     * @param serverURL URL of the Logging server to submit data to
     * @param appKey app key for the application being tracked; find in the Logging Dashboard under Management &gt; Applications
     * @param deviceID unique ID for the device the app is running on; note that null in deviceID means that Logging will fall back to OpenUDID, then, if it's not available, to Google Advertising ID
     * @param idMode enum value specifying which device ID generation strategy Logging should use: OpenUDID or Google Advertising ID
     * @param starRatingLimit sets the limit after how many sessions, for each apps version, the automatic star rating dialog is shown
     * @param starRatingCallback the callback function that will be called from the automatic star rating dialog
     * @param starRatingTextTitle the shown title text for the star rating dialogs
     * @param starRatingTextMessage the shown message text for the star rating dialogs
     * @param starRatingTextDismiss the shown dismiss button text for the shown star rating dialogs
     * @return Logging instance for easy method chaining
     * @throws IllegalArgumentException if context, serverURL, appKey, or deviceID are invalid
     * @throws IllegalStateException if init has previously been called with different values during the same application instance
     */
    private synchronized Logging init(final Context context, String serverURL, final String appKey, final String deviceID, DeviceId.Type idMode,
                                      int starRatingLimit, LoggingStarRating.RatingCallback starRatingCallback, String starRatingTextTitle, String starRatingTextMessage, String starRatingTextDismiss) {

        if (context == null) {
            throw new IllegalArgumentException("valid context is required in Logging init, but was provided 'null'");
        }

        if (!isValidURL(serverURL)) {
            throw new IllegalArgumentException("valid serverURL is required");
        }
        if (serverURL.charAt(serverURL.length() - 1) == '/') {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.i(Logging.TAG, "Removing trailing '/' from provided server url");
            }
            serverURL = serverURL.substring(0, serverURL.length() - 1);//removing trailing '/' from server url
        }

        if (appKey == null || appKey.length() == 0) {
            throw new IllegalArgumentException("valid appKey is required, but was provided either 'null' or empty String");
        }
        if (deviceID != null && deviceID.length() == 0) {
            throw new IllegalArgumentException("valid deviceID is required, but was provided either 'null' or empty String");
        }
        if (deviceID == null && idMode == null) {
            if (OpenUDIDAdapter.isOpenUDIDAvailable()) idMode = DeviceId.Type.OPEN_UDID;
            else if (AdvertisingIdAdapter.isAdvertisingIdAvailable()) idMode = DeviceId.Type.ADVERTISING_ID;
        }
        if (deviceID == null && idMode == DeviceId.Type.OPEN_UDID && !OpenUDIDAdapter.isOpenUDIDAvailable()) {
            throw new IllegalArgumentException("valid deviceID is required because OpenUDID is not available");
        }
        if (deviceID == null && idMode == DeviceId.Type.ADVERTISING_ID && !AdvertisingIdAdapter.isAdvertisingIdAvailable()) {
            throw new IllegalArgumentException("valid deviceID is required because Advertising ID is not available (you need to include Google Play services 4.0+ into your project)");
        }
        if (eventQueue_ != null && (!connectionQueue_.getServerURL().equals(serverURL) ||
                !connectionQueue_.getAppKey().equals(appKey) ||
                !DeviceId.deviceIDEqualsNullSafe(deviceID, idMode, connectionQueue_.getDeviceId()) )) {
            throw new IllegalStateException("Logging cannot be reinitialized with different values");
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Initializing Logging SDk version " + KING_TALK_LOGGING_SDK_VERSION_STRING);
            Log.d(Logging.TAG, "Is consent required? [" + requiresConsent + "]");

            // Context class hierarchy
            // Context
            //|- ContextWrapper
            //|- - Application
            //|- - ContextThemeWrapper
            //|- - - - Activity
            //|- - Service
            //|- - - IntentService

            Class contextClass = context.getClass();
            Class contextSuperClass = contextClass.getSuperclass();

            String contextText = "Provided Context [" + context.getClass().getSimpleName() + "]";
            if(contextSuperClass != null){
                contextText += ", it's superclass: [" + contextSuperClass.getSimpleName() + "]";
            }

            Log.d(Logging.TAG, contextText);

        }

        // In some cases LoggingMessaging does some background processing, so it needs a way
        // to start Logging on itself
        if (MessagingAdapter.isMessagingAvailable()) {
            MessagingAdapter.storeConfiguration(context, serverURL, appKey, deviceID, idMode);
        }


        //set the star rating values
        starRatingCallback_ = starRatingCallback;
        LoggingStarRating.setStarRatingInitConfig(context, starRatingLimit, starRatingTextTitle, starRatingTextMessage, starRatingTextDismiss);

        //app crawler check
        checkIfDeviceIsAppCrawler();

        // if we get here and eventQueue_ != null, init is being called again with the same values,
        // so there is nothing to do, because we are already initialized with those values
        if (eventQueue_ == null) {
            final LoggingStore loggingStore = new LoggingStore(context);

            DeviceId deviceIdInstance;
            if (deviceID != null) {
                deviceIdInstance = new DeviceId(loggingStore, deviceID);
            } else {
                deviceIdInstance = new DeviceId(loggingStore, idMode);
            }


            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Currently cached advertising ID [" + loggingStore.getCachedAdvertisingId() + "]");
            }
            AdvertisingIdAdapter.cacheAdvertisingID(context, loggingStore);

            deviceIdInstance.init(context, loggingStore, true);

            connectionQueue_.setServerURL(serverURL);
            connectionQueue_.setAppKey(appKey);
            connectionQueue_.setLoggingStore(loggingStore);
            connectionQueue_.setDeviceId(deviceIdInstance);
            connectionQueue_.setRequestHeaderCustomValues(requestHeaderCustomValues);

            eventQueue_ = new EventQueue(loggingStore);

            //do star rating related things

            if(getConsent(LoggingFeatureNames.starRating)) {
                LoggingStarRating.registerAppSession(context, starRatingCallback_);
            }
        }

        context_ = context.getApplicationContext();

        // context is allowed to be changed on the second init call
        connectionQueue_.setContext(context_);

        if(requiresConsent) {
            //do delayed push consent action, if needed
            if(delayedPushConsent != null){
                doPushConsentSpecialAction(delayedPushConsent);
            }

            //do delayed location erasure, if needed
            if(delayedLocationErasure){
                doLocationConsentSpecialErasure();
            }

            //send collected consent changes that were made before initialization
            if (collectedConsentChanges.size() != 0) {
                for (String changeItem : collectedConsentChanges) {
                    connectionQueue_.sendConsentChanges(changeItem);
                }
                collectedConsentChanges.clear();
            }

            context_.sendBroadcast(new Intent(CONSENT_BROADCAST));

            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Logging is initialized with the current consent state:");
                checkAllConsent();
            }

            //update remote config values if automatic update is enabled
            if(remoteConfigAutomaticUpdateEnabled && anyConsentGiven()){
                RemoteConfig.updateRemoteConfigValues(context_, null, null, connectionQueue_, false, remoteConfigInitCallback);
            }
        }

        //check for previous native crash dumps
        checkForNativeCrashDumps(context);

        return this;
    }

    /**
     * Checks whether Logging.init has been already called.
     * @return true if Logging is ready to use
     */
    synchronized boolean isInitialized() {
        return eventQueue_ != null;
    }

    /**
     * Initializes the Logging MessagingSDK. Call from your main Activity's onCreate() method.
     * @param activity application activity which acts as a final destination for notifications
     * @param activityClass application activity class which acts as a final destination for notifications
     * @param projectID ProjectID for this app from Google API Console
     * @param mode whether this app installation is a test release or production
     * @return Logging instance for easy method chaining
     * @throws IllegalStateException if no LoggingMessaging class is found (you need to use logging-messaging-sdk-android library instead of logging-sdk-android)
     */
    public Logging initMessaging(Activity activity, Class<? extends Activity> activityClass, String projectID, LoggingMessagingMode mode) {
        return initMessaging(activity, activityClass, projectID, null, mode, false, -1, -1, -1);
    }

    /**
     * Initializes the Logging MessagingSDK. Call from your main Activity's onCreate() method.
     * @param activity application activity which acts as a final destination for notifications
     * @param activityClass application activity class which acts as a final destination for notifications
     * @param projectID ProjectID for this app from Google API Console
     * @param mode whether this app installation is a test release or production
     * @param customIconResId res id for custom icon override
     * @return Logging instance for easy method chaining
     * @throws IllegalStateException if no LoggingMessaging class is found (you need to use logging-messaging-sdk-android library instead of logging-sdk-android)
     */
    private Logging initMessaging(Activity activity, Class<? extends Activity> activityClass, String projectID, LoggingMessagingMode mode, int customIconResId) {
        return initMessaging(activity, activityClass, projectID, null, mode, false, customIconResId, -1, -1);
    }

    /**
     * Initializes the Logging MessagingSDK. Call from your main Activity's onCreate() method.
     * @param activity application activity which acts as a final destination for notifications
     * @param activityClass application activity class which acts as a final destination for notifications
     * @param projectID ProjectID for this app from Google API Console
     * @param mode whether this app installation is a test release or production
     * @param disableUI don't display dialogs & notifications when receiving push notification
     * @return Logging instance for easy method chaining
     * @throws IllegalStateException if no LoggingMessaging class is found (you need to use logging-messaging-sdk-android library instead of logging-sdk-android)
     */
    private Logging initMessaging(Activity activity, Class<? extends Activity> activityClass, String projectID, LoggingMessagingMode mode, boolean disableUI) {
        return initMessaging(activity, activityClass, projectID, null, mode, disableUI, -1, -1, -1);
    }
    /**
     * Initializes the Logging MessagingSDK. Call from your main Activity's onCreate() method.
     * @param activity application activity which acts as a final destination for notifications
     * @param activityClass application activity class which acts as a final destination for notifications
     * @param projectID ProjectID for this app from Google API Console
     * @param buttonNames Strings to use when displaying Dialogs (uses new String[]{"Open", "Review"} by default)
     * @param mode whether this app installation is a test release or production
     * @return Logging instance for easy method chaining
     * @throws IllegalStateException if no LoggingMessaging class is found (you need to use logging-messaging-sdk-android library instead of logging-sdk-android)
     */
    private synchronized Logging initMessaging(Activity activity, Class<? extends Activity> activityClass, String projectID, String[] buttonNames, LoggingMessagingMode mode) {
        return initMessaging(activity, activityClass, projectID, buttonNames, mode, false, -1, -1, -1);
    }

    /**
     * Initializes the Logging MessagingSDK. Call from your main Activity's onCreate() method.
     * @param activity application activity which acts as a final destination for notifications
     * @param activityClass application activity class which acts as a final destination for notifications
     * @param projectID ProjectID for this app from Google API Console
     * @param buttonNames Strings to use when displaying Dialogs (uses new String[]{"Open", "Review"} by default)
     * @param mode whether this app installation is a test release or production
     * @param disableUI don't display dialogs & notifications when receiving push notification
     * @return Logging instance for easy method chaining
     * @throws IllegalStateException if no LoggingMessaging class is found (you need to use logging-messaging-sdk-android library instead of logging-sdk-android)
     */
    private synchronized Logging initMessaging(Activity activity, Class<? extends Activity> activityClass, String projectID, String[] buttonNames, LoggingMessagingMode mode, boolean disableUI) {
        return initMessaging(activity, activityClass, projectID, buttonNames, mode, disableUI, -1, -1, -1);
    }

    /**
     * Initializes the Logging MessagingSDK. Call from your main Activity's onCreate() method.
     * @param activity application activity which acts as a final destination for notifications
     * @param activityClass application activity class which acts as a final destination for notifications
     * @param projectID ProjectID for this app from Google API Console
     * @param buttonNames Strings to use when displaying Dialogs (uses new String[]{"Open", "Review"} by default)
     * @param mode whether this app installation is a test release or production
     * @param disableUI don't display dialogs & notifications when receiving push notification
     * @param customSmallIconResId res id for custom icon override
     * @return Logging instance for easy method chaining
     * @throws IllegalStateException if no LoggingMessaging class is found (you need to use logging-messaging-sdk-android library instead of logging-sdk-android)
     */
    private synchronized Logging initMessaging(Activity activity, Class<? extends Activity> activityClass, String projectID, String[] buttonNames, LoggingMessagingMode mode, boolean disableUI, int customSmallIconResId, int customLargeIconRes, int customAccentColor) {
        try {
            Class.forName("com.kingtalk.logging.LoggingPush");
            throw new IllegalStateException("Please remove initMessaging() call, for FCM integration you need to use LoggingPush class");
        } catch (ClassNotFoundException ignored) { }

        if (mode != null && !MessagingAdapter.isMessagingAvailable()) {
            throw new IllegalStateException("you need to include sdk-messaging library instead of sdk if you want to use Logging Messaging");
        } else {
            messagingMode_ = mode;
            if (!MessagingAdapter.init(activity, activityClass, projectID, buttonNames, disableUI, customSmallIconResId, addMetadataToPushIntents, customLargeIconRes, customAccentColor)) {
                throw new IllegalStateException("couldn't initialize Logging Messaging");
            }
        }

        if (MessagingAdapter.isMessagingAvailable()) {
            MessagingAdapter.storeConfiguration(connectionQueue_.getContext(), connectionQueue_.getServerURL(), connectionQueue_.getAppKey(), connectionQueue_.getDeviceId().getId(), connectionQueue_.getDeviceId().getType());
        }

        return this;
    }

    /**
     * Immediately disables session &amp; event tracking and clears any stored session &amp; event data.
     * This API is useful if your app has a tracking opt-out switch, and you want to immediately
     * disable tracking when a user opts out. The onStart/onStop/recordEvent methods will throw
     * IllegalStateException after calling this until Logging is reinitialized by calling init
     * again.
     */
    private synchronized void halt() {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.i(Logging.TAG, "Halting Logging!");
        }
        eventQueue_ = null;
        final LoggingStore loggingStore = connectionQueue_.getLoggingStore();
        if (loggingStore != null) {
            loggingStore.clear();
        }
        connectionQueue_.setContext(null);
        connectionQueue_.setServerURL(null);
        connectionQueue_.setAppKey(null);
        connectionQueue_.setLoggingStore(null);
        prevSessionDurationStartTime_ = 0;
        activityCount_ = 0;
    }

    /**
     * Tells the Logging SDK that an Activity has started. Since Android does not have an
     * easy way to determine when an application instance starts and stops, you must call this
     * method from every one of your Activity's onStart methods for accurate application
     * session tracking.
     * @throws IllegalStateException if Logging SDK has not been initialized
     */
    synchronized void onStart(Activity activity) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Logging onStart called, [" + activityCount_ + "] -> [" + (activityCount_ + 1) + "] activities now open");
        }

        appLaunchDeepLink = false;
        if (eventQueue_ == null) {
            throw new IllegalStateException("init must be called before onStart");
        }

        ++activityCount_;
        if (activityCount_ == 1) {
            onStartHelper();
        }

        //check if there is an install referrer data
        String referrer = ReferrerReceiver.getReferrer(context_);
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Checking referrer: " + referrer);
        }
        if(referrer != null){
            connectionQueue_.sendReferrerData(referrer);
            ReferrerReceiver.deleteReferrer(context_);
        }

        CrashDetails.inForeground();

        if(autoViewTracker){
            String usedActivityName;

            if(automaticTrackingShouldUseShortName){
                usedActivityName = activity.getClass().getSimpleName();
            } else {
                usedActivityName = activity.getClass().getName();
            }
            recordView(usedActivityName);
        }

        calledAtLeastOnceOnStart = true;
    }

    /**
     * Called when the first Activity is started. Sends a begin session event to the server
     * and initializes application session tracking.
     */
    private void onStartHelper() {
        prevSessionDurationStartTime_ = System.nanoTime();
        connectionQueue_.beginSession();
    }

    /**
     * Tells the Logging SDK that an Activity has stopped. Since Android does not have an
     * easy way to determine when an application instance starts and stops, you must call this
     * method from every one of your Activity's onStop methods for accurate application
     * session tracking.
     * @throws IllegalStateException if Logging SDK has not been initialized, or if
     *                               unbalanced calls to onStart/onStop are detected
     */
    public synchronized void onStop() {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Logging onStop called, [" + activityCount_ + "] -> [" + (activityCount_ - 1) + "] activities now open");
        }

        if (eventQueue_ == null) {
            throw new IllegalStateException("init must be called before onStop");
        }
        if (activityCount_ == 0) {
            throw new IllegalStateException("must call onStart before onStop");
        }

        --activityCount_;
        if (activityCount_ == 0) {
            onStopHelper();
        }

        CrashDetails.inBackground();

        //report current view duration
        reportViewDuration();
    }

    /**
     * Called when final Activity is stopped. Sends an end session event to the server,
     * also sends any unsent custom events.
     */
    private void onStopHelper() {
        connectionQueue_.endSession(roundedSecondsSinceLastSessionDurationUpdate());
        prevSessionDurationStartTime_ = 0;

        if (eventQueue_.size() > 0) {
            connectionQueue_.recordEvents(eventQueue_.events());
        }
    }

    /**
     * Called when GCM Registration ID is received. Sends a token session event to the server.
     */
    void onRegistrationId(String registrationId) {
        onRegistrationId(registrationId, messagingMode_);
    }

    /**
     * DON'T USE THIS!!!!
     */
    void onRegistrationId(String registrationId, LoggingMessagingMode mode) {
        if(!getConsent(LoggingFeatureNames.push)) {
            return;
        }

        connectionQueue_.tokenSession(registrationId, mode);
    }

    /**
     * Changes current device id type to the one specified in parameter. Closes current session and
     * reopens new one with new id. Doesn't merge user profiles on the server
     * @param type Device ID type to change to
     * @param deviceId Optional device ID for a case when type = DEVELOPER_SPECIFIED
     */
    private void changeDeviceId(DeviceId.Type type, String deviceId) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Changing device ID");
        }
        if (eventQueue_ == null) {
            throw new IllegalStateException("init must be called before changeDeviceId");
        }
        if (activityCount_ == 0) {
            throw new IllegalStateException("must call onStart before changeDeviceId");
        }
        if (type == null) {
            throw new IllegalStateException("type cannot be null");
        }

        if(!anyConsentGiven()){
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.w(Logging.TAG, "Can't change Device ID if no consent is given");
            }
            return;
        }

        connectionQueue_.endSession(roundedSecondsSinceLastSessionDurationUpdate(), connectionQueue_.getDeviceId().getId());
        connectionQueue_.getDeviceId().changeToId(context_, connectionQueue_.getLoggingStore(), type, deviceId);
        connectionQueue_.beginSession();

        //update remote config values if automatic update is enabled
        remoteConfigClearValues();
        if(remoteConfigAutomaticUpdateEnabled && anyConsentGiven()){
            RemoteConfig.updateRemoteConfigValues(context_, null, null, connectionQueue_, false, null);
        }
    }

    /**
     * Changes current device id to the one specified in parameter. Merges user profile with new id
     * (if any) with old profile.
     * @param deviceId new device id
     */
    private void changeDeviceId(String deviceId) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Changing device ID");
        }
        if (eventQueue_ == null) {
            throw new IllegalStateException("init must be called before changeDeviceId");
        }
        if (activityCount_ == 0) {
            throw new IllegalStateException("must call onStart before changeDeviceId");
        }
        if (deviceId == null || "".equals(deviceId)) {
            throw new IllegalStateException("deviceId cannot be null or empty");
        }

        if(!anyConsentGiven()){
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.w(Logging.TAG, "Can't change Device ID if no consent is given");
            }
            return;
        }

        connectionQueue_.changeDeviceId(deviceId, roundedSecondsSinceLastSessionDurationUpdate());

        //update remote config values if automatic update is enabled
        remoteConfigClearValues();
        if(remoteConfigAutomaticUpdateEnabled && anyConsentGiven()){
            //request should be delayed, because of the delayed server merge
            RemoteConfig.updateRemoteConfigValues(context_, null, null, connectionQueue_, true,null);
        }
    }

    /**
     * Records a custom event with no segmentation values, a count of one and a sum of zero.
     * @param key name of the custom event, required, must not be the empty string
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty
     */
    private void recordEvent(final String key) {
        recordEvent(key, null, 1, 0);
    }

    /**
     * Records a custom event with no segmentation values, the specified count, and a sum of zero.
     * @param key name of the custom event, required, must not be the empty string
     * @param count count to associate with the event, should be more than zero
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty
     */
    private void recordEvent(final String key, final int count) {
        recordEvent(key, null, count, 0);
    }

    /**
     * Records a custom event with no segmentation values, and the specified count and sum.
     * @param key name of the custom event, required, must not be the empty string
     * @param count count to associate with the event, should be more than zero
     * @param sum sum to associate with the event
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty
     */
    public void recordEvent(final String key, final int count, final double sum) {
        recordEvent(key, null, count, sum);
    }

    /**
     * Records a custom event with the specified segmentation values and count, and a sum of zero.
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation dictionary to associate with the event, can be null
     * @param count count to associate with the event, should be more than zero
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty
     */
    void recordEvent(final String key, final Map<String, String> segmentation, final int count) {
        recordEvent(key, segmentation, count, 0);
    }

    /**
     * Records a custom event with the specified values.
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation dictionary to associate with the event, can be null
     * @param count count to associate with the event, should be more than zero
     * @param sum sum to associate with the event
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty, count is less than 1, or if
     *                                  segmentation contains null or empty keys or values
     */
    private synchronized void recordEvent(final String key, final Map<String, String> segmentation, final int count, final double sum) {
        recordEvent(key, segmentation, count, sum, 0);
    }

    /**
     * Records a custom event with the specified values.
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation dictionary to associate with the event, can be null
     * @param count count to associate with the event, should be more than zero
     * @param sum sum to associate with the event
     * @param dur duration of an event
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty, count is less than 1, or if
     *                                  segmentation contains null or empty keys or values
     */
    private synchronized void recordEvent(final String key, final Map<String, String> segmentation, final int count, final double sum, final double dur){
        recordEvent(key, segmentation, null, null, count, sum, 0);
    }

    /**
     * Records a custom event with the specified values.
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation dictionary to associate with the event, can be null
     * @param count count to associate with the event, should be more than zero
     * @param sum sum to associate with the event
     * @param dur duration of an event
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty, count is less than 1, or if
     *                                  segmentation contains null or empty keys or values
     */
    private synchronized void recordEvent(final String key, final Map<String, String> segmentation, final Map<String, Integer> segmentationInt, final Map<String, Double> segmentationDouble, final int count, final double sum, final double dur) {
        if (!isInitialized()) {
            throw new IllegalStateException("Logging.sharedInstance().init must be called before recordEvent");
        }
        if (key == null || key.length() == 0) {
            throw new IllegalArgumentException("Valid Logging event key is required");
        }
        if (count < 1) {
            throw new IllegalArgumentException("Logging event count should be greater than zero");
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Recording event with key: [" + key + "]");
        }

        if (segmentation != null) {
            for (String k : segmentation.keySet()) {
                if (k == null || k.length() == 0) {
                    throw new IllegalArgumentException("Logging event segmentation key cannot be null or empty");
                }
                if (segmentation.get(k) == null || segmentation.get(k).length() == 0) {
                    throw new IllegalArgumentException("Logging event segmentation value cannot be null or empty");
                }
            }
        }

        if (segmentationInt != null) {
            for (String k : segmentationInt.keySet()) {
                if (k == null || k.length() == 0) {
                    throw new IllegalArgumentException("Logging event segmentation key cannot be null or empty");
                }
                if (segmentationInt.get(k) == null) {
                    throw new IllegalArgumentException("Logging event segmentation value cannot be null");
                }
            }
        }

        if (segmentationDouble != null) {
            for (String k : segmentationDouble.keySet()) {
                if (k == null || k.length() == 0) {
                    throw new IllegalArgumentException("Logging event segmentation key cannot be null or empty");
                }
                if (segmentationDouble.get(k) == null) {
                    throw new IllegalArgumentException("Logging event segmentation value cannot be null");
                }
            }
        }

        switch (key) {
            case STAR_RATING_EVENT_KEY:
                if (Logging.sharedInstance().getConsent(LoggingFeatureNames.starRating)) {
                    eventQueue_.recordEvent(key, segmentation, segmentationInt, segmentationDouble, count, sum, dur);
                    sendEventsForced();
                }
                break;
            case VIEW_EVENT_KEY:
                if (Logging.sharedInstance().getConsent(LoggingFeatureNames.views)) {
                    eventQueue_.recordEvent(key, segmentation, segmentationInt, segmentationDouble, count, sum, dur);
                    sendEventsForced();
                }
                break;
            default:
                if (Logging.sharedInstance().getConsent(LoggingFeatureNames.events)) {
                    eventQueue_.recordEvent(key, segmentation, segmentationInt, segmentationDouble, count, sum, dur);
                    sendEventsIfNeeded();
                }
                break;
        }
    }

    /**
     * Enable or disable automatic view tracking
     * @param enable boolean for the state of automatic view tracking
     * @return Returns link to Logging for call chaining
     */
    synchronized Logging setViewTracking(boolean enable){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Enabling automatic view tracking");
        }
        autoViewTracker = enable;
        return this;
    }

    /**
     * Check state of automatic view tracking
     * @return boolean - true if enabled, false if disabled
     */
    public synchronized boolean isViewTrackingEnabled(){
        return autoViewTracker;
    }

    /**
     *  Record a view manually, without automatic tracking
     * or track view that is not automatically tracked
     * like fragment, Message box or transparent Activity
     * @param viewName String - name of the view
     * @return Returns link to Logging for call chaining
     */
    private synchronized Logging recordView(String viewName){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Recording view with name: [" + viewName + "]");
        }

        reportViewDuration();
        lastView = viewName;
        lastViewStart = Logging.currentTimestamp();
        HashMap<String, String> segments = new HashMap<>();
        segments.put("name", viewName);
        segments.put("visit", "1");
        segments.put("segment", "Android");
        if(firstView) {
            firstView = false;
            segments.put("start", "1");
        }
        recordEvent(VIEW_EVENT_KEY, segments, 1);
        return this;
    }

    /**
     * Sets information about user. Possible keys are:
     * <ul>
     * <li>
     * name - (String) providing user's full name
     * </li>
     * <li>
     * username - (String) providing user's nickname
     * </li>
     * <li>
     * email - (String) providing user's email address
     * </li>
     * <li>
     * organization - (String) providing user's organization's name where user works
     * </li>
     * <li>
     * phone - (String) providing user's phone number
     * </li>
     * <li>
     * picture - (String) providing WWW URL to user's avatar or profile picture
     * </li>
     * <li>
     * picturePath - (String) providing local path to user's avatar or profile picture
     * </li>
     * <li>
     * gender - (String) providing user's gender as M for male and F for female
     * </li>
     * <li>
     * byear - (int) providing user's year of birth as integer
     * </li>
     * </ul>
     * @param data Map&lt;String, String&gt; with user data
     * @deprecated use {@link UserData#setUserData(Map)} to set data and {@link UserData#save()} to send it to server.
     */
    public synchronized Logging setUserData(Map<String, String> data) {
        return setUserData(data, null);
    }

    /**
     * Sets information about user with custom properties.
     * In custom properties you can provide any string key values to be stored with user
     * Possible keys are:
     * <ul>
     * <li>
     * name - (String) providing user's full name
     * </li>
     * <li>
     * username - (String) providing user's nickname
     * </li>
     * <li>
     * email - (String) providing user's email address
     * </li>
     * <li>
     * organization - (String) providing user's organization's name where user works
     * </li>
     * <li>
     * phone - (String) providing user's phone number
     * </li>
     * <li>
     * picture - (String) providing WWW URL to user's avatar or profile picture
     * </li>
     * <li>
     * picturePath - (String) providing local path to user's avatar or profile picture
     * </li>
     * <li>
     * gender - (String) providing user's gender as M for male and F for female
     * </li>
     * <li>
     * byear - (int) providing user's year of birth as integer
     * </li>
     * </ul>
     * @param data Map&lt;String, String&gt; with user data
     * @param customdata Map&lt;String, String&gt; with custom key values for this user
     * @deprecated use {@link UserData#setUserData(Map, Map)} to set data and {@link UserData#save()}  to send it to server.
     */
    public synchronized Logging setUserData(Map<String, String> data, Map<String, String> customdata) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting user data");
        }
        UserData.setData(data);
        if(customdata != null)
            UserData.setCustomData(customdata);
        connectionQueue_.sendUserData();
        UserData.clear();
        return this;
    }

    /**
     * Sets custom properties.
     * In custom properties you can provide any string key values to be stored with user
     * @param customdata Map&lt;String, String&gt; with custom key values for this user
     * @deprecated use {@link UserData#setCustomUserData(Map)} to set data and {@link UserData#save()} to send it to server.
     */
    public synchronized Logging setCustomUserData(Map<String, String> customdata) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting custom user data");
        }
        if(customdata != null)
            UserData.setCustomData(customdata);
        connectionQueue_.sendUserData();
        UserData.clear();
        return this;
    }

    /**
     * Disable sending of location data
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging disableLocation() {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Disabling location");
        }

        if(!getConsent(LoggingFeatureNames.location)){
            //can't send disable location request if no consent given
            return this;
        }

        resetLocationValues();
        connectionQueue_.getLoggingStore().setLocationDisabled(true);
        connectionQueue_.sendLocation();

        return this;
    }

    private synchronized void resetLocationValues(){
        connectionQueue_.getLoggingStore().setLocationCountryCode("");
        connectionQueue_.getLoggingStore().setLocationCity("");
        connectionQueue_.getLoggingStore().setLocation("");
        connectionQueue_.getLoggingStore().setLocationIpAddress("");
    }

    /**
     * Set location parameters. If they are set before begin_session, they will be sent as part of it.
     * If they are set after, then they will be sent as a separate request.
     * If this is called after disabling location, it will enable it.
     * @param country_code ISO Country code for the user's country
     * @param city Name of the user's city
     * @param location comma separate lat and lng values. For example, "56.42345,123.45325"
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging setLocation(String country_code, String city, String location, String ipAddress){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting location parameters");
        }

        if(!getConsent(LoggingFeatureNames.location)){
            return this;
        }

        if(country_code != null){
            connectionQueue_.getLoggingStore().setLocationCountryCode(country_code);
        }

        if(city != null){
            connectionQueue_.getLoggingStore().setLocationCity(city);
        }

        if(location != null){
            connectionQueue_.getLoggingStore().setLocation(location);
        }

        if(ipAddress != null){
            connectionQueue_.getLoggingStore().setLocationIpAddress(ipAddress);
        }

        if((country_code == null && city != null) || (city == null && country_code != null)) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.w(Logging.TAG, "In \"setLocation\" both city and country code need to be set at the same time to be sent");
            }
        }

        if(country_code != null || city != null || location != null || ipAddress != null){
            connectionQueue_.getLoggingStore().setLocationDisabled(false);
        }


        if(isBeginSessionSent || !Logging.sharedInstance().getConsent(LoggingFeatureNames.sessions)){
            //send as a seperate request if either begin session was already send and we missed our first opportunity
            //or if consent for sessions is not given and our only option to send this is as a separate request
            connectionQueue_.sendLocation();
        }  //will be sent a part of begin session


        return this;
    }

    /**
     * Sets custom segments to be reported with crash reports
     * In custom segments you can provide any string key values to segments crashes by
     * @param segments Map&lt;String, String&gt; key segments and their values
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging setCustomCrashSegments(Map<String, String> segments) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting custom crash segments");
        }

        if(!getConsent(LoggingFeatureNames.crashes)){
            return this;
        }

        if(segments != null) {
            CrashDetails.setCustomSegments(segments);
        }
        return this;
    }

    /**
     * Add crash breadcrumb like log record to the log that will be send together with crash report
     * @param record String a bread crumb for the crash report
     * @return Returns link to Logging for call chaining
     * @deprecated use `addCrashBreadcrumb`
     */
    public synchronized Logging addCrashLog(String record) {
        return addCrashBreadcrumb(record);
    }

    /**
     * Add crash breadcrumb like log record to the log that will be send together with crash report
     * @param record String a bread crumb for the crash report
     * @return Returns link to Logging for call chaining
     */
    private synchronized Logging addCrashBreadcrumb(String record) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Adding crash breadcrumb");
        }

        if(!getConsent(LoggingFeatureNames.crashes)){
            return this;
        }

        if(record == null || record.isEmpty()) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Can't add a null or empty crash breadcrumb");
            }
            return this;
        }

        CrashDetails.addLog(record);
        return this;
    }

    /**
     * Called during init to check if there are any crash dumps saved
     * @param context
     */
    private synchronized void checkForNativeCrashDumps(Context context){
        Log.d(TAG, "Checking for native crash dumps");

        String basePath = context.getCacheDir().getAbsolutePath();
        String finalPath = basePath + File.separator + loggingFolderName + File.separator + loggingNativeCrashFolderName;

        File folder = new File(finalPath);
        if (folder.exists()) {
            Log.d(TAG, "Native crash folder exists, checking for dumps");

            File[] dumpFiles = folder.listFiles();
            Log.d(TAG,"Crash dump folder contains [" + dumpFiles.length + "] files");
            for (File dumpFile : dumpFiles) {
                //record crash
                recordNativeException(dumpFile);

                //delete dump file
                dumpFile.delete();
            }
        } else {
            Log.d(TAG, "Native crash folder does not exist");
        }
    }

    private synchronized void recordNativeException(File dumpFile){
        Log.d(TAG, "Recording native crash dump: [" + dumpFile.getName() + "]");

        //check for consent
        if(!getConsent(LoggingFeatureNames.crashes)){
            return;
        }

        //read bytes
        int size = (int)dumpFile.length();
        byte[] bytes = new byte[size];

        try {
            BufferedInputStream buf = new BufferedInputStream(new FileInputStream(dumpFile));
            buf.read(bytes, 0, bytes.length);
            buf.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read dump file bytes");
            e.printStackTrace();
            return;
        }

        //convert to base64
        String dumpString = Base64.encodeToString(bytes, Base64.NO_WRAP);

        //record crash
        connectionQueue_.sendCrashReport(dumpString, false, true);
    }

    /**
     * Log handled exception to report it to server as non fatal crash
     * @param exception Exception to log
     * @deprecated Use recordHandledException
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging logException(Exception exception) {
        return recordException(exception, true);
    }

    /**
     * Log handled exception to report it to server as non fatal crash
     * @param exception Exception to log
     * @return Returns link to Logging for call chaining
     */
    private synchronized Logging recordHandledException(Exception exception) {
        return recordException(exception, true);
    }

    /**
     * Log handled exception to report it to server as non fatal crash
     * @param exception Throwable to log
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging recordHandledException(Throwable exception) {
        return recordException(exception, true);
    }

    /**
     * Log unhandled exception to report it to server as fatal crash
     * @param exception Exception to log
     * @return Returns link to Logging for call chaining
     */
    private synchronized void recordUnhandledException(Exception exception) {
        recordException(exception, false);
    }

    /**
     * Log unhandled exception to report it to server as fatal crash
     * @param exception Throwable to log
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging recordUnhandledException(Throwable exception) {
        return recordException(exception, false);
    }

    /**
     * Common call for handling exceptions
     * @param exception Exception to log
     * @param itIsHandled If the exception is handled or not (fatal)
     * @return Returns link to Logging for call chaining
     */
    private synchronized Logging recordException(Throwable exception, boolean itIsHandled) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Logging exception, handled:[" + itIsHandled + "]");
        }

        if(!getConsent(LoggingFeatureNames.crashes)){
            return this;
        }

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        connectionQueue_.sendCrashReport(sw.toString(), itIsHandled, false);
        return this;
    }

    /**
     * Enable crash reporting to send unhandled crash reports to server
     * @return Returns link to Logging for call chaining
     */
    synchronized Logging enableCrashReporting() {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Enabling unhandled crash reporting");
        }
        //get default handler
        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.UncaughtExceptionHandler handler = new Thread.UncaughtExceptionHandler() {

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if(getConsent(LoggingFeatureNames.crashes)){
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    e.printStackTrace(pw);

                    Logging.sharedInstance().connectionQueue_.sendCrashReport(sw.toString(), false, false);
                }

                //if there was another handler before
                if(oldHandler != null){
                    //notify it also
                    oldHandler.uncaughtException(t,e);
                }
            }
        };

        Thread.setDefaultUncaughtExceptionHandler(handler);
        return this;
    }

    /**
     * Start timed event with a specified key
     * @param key name of the custom event, required, must not be the empty string or null
     * @return true if no event with this key existed before and event is started, false otherwise
     */
    public synchronized boolean startEvent(final String key) {
        if (!isInitialized()) {
            throw new IllegalStateException("Logging.sharedInstance().init must be called before recordEvent");
        }
        if (key == null || key.length() == 0) {
            throw new IllegalArgumentException("Valid Logging event key is required");
        }
        if (timedEvents.containsKey(key)) {
            return false;
        }
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Starting event: [" + key + "]");
        }
        timedEvents.put(key, new Event(key));
        return true;
    }

    /**
     * End timed event with a specified key
     * @param key name of the custom event, required, must not be the empty string or null
     * @return true if event with this key has been previously started, false otherwise
     */
    public synchronized boolean endEvent(final String key) {
        return endEvent(key, null, 1, 0);
    }

    /**
     * End timed event with a specified key
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation dictionary to associate with the event, can be null
     * @param count count to associate with the event, should be more than zero
     * @param sum sum to associate with the event
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty, count is less than 1, or if
     *                                  segmentation contains null or empty keys or values
     * @return true if event with this key has been previously started, false otherwise
     */
    private synchronized boolean endEvent(final String key, final Map<String, String> segmentation, final int count, final double sum) {
        return endEvent(key, segmentation, null, null, count, sum);
    }
    /**
     * End timed event with a specified key
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation dictionary to associate with the event, can be null
     * @param count count to associate with the event, should be more than zero
     * @param sum sum to associate with the event
     * @throws IllegalStateException if Logging SDK has not been initialized
     * @throws IllegalArgumentException if key is null or empty, count is less than 1, or if
     *                                  segmentation contains null or empty keys or values
     * @return true if event with this key has been previously started, false otherwise
     */
    private synchronized boolean endEvent(final String key, final Map<String, String> segmentation, final Map<String, Integer> segmentationInt, final Map<String, Double> segmentationDouble, final int count, final double sum) {
        Event event = timedEvents.remove(key);

        if (event != null) {
            if(!getConsent(LoggingFeatureNames.events)) {
                return true;
            }

            if (!isInitialized()) {
                throw new IllegalStateException("Logging.sharedInstance().init must be called before recordEvent");
            }
            if (key == null || key.length() == 0) {
                throw new IllegalArgumentException("Valid Logging event key is required");
            }
            if (count < 1) {
                throw new IllegalArgumentException("Logging event count should be greater than zero");
            }
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Ending event: [" + key + "]");
            }

            if (segmentation != null) {
                for (String k : segmentation.keySet()) {
                    if (k == null || k.length() == 0) {
                        throw new IllegalArgumentException("Logging event segmentation key cannot be null or empty");
                    }
                    if (segmentation.get(k) == null || segmentation.get(k).length() == 0) {
                        throw new IllegalArgumentException("Logging event segmentation value cannot be null or empty");
                    }
                }
            }

            if (segmentationInt != null) {
                for (String k : segmentationInt.keySet()) {
                    if (k == null || k.length() == 0) {
                        throw new IllegalArgumentException("Logging event segmentation key cannot be null or empty");
                    }
                    if (segmentationInt.get(k) == null) {
                        throw new IllegalArgumentException("Logging event segmentation value cannot be null");
                    }
                }
            }

            if (segmentationDouble != null) {
                for (String k : segmentationDouble.keySet()) {
                    if (k == null || k.length() == 0) {
                        throw new IllegalArgumentException("Logging event segmentation key cannot be null or empty");
                    }
                    if (segmentationDouble.get(k) == null) {
                        throw new IllegalArgumentException("Logging event segmentation value cannot be null");
                    }
                }
            }

            long currentTimestamp = Logging.currentTimestampMs();

            event.segmentation = segmentation;
            event.segmentationDouble = segmentationDouble;
            event.segmentationInt = segmentationInt;
            event.dur = (currentTimestamp - event.timestamp) / 1000.0;
            event.count = count;
            event.sum = sum;

            eventQueue_.recordEvent(event);
            sendEventsIfNeeded();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Disable periodic session time updates.
     * By default, Logging will send a request to the server each 30 seconds with a small update
     * containing session duration time. This method allows you to disable such behavior.
     * Note that event updates will still be sent every 10 events or 30 seconds after event recording.
     * @param disable whether or not to disable session time updates
     * @return Logging instance for easy method chaining
     */
    private synchronized Logging setDisableUpdateSessionRequests(final boolean disable) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Disabling periodic session time updates");
        }
        disableUpdateSessionRequests_ = disable;
        return this;
    }

    /**
     * Sets whether debug logging is turned on or off. Logging is disabled by default.
     * @return Logging instance for easy method chaining
     */
    synchronized Logging setLoggingEnabled() {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Enabling logging");
        }
        enableLogging_ = true;
        return this;
    }

    public synchronized boolean isLoggingEnabled() {
        return enableLogging_;
    }

    public synchronized Logging enableParameterTamperingProtection(String salt) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Enabling tamper protection");
        }
        ConnectionProcessor.salt = salt;
        return this;
    }

    /**
     * Returns if the logging sdk onStart function has been called at least once
     * @return true - yes, it has, false - no it has not
     */
    public synchronized boolean hasBeenCalledOnStart() {
        return calledAtLeastOnceOnStart;
    }

    public synchronized Logging setEventQueueSizeToSend(int size) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting event queue size: [" + size + "]");
        }
        eventQueueSize = size;
        return this;
    }

    private boolean appLaunchDeepLink = true;

    static void onCreate(Activity activity) {
        Intent launchIntent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());

        if (sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Activity created: " + activity.getClass().getName() + " ( main is " + launchIntent.getComponent().getClassName() + ")");
        }

        Intent intent = activity.getIntent();
        if (intent != null) {
            Uri data = intent.getData();
            if (data != null) {
                if (sharedInstance().isLoggingEnabled()) {
                    Log.d(Logging.TAG, "Data in activity created intent: " + data + " (appLaunchDeepLink " + sharedInstance().appLaunchDeepLink + ") " );
                }
                if (sharedInstance().appLaunchDeepLink) {
                    DeviceInfo.deepLink = data.toString();
                }
            }
        }
    }

    /**
     * Reports duration of last view
     */
    private void reportViewDuration(){
        if (sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "View [" + lastView + "] is getting closed, reporting duration: [" + (Logging.currentTimestamp() - lastViewStart) + "]");
        }

        if(lastView != null && lastViewStart <= 0) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Last view start value is not normal: [" + lastViewStart + "]");
            }
        }

        if(!getConsent(LoggingFeatureNames.views)) {
            return;
        }

        //only record view if the view name is not null and if it has a reasonable duration
        //if the lastViewStart is equal to 0, the duration would be set to the current timestamp
        //and therefore will be ignored
        if(lastView != null && lastViewStart > 0){
            HashMap<String, String> segments = new HashMap<>();
            segments.put("name", lastView);
            segments.put("dur", String.valueOf(Logging.currentTimestamp()-lastViewStart));
            segments.put("segment", "Android");
            recordEvent(VIEW_EVENT_KEY,segments,1);
            lastView = null;
            lastViewStart = 0;
        }
    }

    /**
     * Submits all of the locally queued events to the server if there are more than 10 of them.
     */
    private void sendEventsIfNeeded() {
        if (eventQueue_.size() >= eventQueueSize) {
            connectionQueue_.recordEvents(eventQueue_.events());
        }
    }

    /**
     * Immediately sends all stored events
     */
    private void sendEventsForced() {
        connectionQueue_.recordEvents(eventQueue_.events());
    }

    /**
     * Called every 60 seconds to send a session heartbeat to the server. Does nothing if there
     * is not an active application session.
     */
    private synchronized void onTimer() {
        final boolean hasActiveSession = activityCount_ > 0;
        if (hasActiveSession) {
            if (!disableUpdateSessionRequests_) {
                connectionQueue_.updateSession(roundedSecondsSinceLastSessionDurationUpdate());
            }
            if (eventQueue_.size() > 0) {
                connectionQueue_.recordEvents(eventQueue_.events());
            }
        }

        if(isInitialized()){
            connectionQueue_.tick();
        }
    }

    /**
     * Calculates the unsent session duration in seconds, rounded to the nearest int.
     */
    private int roundedSecondsSinceLastSessionDurationUpdate() {
        final long currentTimestampInNanoseconds = System.nanoTime();
        final long unsentSessionLengthInNanoseconds = currentTimestampInNanoseconds - prevSessionDurationStartTime_;
        prevSessionDurationStartTime_ = currentTimestampInNanoseconds;
        return (int) Math.round(unsentSessionLengthInNanoseconds / 1000000000.0d);
    }

    /**
     * Utility method to return a current timestamp that can be used in the Count.ly API.
     */
    static int currentTimestamp() {
        return ((int)(System.currentTimeMillis() / 1000L));
    }

    static class TimeUniquesEnsurer {
        final List<Long> lastTsMs = new ArrayList<>(10);
        final long addition = 0;

        long currentTimeMillis() {
            return System.currentTimeMillis() + addition;
        }

        synchronized long uniqueTimestamp() {
            long ms = currentTimeMillis();

            // change time back case
            if (lastTsMs.size() > 2) {
                long min = Collections.min(lastTsMs);
                if (ms < min) {
                    lastTsMs.clear();
                    lastTsMs.add(ms);
                    return ms;
                }
            }
            // usual case
            while (lastTsMs.contains(ms)) {
                ms += 1;
            }
            while (lastTsMs.size() >= 10) {
                lastTsMs.remove(0);
            }
            lastTsMs.add(ms);
            return ms;
        }
    }
    private static final TimeUniquesEnsurer timeGenerator = new TimeUniquesEnsurer();

    static synchronized long currentTimestampMs() {
        return timeGenerator.uniqueTimestamp();
    }

    /**
     * Utility method to return a current hour of the day that can be used in the Count.ly API.
     */
    static int currentHour(){return Calendar.getInstance().get(Calendar.HOUR_OF_DAY); }

    /**
     * Utility method to return a current day of the week that can be used in the Count.ly API.
     */
    @SuppressLint("SwitchIntDef")
    static int currentDayOfWeek(){
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        switch (day) {
            case Calendar.MONDAY:
                return 1;
            case Calendar.TUESDAY:
                return 2;
            case Calendar.WEDNESDAY:
                return 3;
            case Calendar.THURSDAY:
                return 4;
            case Calendar.FRIDAY:
                return 5;
            case Calendar.SATURDAY:
                return 6;
        }
        return 0;
    }

    /**
     * Utility method for testing validity of a URL.
     */
    static boolean isValidURL(final String urlStr) {
        boolean validURL = false;
        if (urlStr != null && urlStr.length() > 0) {
            try {
                new URL(urlStr);
                validURL = true;
            }
            catch (MalformedURLException e) {
                validURL = false;
            }
        }
        return validURL;
    }

    /**
     * Allows public key pinning.
     * Supply list of SSL certificates (base64-encoded strings between "-----BEGIN CERTIFICATE-----" and "-----END CERTIFICATE-----" without end-of-line)
     * along with server URL starting with "https://". Logging will only accept connections to the server
     * if public key of SSL certificate provided by the server matches one provided to this method or by {@link #enableCertificatePinning(List)}.
     * @param certificates List of SSL public keys
     * @return Logging instance
     */
    private static Logging enablePublicKeyPinning(List<String> certificates) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.i(Logging.TAG, "Enabling public key pinning");
        }
        publicKeyPinCertificates = certificates;
        return Logging.sharedInstance();
    }

    /**
     * Allows certificate pinning.
     * Supply list of SSL certificates (base64-encoded strings between "-----BEGIN CERTIFICATE-----" and "-----END CERTIFICATE-----" without end-of-line)
     * along with server URL starting with "https://". Logging will only accept connections to the server
     * if certificate provided by the server matches one provided to this method or by {@link #enablePublicKeyPinning(List)}.
     * @param certificates List of SSL certificates
     * @return Logging instance
     */
    private static Logging enableCertificatePinning(List<String> certificates) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.i(Logging.TAG, "Enabling certificate pinning");
        }
        certificatePinCertificates = certificates;
        return Logging.sharedInstance();
    }

    /**
     * Shows the star rating dialog
     * @param activity the activity that will own the dialog
     * @param callback callback for the star rating dialog "rate" and "dismiss" events
     */
    private void showStarRating(Activity activity, LoggingStarRating.RatingCallback callback){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Showing star rating");
        }

        if(!getConsent(LoggingFeatureNames.starRating)) {
            return;
        }

        LoggingStarRating.showStarRating(activity, callback);
    }

    /**
     * Set's the text's for the different fields in the star rating dialog. Set value null if for some field you want to keep the old value
     * @param starRatingTextTitle dialog's title text
     * @param starRatingTextMessage dialog's message text
     * @param starRatingTextDismiss dialog's dismiss buttons text
     */
    public synchronized Logging setStarRatingDialogTexts(String starRatingTextTitle, String starRatingTextMessage, String starRatingTextDismiss) {
        if(context_ == null) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Can't call this function before init has been called");
                return this;
            }
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting star rating texts");
        }

        LoggingStarRating.setStarRatingInitConfig(context_, -1, starRatingTextTitle, starRatingTextMessage, starRatingTextDismiss);

        return this;
    }

    /**
     * Set if the star rating should be shown automatically
     * @param IsShownAutomatically set it true if you want to show the app star rating dialog automatically for each new version after the specified session amount
     */
    public synchronized Logging setIfStarRatingShownAutomatically(boolean IsShownAutomatically) {
        if(context_ == null) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Can't call this function before init has been called");
                return this;
            }
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting to show star rating automaticaly: [" + IsShownAutomatically + "]");
        }

        LoggingStarRating.setShowDialogAutomatically(context_, IsShownAutomatically);

        return this;
    }

    /**
     * Set if the star rating is shown only once per app lifetime
     * @param disableAsking set true if you want to disable asking the app rating for each new app version (show it only once per apps lifetime)
     */
    public synchronized Logging setStarRatingDisableAskingForEachAppVersion(boolean disableAsking) {
        if(context_ == null) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Can't call this function before init has been called");
                return this;
            }
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting to disable showing of star rating for each app version:[" + disableAsking + "]");
        }

        LoggingStarRating.setStarRatingDisableAskingForEachAppVersion(context_, disableAsking);

        return this;
    }

    /**
     * Set after how many sessions the automatic star rating will be shown for each app version
     * @param limit app session amount for the limit
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging setAutomaticStarRatingSessionLimit(int limit) {
        if(context_ == null) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Can't call this function before init has been called");
                return this;
            }
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting automatic star rating session limit: [" + limit + "]");
        }
        LoggingStarRating.setStarRatingInitConfig(context_, limit, null, null, null);

        return this;
    }

    /**
     * Returns the session limit set for automatic star rating
     */
    public int getAutomaticStarRatingSessionLimit(){
        if(context_ == null) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Can't call this function before init has been called");
                return -1;
            }
        }

        int sessionLimit = LoggingStarRating.getAutomaticStarRatingSessionLimit(context_);

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Getting automatic star rating session limit: [" + sessionLimit + "]");
        }

        return sessionLimit;
    }

    /**
     * Returns how many sessions has star rating counted internally for the current apps version
     */
    public int getStarRatingsCurrentVersionsSessionCount(){
        if(context_ == null) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Can't call this function before init has been called");
                return -1;
            }
        }

        int sessionCount = LoggingStarRating.getCurrentVersionsSessionCount(context_);

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Getting star rating current version session count: [" + sessionCount + "]");
        }

        return sessionCount;
    }

    /**
     * Set the automatic star rating session count back to 0
     */
    public void clearAutomaticStarRatingSessionCount(){
        if(context_ == null) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Can't call this function before init has been called");
                return;
            }
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Clearing star rating session count");
        }

        LoggingStarRating.clearAutomaticStarRatingSessionCount(context_);
    }

    /**
     * Set if the star rating dialog is cancellable
     * @param isCancellable set this true if it should be cancellable
     */
    public synchronized Logging setIfStarRatingDialogIsCancellable(boolean isCancellable){
        if(context_ == null) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "Can't call this function before init has been called");
                return this;
            }
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting if star rating is cancellable: [" + isCancellable + "]");
        }

        LoggingStarRating.setIfRatingDialogIsCancellable(context_, isCancellable);

        return this;
    }

    /**
     * Set the override for forcing to use HTTP POST for all connections to the server
     * @param isItForced the flag for the new status, set "true" if you want it to be forced
     */
    public synchronized Logging setHttpPostForced(boolean isItForced) {

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting if HTTP POST is forced: [" + isItForced + "]");
        }

        isHttpPostForced = isItForced;
        return this;
    }

    /**
     * Get the status of the override for HTTP POST
     * @return return "true" if HTTP POST ir forced
     */
    boolean isHttpPostForced() {
        return isHttpPostForced;
    }

    private void checkIfDeviceIsAppCrawler(){
        String deviceName = DeviceInfo.getDevice();

        for(int a = 0 ; a < appCrawlerNames.size() ; a++) {
            if(deviceName.equals(appCrawlerNames.get(a))){
                deviceIsAppCrawler = true;
                return;
            }
        }
    }

    /**
     * Set if Logging SDK should ignore app crawlers
     * @param shouldIgnore if crawlers should be ignored
     */
    public synchronized Logging setShouldIgnoreCrawlers(boolean shouldIgnore){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting if should ignore app crawlers: [" + shouldIgnore + "]");
        }
        shouldIgnoreCrawlers = shouldIgnore;
        return this;
    }

    /**
     * Add app crawler device name to the list of names that should be ignored
     * @param crawlerName the name to be ignored
     */
    public void addAppCrawlerName(String crawlerName) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Adding app crawler name: [" + crawlerName + "]");
        }
        if(crawlerName != null && !crawlerName.isEmpty()) {
            appCrawlerNames.add(crawlerName);
        }
    }

    /**
     * Return if current device is detected as a app crawler
     * @return returns if devices is detected as a app crawler
     */
    boolean isDeviceAppCrawler() {
        return deviceIsAppCrawler;
    }

    /**
     * Return if the logging sdk should ignore app crawlers
     */
    boolean ifShouldIgnoreCrawlers(){
        return shouldIgnoreCrawlers;
    }

    /**
     * Returns the device id used by logging for this device
     * @return device ID
     */
    public synchronized String getDeviceID() {
        if(!isInitialized()) {
            throw new IllegalStateException("init must be called before getDeviceID");
        }
        return connectionQueue_.getDeviceId().getId();
    }

    /**
     * Returns the type of the device ID used by logging for this device.
     * @return device ID type
     */
    public synchronized DeviceId.Type getDeviceIDType(){
        if(!isInitialized()) {
            throw new IllegalStateException("init must be called before getDeviceID");
        }

        return connectionQueue_.getDeviceId().getType();
    }

    public synchronized Logging setPushIntentAddMetadata(boolean shouldAddMetadata) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting if adding metadata to push intents: [" + shouldAddMetadata + "]");
        }
        addMetadataToPushIntents = shouldAddMetadata;
        return this;
    }

    /**
     * Set if automatic activity tracking should use short names
     * @param shouldUseShortName set true if you want short names
     */
    synchronized Logging setAutoTrackingUseShortName(boolean shouldUseShortName) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting if automatic view tracking should use short names: [" + shouldUseShortName + "]");
        }
        automaticTrackingShouldUseShortName = shouldUseShortName;
        return this;
    }

    /**
     * Set if attribution should be enabled
     * @param shouldEnableAttribution set true if you want to enable it, set false if you want to disable it
     */
    public synchronized Logging setEnableAttribution(boolean shouldEnableAttribution) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting if attribution should be enabled");
        }
        isAttributionEnabled = shouldEnableAttribution;
        return this;
    }

    public synchronized Logging setRequiresConsent(boolean shouldRequireConsent){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting if consent should be required, [" + shouldRequireConsent + "]");
        }
        requiresConsent = shouldRequireConsent;
        return this;
    }

    /**
     * Initiate all things related to consent
     */
    private void initConsent(){
        //groupedFeatures.put("activity", new String[]{LoggingFeatureNames.sessions, LoggingFeatureNames.events, LoggingFeatureNames.views});
        //groupedFeatures.put("interaction", new String[]{LoggingFeatureNames.sessions, LoggingFeatureNames.events, LoggingFeatureNames.views});
    }

    /**
     * Special things needed to be done during setting push consent
     * @param consentValue The value of push consent
     */
    private void doPushConsentSpecialAction(boolean consentValue){
        if(isLoggingEnabled()) {
            Log.d(TAG, "Doing push consent special action: [" + consentValue + "]");
        }
        connectionQueue_.getLoggingStore().setConsentPush(consentValue);
    }

    /**
     * Actions needed to be done for the consent related location erasure
     */
    private void doLocationConsentSpecialErasure(){
        resetLocationValues();
        connectionQueue_.sendLocation();
    }

    /**
     * Check if the given name is a valid feature name
     * @param name the name of the feature to be tested if it is valid
     * @return returns true if value is contained in feature name array
     */
    private boolean isValidFeatureName(String name){
        for(String fName:validFeatureNames){
            if(fName.equals(name)){
                return true;
            }
        }
        return false;
    }

    /**
     * Prepare features into json format
     * @param features the names of features that are about to be changed
     * @param consentValue the value for the new consent
     * @return provided consent changes in json format
     */
    private String formatConsentChanges(String [] features, boolean consentValue){
        StringBuilder preparedConsent = new StringBuilder();
        preparedConsent.append("{");

        for(int a = 0 ; a < features.length ; a++){
            if(a != 0){
                preparedConsent.append(",");
            }
            preparedConsent.append('"');
            preparedConsent.append(features[a]);
            preparedConsent.append('"');
            preparedConsent.append(':');
            preparedConsent.append(consentValue);
        }

        preparedConsent.append("}");

        return preparedConsent.toString();
    }

    /**
     * Group multiple features into a feature group
     * @param groupName name of the consent group
     * @param features array of feature to be added to the consent group
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging createFeatureGroup(String groupName, String[] features){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Creating a feature group with the name: [" + groupName + "]");
        }

        groupedFeatures.put(groupName, features);
        return this;
    }

    /**
     * Set the consent of a feature group
     * @param groupName name of the consent group
     * @param isConsentGiven the value that should be set for this consent group
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging setConsentFeatureGroup(String groupName, boolean isConsentGiven){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting consent for feature group named: [" + groupName + "] with value: [" + isConsentGiven + "]");
        }

        if(!groupedFeatures.containsKey(groupName)){
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Trying to set consent for a unknown feature group: [" + groupName + "]");
            }

            return this;
        }

        setConsent(groupedFeatures.get(groupName), isConsentGiven);

        return this;
    }

    /**
     * Set the consent of a feature
     * @param featureNames feature names for which consent should be changed
     * @param isConsentGiven the consent value that should be set
     * @return Returns link to Logging for call chaining
     */
    private synchronized Logging setConsent(String[] featureNames, boolean isConsentGiven){
        final boolean isInit = isInitialized();//is the SDK initialized

        boolean previousSessionsConsent = false;
        if(featureConsentValues.containsKey(LoggingFeatureNames.sessions)){
            previousSessionsConsent = featureConsentValues.get(LoggingFeatureNames.sessions);
        }

        boolean previousLocationConsent = false;
        if(featureConsentValues.containsKey(LoggingFeatureNames.location)){
            previousLocationConsent = featureConsentValues.get(LoggingFeatureNames.location);
        }

        boolean currentSessionConsent = previousSessionsConsent;

        for(String featureName:featureNames) {
            if (Logging.sharedInstance() != null && Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Setting consent for feature named: [" + featureName + "] with value: [" + isConsentGiven + "]");
            }

            if (!isValidFeatureName(featureName)) {
                Log.d(Logging.TAG, "Given feature: [" + featureName + "] is not a valid name, ignoring it");
                continue;
            }


            featureConsentValues.put(featureName, isConsentGiven);

            //special actions for each feature
            switch (featureName){
                case LoggingFeatureNames.push:
                    if(isInit) {
                        //if the SDK is already initialized, do the special action now
                        doPushConsentSpecialAction(isConsentGiven);
                    } else {
                        //do the special action later
                        delayedPushConsent = isConsentGiven;
                    }
                    break;
                case LoggingFeatureNames.sessions:
                    currentSessionConsent = isConsentGiven;
                    break;
                case LoggingFeatureNames.location:
                    if(previousLocationConsent && !isConsentGiven){
                        //if consent is about to be removed
                        if(isInit){
                            doLocationConsentSpecialErasure();
                        } else {
                            delayedLocationErasure = true;
                        }
                    }
                    break;
            }
        }

        String formattedChanges = formatConsentChanges(featureNames, isConsentGiven);

        if(isInit && (collectedConsentChanges.size() == 0)){
            //if logging is initialized and collected changes are already sent, send consent now
            connectionQueue_.sendConsentChanges(formattedChanges);

            context_.sendBroadcast(new Intent(CONSENT_BROADCAST));

            //if consent has changed and it was set to true
            if((previousSessionsConsent != currentSessionConsent) && currentSessionConsent){
                //if consent was given, we need to begin the session
                if(isBeginSessionSent){
                    //if the first timing for a beginSession call was missed, send it again
                    onStartHelper();
                }
            }
        } else {
            // if logging is not initialized, collect and send it after it is

            collectedConsentChanges.add(formattedChanges);
        }

        return this;
    }

    /**
     * Give the consent to a feature
     * @param featureNames the names of features for which consent should be given
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging giveConsent(String[] featureNames){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Giving consent for features named: [" + featureNames.toString() + "]");
        }
        setConsent(featureNames, true);

        return this;
    }

    /**
     * Remove the consent of a feature
     * @param featureNames the names of features for which consent should be removed
     * @return Returns link to Logging for call chaining
     */
    public synchronized Logging removeConsent(String[] featureNames){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Removing consent for features named: [" + featureNames.toString() + "]");
        }

        setConsent(featureNames, false);

        return this;
    }

    /**
     * Get the current consent state of a feature
     * @param featureName the name of a feature for which consent should be checked
     * @return the consent value
     */
    synchronized boolean getConsent(String featureName){
        if(!requiresConsent){
            //return true silently
            return true;
        }

        Boolean returnValue = featureConsentValues.get(featureName);

        if(returnValue == null) {
            if(featureName.equals(LoggingFeatureNames.push)){
                //if the feature is 'push", set it with the value from preferences

                boolean storedConsent = connectionQueue_.getLoggingStore().getConsentPush();

                if (Logging.sharedInstance().isLoggingEnabled()) {
                    Log.d(Logging.TAG, "Push consent has not been set this session. Setting the value found stored in preferences:[" + storedConsent + "]");
                }

                featureConsentValues.put(featureName, storedConsent);

                returnValue = storedConsent;
            } else {
                returnValue = false;
            }
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Returning consent for feature named: [" + featureName + "] [" + returnValue + "]");
        }

        return returnValue;
    }

    /**
     * Print the consent values of all features
     * @return Returns link to Logging for call chaining
     */
    private synchronized Logging checkAllConsent(){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Checking and printing consent for All features");
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Is consent required? [" + requiresConsent + "]");
        }

        //make sure push consent has been added to the feature map
        getConsent(LoggingFeatureNames.push);

        StringBuilder sb = new StringBuilder();

        for(String key:featureConsentValues.keySet()) {
            sb.append("Feature named [").append(key).append("], consent value: [").append(featureConsentValues.get(key)).append("]\n");
        }

        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, sb.toString());
        }

        return this;
    }

    /**
     * Returns true if any consent has been given
     * @return true - any consent has been given, false - no consent has been given
     */
    boolean anyConsentGiven(){
        if (!requiresConsent){
            //no consent required - all consent given
            return true;
        }

        for(String key:featureConsentValues.keySet()) {
            if(featureConsentValues.get(key)){
                return true;
            }
        }
        return false;
    }

    /**
     * Show the rating dialog to the user
     * @param widgetId ID that identifies this dialog
     * @return
     */
    public synchronized Logging showFeedbackPopup(final String widgetId, final String closeButtonText, final Activity activity, final LoggingStarRating.FeedbackRatingCallback callback){
        if (!isInitialized()) {
            throw new IllegalStateException("Logging.sharedInstance().init must be called before showFeedbackPopup");
        }

        LoggingStarRating.showFeedbackPopup(widgetId, closeButtonText, activity, this, connectionQueue_, callback);

        return this;
    }

    /**
     * If enable, will automatically download newest remote config values on init.
     * @param enabled set true for enabling it
     * @param callback callback called after the update was done
     * @return
     */
    public synchronized Logging setRemoteConfigAutomaticDownload(boolean enabled, RemoteConfig.RemoteConfigCallback callback){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Setting if remote config Automatic download will be enabled, " + enabled);
        }

        remoteConfigAutomaticUpdateEnabled = enabled;
        remoteConfigInitCallback = callback;
        return this;
    }

    /**
     * Manually update remote config values
     * @param callback
     */
    public void remoteConfigUpdate(RemoteConfig.RemoteConfigCallback callback){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Manually calling to updateRemoteConfig");
        }
        if (!isInitialized()) {
            throw new IllegalStateException("Logging.sharedInstance().init must be called before remoteConfigUpdate");
        }
        if(!anyConsentGiven()){ return; }
        RemoteConfig.updateRemoteConfigValues(context_, null, null, connectionQueue_, false, callback);
    }

    /**
     * Manual remote config update call. Will only update the keys provided.
     * @param keysToInclude
     * @param callback
     */
    public void updateRemoteConfigForKeysOnly(String[] keysToInclude, RemoteConfig.RemoteConfigCallback callback){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Manually calling to updateRemoteConfig with include keys");
        }
        if (!isInitialized()) {
            throw new IllegalStateException("Logging.sharedInstance().init must be called before updateRemoteConfigForKeysOnly");
        }
        if(!anyConsentGiven()){
            if(callback != null){ callback.callback("No consent given"); }
            return;
        }
        if (keysToInclude == null && Logging.sharedInstance().isLoggingEnabled()) { Log.w(Logging.TAG,"updateRemoteConfigExceptKeys passed 'keys to include' array is null"); }
        RemoteConfig.updateRemoteConfigValues(context_, keysToInclude, null, connectionQueue_, false, callback);
    }

    /**
     * Manual remote config update call. Will update all keys except the ones provided
     * @param keysToExclude
     * @param callback
     */
    public void updateRemoteConfigExceptKeys(String[] keysToExclude, RemoteConfig.RemoteConfigCallback callback) {
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Manually calling to updateRemoteConfig with exclude keys");
        }
        if (!isInitialized()) {
            throw new IllegalStateException("Logging.sharedInstance().init must be called before updateRemoteConfigExceptKeys");
        }
        if(!anyConsentGiven()){
            if(callback != null){ callback.callback("No consent given"); }
            return;
        }
        if (keysToExclude == null && Logging.sharedInstance().isLoggingEnabled()) { Log.w(Logging.TAG,"updateRemoteConfigExceptKeys passed 'keys to ignore' array is null"); }
        RemoteConfig.updateRemoteConfigValues(context_, null, keysToExclude, connectionQueue_, false, callback);
    }

    /**
     * Get the stored value for the provided remote config key
     * @param key
     * @return
     */
    public Object getRemoteConfigValueForKey(String key){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Calling remoteConfigValueForKey");
        }
        if (!isInitialized()) {
            throw new IllegalStateException("Logging.sharedInstance().init must be called before remoteConfigValueForKey");
        }
        if(!anyConsentGiven()) { return null; }

        return RemoteConfig.getValue(key, context_);
    }

    /**
     * Clear all stored remote config values
     */
    private void remoteConfigClearValues(){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Calling remoteConfigClearValues");
        }
        if (!isInitialized()) {
            throw new IllegalStateException("Logging.sharedInstance().init must be called before remoteConfigClearValues");
        }

        RemoteConfig.clearValueStore(context_);
    }

    /**
     * Allows you to add custom header key/value pairs to each request
     */
    public void addCustomNetworkRequestHeaders(Map<String, String> headerValues){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Calling addCustomNetworkRequestHeaders");
        }
        requestHeaderCustomValues = headerValues;
        if(connectionQueue_ != null){
            connectionQueue_.setRequestHeaderCustomValues(requestHeaderCustomValues);
        }
    }

    // for unit testing
    ConnectionQueue getConnectionQueue() { return connectionQueue_; }
    void setConnectionQueue(final ConnectionQueue connectionQueue) { connectionQueue_ = connectionQueue; }
    ExecutorService getTimerService() { return timerService_; }
    EventQueue getEventQueue() { return eventQueue_; }
    void setEventQueue(final EventQueue eventQueue) { eventQueue_ = eventQueue; }
    long getPrevSessionDurationStartTime() { return prevSessionDurationStartTime_; }
    void setPrevSessionDurationStartTime(final long prevSessionDurationStartTime) { prevSessionDurationStartTime_ = prevSessionDurationStartTime; }
    int getActivityCount() { return activityCount_; }
    synchronized boolean getDisableUpdateSessionRequests() { return disableUpdateSessionRequests_; }

    @SuppressWarnings("InfiniteRecursion")
    private void stackOverflow() {
        this.stackOverflow();
    }

    @SuppressWarnings("ConstantConditions")
    public synchronized Logging crashTest(int crashNumber) {

        if (crashNumber == 1){
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Running crashTest 1");
            }

            stackOverflow();

        }else if (crashNumber == 2){

            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Running crashTest 2");
            }

            //noinspection UnusedAssignment,divzero
            @SuppressWarnings("NumericOverflow") int test = 10/0;

        }else if (crashNumber == 3){

            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Running crashTest 3");
            }

            Object[] o = null;
            //noinspection InfiniteLoopStatement
            while (true) { o = new Object[] { o }; }


        }else if (crashNumber == 4){

            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Running crashTest 4");
            }

            throw new RuntimeException("This is a crash");
        }
        else{
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Running crashTest 5");
            }

            String test = null;
            //noinspection ResultOfMethodCallIgnored
            test.charAt(1);
        }
        return Logging.sharedInstance();
    }

    static void init(final int evenQueueSize, final long timeDelay){
        Logging.eventQueueSize = evenQueueSize;
        Logging.timeDelay = timeDelay;
    }

    @Override
    public void logging(String key) {
        sharedInstance().recordEvent(key);
    }

    @Override
    public void logging(String key, int count){
        sharedInstance().recordEvent(key,count);
    }

    @Override
    public void logging(String key, int count, double sum) {
        sharedInstance().recordEvent(key,count,sum);
    }

    @Override
    public void logging(String key, HashMap<String, String> segment) {
        sharedInstance().recordEvent(key,segment,1);
    }

    @Override
    public void logging(String key, HashMap<String, String> segment, int count) {
        sharedInstance().recordEvent(key,segment,count);
    }

    @Override
    public void logging(String key, HashMap<String, String> segment, int count, double sum) {
        sharedInstance().recordEvent(key,segment,count,sum);
    }

    @Override
    public void logging(String key, HashMap<String, String> segment, int count, double sum, double duration) {
        sharedInstance().recordEvent(key,segment,count,sum,duration);
    }
}
