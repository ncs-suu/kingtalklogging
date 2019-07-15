package com.kingtalk.logging;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLConnection;
import java.util.Iterator;

class RemoteConfig {

    public interface RemoteConfigCallback {
        /**
         * Called after receiving remote config update result
         * @param error if is null, it means that no errors were encountered
         */
        void callback(String error);
    }

    /**
     * Internal call for updating remote config keys
     * @param keysOnly set if these are the only keys to update
     * @param keysExcept set if these keys should be ignored from the update
     * @param requestShouldBeDelayed this is set to true in case of update after a deviceId change
     * @param callback called after the update is done
     */
    static void updateRemoteConfigValues(final Context context, final String[] keysOnly, final String[] keysExcept, final ConnectionQueue connectionQueue_, final boolean requestShouldBeDelayed, final RemoteConfigCallback callback){
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "Updating remote config values, requestShouldBeDelayed:[" + requestShouldBeDelayed + "]");
        }
        String keysInclude = null;
        String keysExclude = null;

        if(keysOnly != null && keysOnly.length > 0){
            //include list takes precedence
            //if there is at least one item, use it
            JSONArray includeArray = new JSONArray();
            for (String key:keysOnly) {
                includeArray.put(key);
            }
            keysInclude = includeArray.toString();
        } else if(keysExcept != null && keysExcept.length > 0){
            //include list was not used, use the exclude list
            JSONArray excludeArray = new JSONArray();
            for(String key:keysExcept){
                excludeArray.put(key);
            }
            keysExclude = excludeArray.toString();
        }

        ConnectionProcessor cp = connectionQueue_.createConnectionProcessor();
        URLConnection urlConnection;
        String requestData = connectionQueue_.prepareRemoteConfigRequest(keysInclude, keysExclude);
        if (Logging.sharedInstance().isLoggingEnabled()) {
            Log.d(Logging.TAG, "RemoteConfig requestData:[" + requestData + "]");
        }

        try {
            urlConnection = cp.urlConnectionForServerRequest(requestData, "/o/sdk?");
        } catch (IOException e) {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.e(Logging.TAG, "IOException while preparing remote config update request :[" + e.toString() + "]");
            }

            if(callback != null){
                callback.callback("Encountered problem while trying to reach the server");
            }

            return;
        }

        (new LoggingStarRating.ImmediateRequestMaker()).execute(urlConnection, requestShouldBeDelayed, (LoggingStarRating.InternalFeedbackRatingCallback) checkResponse -> {
            if (Logging.sharedInstance().isLoggingEnabled()) {
                Log.d(Logging.TAG, "Processing remote config received response, receved response is null:[" + (checkResponse == null) + "]");
            }
            if(checkResponse == null) {
                if(callback != null){
                    callback.callback("Encountered problem while trying to reach the server, possibly no internet connection");
                }
                return;
            }

            //merge the new values into the current ones
            RemoteConfigValueStore rcvs = loadConfig(context);
            if(keysExcept == null && keysOnly == null){
                //in case of full updates, clear old values
                rcvs.values = new JSONObject();
            }
            rcvs.mergeValues(checkResponse);
            saveConfig(context, rcvs);

            if(callback != null){
                callback.callback(null);
            }
        });
    }

    static Object getValue(String key, Context context){
        RemoteConfigValueStore rcvs = loadConfig(context);
        return rcvs.getValue(key);
    }


    private static void saveConfig(Context context, RemoteConfigValueStore rcvs){
        LoggingStore cs = new LoggingStore(context);
        cs.setRemoteConfigValues(rcvs.dataToString());
    }

    private static RemoteConfigValueStore loadConfig(Context context){
        LoggingStore cs = new LoggingStore(context);
        String rcvsString = cs.getRemoteConfigValues();
        return RemoteConfigValueStore.dataFromString(rcvsString);
    }

    static void clearValueStore(Context context){
        LoggingStore cs = new LoggingStore(context);
        cs.setRemoteConfigValues("");
    }

    private static class RemoteConfigValueStore {
        JSONObject values;

        //add new values to the current storage
        void mergeValues(JSONObject newValues){
            if(newValues == null) {return;}

            Iterator<String> iter = newValues.keys();
            while (iter.hasNext()) {
                String key = iter.next();
                try {
                    Object value = newValues.get(key);
                    values.put(key, value);
                } catch (Exception e) {
                    if (Logging.sharedInstance().isLoggingEnabled()) {
                        Log.e(Logging.TAG, "Failed merging new remote config values");
                    }
                }
            }
        }

        private RemoteConfigValueStore(JSONObject values){
            this.values = values;
        }

        Object getValue(String key){
            return values.opt(key);
        }

        static RemoteConfigValueStore dataFromString(String storageString){
            if(storageString == null || storageString.isEmpty()){
                return new RemoteConfigValueStore(new JSONObject());
            }

            JSONObject values;
            try {
                values = new JSONObject(storageString);
            } catch (JSONException e) {
                if (Logging.sharedInstance().isLoggingEnabled()) {
                    Log.e(Logging.TAG, "Couldn't decode RemoteConfigValueStore successfully: " + e.toString());
                }
                values = new JSONObject();
            }
            return new RemoteConfigValueStore(values);
        }

        private String dataToString(){
            return values.toString();
        }
    }
}
