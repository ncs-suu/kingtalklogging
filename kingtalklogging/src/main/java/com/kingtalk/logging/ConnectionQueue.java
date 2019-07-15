package com.kingtalk.logging;

import android.content.Context;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import static com.kingtalk.logging.Logging.LoggingFeatureNames.attribution;
import static com.kingtalk.logging.Logging.LoggingFeatureNames.crashes;
import static com.kingtalk.logging.Logging.LoggingFeatureNames.location;
import static com.kingtalk.logging.Logging.LoggingFeatureNames.push;
import static com.kingtalk.logging.Logging.LoggingFeatureNames.sessions;
import static com.kingtalk.logging.Logging.LoggingFeatureNames.users;

class ConnectionQueue {
    private LoggingStore store_;
    private ExecutorService executor_;
    private String appKey_;
    private Context context_;
    private String serverURL_;
    private Future<?> connectionProcessorFuture_;
    private DeviceId deviceId_;
    private SSLContext sslContext_;

    private Map<String, String> requestHeaderCustomValues;

    // Getters are for unit testing
    String getAppKey() {
        return appKey_;
    }

    void setAppKey(final String appKey) {
        appKey_ = appKey;
    }

    Context getContext() {
        return context_;
    }

    void setContext(final Context context) {
        context_ = context;
    }

    String getServerURL() {
        return serverURL_;
    }

    void setServerURL(final String serverURL) {
        serverURL_ = serverURL;

        if (Logging.publicKeyPinCertificates == null && Logging.certificatePinCertificates == null) {
            sslContext_ = null;
        } else try {
            TrustManager[] tm = {new CertificateTrustManager(Logging.publicKeyPinCertificates, Logging.certificatePinCertificates)};
            sslContext_ = SSLContext.getInstance("TLS");
            sslContext_.init(null, tm, null);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    LoggingStore getLoggingStore() {
        return store_;
    }

    void setLoggingStore(final LoggingStore loggingStore) {
        store_ = loggingStore;
    }

    DeviceId getDeviceId() { return deviceId_; }

    void setDeviceId(DeviceId deviceId) {
        this.deviceId_ = deviceId;
    }

    void setRequestHeaderCustomValues(Map<String, String> headerCustomValues){
        requestHeaderCustomValues = headerCustomValues;
    }

    /**
     * Checks internal state and throws IllegalStateException if state is invalid to begin use.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    private void checkInternalState() {
        if (context_ == null) {
            throw new IllegalStateException("context has not been set");
        }
        if (appKey_ == null || appKey_.length() == 0) {
            throw new IllegalStateException("app key has not been set");
        }
        if (store_ == null) {
            throw new IllegalStateException("Logging store has not been set");
        }
        if (serverURL_ == null || !Logging.isValidURL(serverURL_)) {
            throw new IllegalStateException("server URL is not valid");
        }
        if (Logging.publicKeyPinCertificates != null && !serverURL_.startsWith("https")) {
            throw new IllegalStateException("server must start with https once you specified public keys");
        }
    }

    /**
     * Records a session start event for the app and sends it to the server.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void beginSession() {
        checkInternalState();

        boolean dataAvailable = false;//will only send data if there is something valuable to send
        String data = prepareCommonRequestData();

        if(Logging.sharedInstance().getConsent(sessions)) {
            //add session data if consent given
            data += "&begin_session=1"
                    + "&metrics=" + DeviceInfo.getMetrics(context_);//can be only sent with begin session
            dataAvailable = true;
        }

        LoggingStore cs = getLoggingStore();
        String locationData = prepareLocationData(cs);

        if(!locationData.isEmpty()){
            data += locationData;
            dataAvailable = true;
        }

        if(Logging.sharedInstance().getConsent(attribution)) {
            //add attribution data if consent given
            if (Logging.sharedInstance().isAttributionEnabled) {
                String cachedAdId = store_.getCachedAdvertisingId();

                if (!cachedAdId.isEmpty()) {
                    data += "&aid=" + ConnectionProcessor.urlEncodeString("{\"adid\":\"" + cachedAdId + "\"}");

                    dataAvailable = true;
                }
            }
        }

        Logging.sharedInstance().isBeginSessionSent = true;

        if(dataAvailable) {
            store_.addConnection(data);
            tick();
        }
    }

    /**
     * Records a session duration event for the app and sends it to the server. This method does nothing
     * if passed a negative or zero duration.
     * @param duration duration in seconds to extend the current app session, should be more than zero
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void updateSession(final int duration) {
        checkInternalState();
        if (duration > 0) {
            boolean dataAvailable = false;//will only send data if there is something valuable to send
            String data = prepareCommonRequestData();

            if(Logging.sharedInstance().getConsent(sessions)) {
                data += "&session_duration=" + duration;
                dataAvailable = true;
            }

            if(Logging.sharedInstance().getConsent(attribution)) {
                if (Logging.sharedInstance().isAttributionEnabled) {
                    String cachedAdId = store_.getCachedAdvertisingId();

                    if (!cachedAdId.isEmpty()) {
                        data += "&aid=" + ConnectionProcessor.urlEncodeString("{\"adid\":\"" + cachedAdId + "\"}");
                        dataAvailable = true;
                    }
                }
            }

            if(dataAvailable) {
                store_.addConnection(data);
                tick();
            }
        }
    }

    void changeDeviceId(String deviceId, final int duration) {
        checkInternalState();

        if(!Logging.sharedInstance().anyConsentGiven()){
            //no consent set, aborting
            return;
        }

        String data = prepareCommonRequestData();

        if(Logging.sharedInstance().getConsent(sessions)) {
            data += "&session_duration=" + duration;
        }

        // !!!!! THIS SHOULD ALWAYS BE ADDED AS THE LAST FIELD, OTHERWISE MERGING BREAKS !!!!!
        data += "&device_id=" + ConnectionProcessor.urlEncodeString(deviceId);

        store_.addConnection(data);
        tick();
    }

    void tokenSession(String token, Logging.LoggingMessagingMode mode) {
        checkInternalState();

        if(!Logging.sharedInstance().getConsent(push)){
            return;
        }

        final String data = prepareCommonRequestData()
                + "&token_session=1"
                + "&android_token=" + token
                + "&test_mode=" + (mode == Logging.LoggingMessagingMode.TEST ? 2 : 0)
                + "&locale=" + DeviceInfo.getLocale();

        // To ensure begin_session will be fully processed by the server before token_session
        final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        worker.schedule(() -> {
            store_.addConnection(data);
            tick();
        }, 10, TimeUnit.SECONDS);
    }

    /**
     * Records a session end event for the app and sends it to the server. Duration is only included in
     * the session end event if it is more than zero.
     * @param duration duration in seconds to extend the current app session
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void endSession(final int duration) {
        endSession(duration, null);
    }

    void endSession(final int duration, String deviceIdOverride) {
        checkInternalState();

        boolean dataAvailable = false;//will only send data if there is something valuable to send
        String data = prepareCommonRequestData();

        if(Logging.sharedInstance().getConsent(sessions)) {
            data += "&end_session=1";
            if (duration > 0) {
                data += "&session_duration=" + duration;
            }
            dataAvailable = true;
        }

        if (deviceIdOverride != null && Logging.sharedInstance().anyConsentGiven()) {
            //if no consent is given, device ID override is not sent
            data += "&override_id=" + ConnectionProcessor.urlEncodeString(deviceIdOverride);
            dataAvailable = true;
        }

        if(dataAvailable) {
            store_.addConnection(data);
            tick();
        }
    }

    /**
     * Send user location
     */
    void sendLocation() {
        checkInternalState();

        String data = prepareCommonRequestData();

        LoggingStore cs = getLoggingStore();
        data += prepareLocationData(cs);

        store_.addConnection(data);

        tick();
    }

    /**
     * Send user data to the server.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendUserData() {
        checkInternalState();

        if(!Logging.sharedInstance().getConsent(users)){
            return;
        }

        String userdata = UserData.getDataForRequest();

        if(!userdata.equals("")){
            String data = prepareCommonRequestData()
                    + userdata;
            store_.addConnection(data);

            tick();
        }
    }

    /**
     * Attribute installation to Logging server.
     * @param referrer query parameters
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendReferrerData(String referrer) {
        checkInternalState();

        if(Logging.sharedInstance().getConsent(attribution)) {
            return;
        }

        if(referrer != null){
            String data = prepareCommonRequestData()
                    + referrer;
            store_.addConnection(data);

            tick();
        }
    }

    /**
     * Reports a crash with device data to the server.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendCrashReport(String error, boolean nonfatal, boolean isNativeCrash) {
        checkInternalState();

        if(!Logging.sharedInstance().getConsent(crashes)){
            return;
        }

        //limit the size of the crash report to 10k characters
        if(!isNativeCrash) {
            error = error.substring(0, Math.min(10000, error.length()));
        }

        final String data = prepareCommonRequestData()
                          + "&crash=" + ConnectionProcessor.urlEncodeString(CrashDetails.getCrashData(context_, error, nonfatal, isNativeCrash));

        store_.addConnection(data);

        tick();
    }

    /**
     * Records the specified events and sends them to the server.
     * @param events URL-encoded JSON string of event data
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void recordEvents(final String events) {
        checkInternalState();

        ////////////////////////////////////////////////////
        ///CONSENT FOR EVENTS IS CHECKED ON EVENT CREATION//
        ////////////////////////////////////////////////////

        final String data = prepareCommonRequestData()
                          + "&events=" + events;

        store_.addConnection(data);
        tick();
    }

    void sendConsentChanges(String formattedConsentChanges) {
        checkInternalState();

        final String data = prepareCommonRequestData()
                + "&consent=" + ConnectionProcessor.urlEncodeString(formattedConsentChanges);

        store_.addConnection(data);

        tick();
    }

    private String prepareCommonRequestData(){
        return "app_key=" + appKey_
                + "&timestamp=" + Logging.currentTimestampMs()
                + "&hour=" + Logging.currentHour()
                + "&dow=" + Logging.currentDayOfWeek()
                + "&tz=" + DeviceInfo.getTimezoneOffset()
                + "&sdk_version=" + Logging.KING_TALK_LOGGING_SDK_VERSION_STRING
                + "&sdk_name=" + Logging.LOGGING_SDK_NAME;
    }

    private String prepareLocationData(LoggingStore cs){
        String data = "";

        if((cs.getLocationDisabled() || !Logging.sharedInstance().getConsent(location))){
            //if location is disabled or consent not given, send empty location info
            //this way it is cleared server side and geoip is not used
            //do this only if allowed
            data += "&location=";
        } else {
            if(Logging.sharedInstance().getConsent(location)) {
                //location should be send, add all the fields we have
                String location = cs.getLocation();
                String city = cs.getLocationCity();
                String country_code = cs.getLocationCountryCode();
                String ip = cs.getLocationIpAddress();

                if(location != null && !location.isEmpty()){
                    data += "&location=" + ConnectionProcessor.urlEncodeString(location);
                }

                if(city != null && !city.isEmpty()){
                    data += "&city=" + city;
                }

                if(country_code != null && !country_code.isEmpty()){
                    data += "&country_code=" + country_code;
                }

                if(ip != null && !ip.isEmpty()){
                    data += "&ip=" + ip;
                }
            }
        }
        return data;
    }

    String prepareRemoteConfigRequest(String keysInclude, String keysExclude){
        String data = prepareCommonRequestData()
                + "&method=fetch_remote_config"
                + "&device_id=" + ConnectionProcessor.urlEncodeString(deviceId_.getId());

        if(Logging.sharedInstance().getConsent(sessions)) {
            //add session data if consent given
            data += "&metrics=" + DeviceInfo.getMetrics(context_);
        }

        LoggingStore cs = getLoggingStore();
        String locationData = prepareLocationData(cs);
        data += locationData;

        //add key filters
        if(keysInclude != null){
            data += "&keys=" +  ConnectionProcessor.urlEncodeString(keysInclude);
        } else if(keysExclude != null) {
            data += "&omit_keys=" + ConnectionProcessor.urlEncodeString(keysExclude);
        }

        return data;
    }

    /**
     * Ensures that an executor has been created for ConnectionProcessor instances to be submitted to.
     */
    private void ensureExecutor() {
        if (executor_ == null) {
            executor_ = Executors.newSingleThreadExecutor();
        }
    }

    /**
     * Starts ConnectionProcessor instances running in the background to
     * process the local connection queue data.
     * Does nothing if there is connection queue data or if a ConnectionProcessor
     * is already running.
     */
    void tick() {
        if (!store_.isEmptyConnections() && (connectionProcessorFuture_ == null || connectionProcessorFuture_.isDone())) {
            ensureExecutor();
            connectionProcessorFuture_ = executor_.submit(createConnectionProcessor());
        }
    }

    ConnectionProcessor createConnectionProcessor(){
        return new ConnectionProcessor(serverURL_, store_, deviceId_, sslContext_, requestHeaderCustomValues);
    }

    // for unit testing
//    ExecutorService getExecutor() { return executor_; }
//    void setExecutor(final ExecutorService executor) { executor_ = executor; }
//    Future<?> getConnectionProcessorFuture() { return connectionProcessorFuture_; }
//    void setConnectionProcessorFuture(final Future<?> connectionProcessorFuture) { connectionProcessorFuture_ = connectionProcessorFuture; }

}
