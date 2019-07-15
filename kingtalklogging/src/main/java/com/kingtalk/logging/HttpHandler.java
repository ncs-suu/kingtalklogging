package com.kingtalk.logging;

import android.app.Activity;
import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

class HttpHandler extends AsyncTask<String, String, ILogging>{

    private Activity activity;
    private int eventQueueSize;
    private long timeDelay;
    HttpHandler(Activity activity, int eventQueueSize, long timeDelay) {
        this.activity = activity;
        this.eventQueueSize = eventQueueSize;
        this.timeDelay = timeDelay;
    }

    @Override
    protected ILogging doInBackground(String... strings) {
        try {
            URL url = new URL(strings[0]);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            InputStream in = new BufferedInputStream(conn.getInputStream());
            String con = convertStreamToString(in);
            JSONObject object = getJSon(con);
            try {
                if (object == null){
                    Log.d("JSON GET", "doInBackground: Can't get json object");
                    return null;
                }
                object.getString("url");
                Logging.init(eventQueueSize,timeDelay);
                Logging.onCreate(activity);
                Logging.sharedInstance().init(activity.getApplicationContext(),"https://us-try.count.ly","28fc3e2e0c17e003edad1c3b643a21d3837c84a3");
//                Logging.sharedInstance().init(activity.getApplicationContext(),object.getString("url"),object.getString("app_key"));
                Logging.sharedInstance().onStart(activity);
                Logging.sharedInstance().setLoggingEnabled();
                Logging.sharedInstance().enableCrashReporting();
                return Logging.sharedInstance();
            } catch (JSONException e) {
                e.printStackTrace();
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        } catch (MalformedURLException e) {
            Log.d("CONNECTION","MalformedURLException: " + e.getMessage());
            return null;
        } catch (ProtocolException e) {
            Log.d("CONNECTION","ProtocolException: " + e.getMessage());
            return null;
        } catch (IOException e) {
            Log.d("CONNECTION","IOException: " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.d("CONNECTION","Exception: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
    }

    @Override
    protected void onPostExecute(ILogging iLogging) {
        super.onPostExecute(iLogging);
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);
    }

    private String convertStreamToString(InputStream is) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }
    private JSONObject getJSon(String text) {
        try {
            JSONObject result = new JSONObject(text);
            return result.getJSONObject("result").getJSONObject("config");
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }

    }
}