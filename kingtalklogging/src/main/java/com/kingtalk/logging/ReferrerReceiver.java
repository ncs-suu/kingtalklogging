package com.kingtalk.logging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.net.URLDecoder;
import java.util.Objects;

/**
 * ADB Testing
 * adb shell
 * am broadcast -a com.android.vending.INSTALL_REFERRER --es "referrer" "logging_cid%3Dcb14e5f33b528334715f1809e4572842c74686df%26logging_cuid%3Decf125107e4e27e6bcaacb3ae10ddba66459e6ae"
**/
//******************************************************************************
class ReferrerReceiver extends BroadcastReceiver
{
    private static final String key = "referrer";
    //--------------------------------------------------------------------------
    public static String getReferrer(Context context)
    {
        // Return any persisted referrer value or null if we don't have a referrer.
        return context.getSharedPreferences(key, Context.MODE_PRIVATE).getString(key, null);
    }

    public static void deleteReferrer(Context context)
    {
        // delete stored referrer.
        context.getSharedPreferences(key, Context.MODE_PRIVATE).edit().remove(key).apply();
    }

    //--------------------------------------------------------------------------
    public ReferrerReceiver(){
    }

    //--------------------------------------------------------------------------
    @Override public void onReceive(Context context, Intent intent)
    {
        try
        {
            // Make sure this is the intent we expect - it always should be.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if ((null != intent) && (Objects.equals(intent.getAction(), "com.android.vending.INSTALL_REFERRER")))
                {
                    // This intent should have a referrer string attached to it.
                    String rawReferrer = intent.getStringExtra(key);
                    if (null != rawReferrer)
                    {
                        // The string is usually URL Encoded, so we need to decode it.
                        String referrer = URLDecoder.decode(rawReferrer, "UTF-8");

                        // Log the referrer string.
                        Log.d(Logging.TAG, "Referrer: " + referrer);

                        String[] parts = referrer.split("&");
                        String cid = null;
                        String uid = null;
                        for (String part : parts) {
                            if (part.startsWith("logging_cid")) {
                                cid = part.replace("logging_cid=", "").trim();
                            }
                            if (part.startsWith("logging_cuid")) {
                                uid = part.replace("logging_cuid=", "").trim();
                            }
                        }
                        String res = "";
                        if(cid != null)
                            res += "&campaign_id="+cid;
                        if(uid != null)
                            res += "&campaign_user="+uid;

                        Log.d(Logging.TAG, "Processed: " + res);
                        // Persist the referrer string.
                        if(!res.equals(""))
                            context.getSharedPreferences(key, Context.MODE_PRIVATE).edit().putString(key, res).apply();
                    }
                }
            }
        }
        catch (Exception e)
        {
            Log.d(Logging.TAG, e.toString());
        }
    }
}