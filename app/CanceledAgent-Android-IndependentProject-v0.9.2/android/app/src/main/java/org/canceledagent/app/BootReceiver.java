package org.canceledagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** User-opt-in restart of the on-device service after reboot or package replacement. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        if(!"true".equals(new SecurePrefs(context).get("bootHosting","false")))return;
        try{EmbeddedService.launch(context);}catch(Exception ex){Log.e("CanceledAgent","Boot service start refused by Android",ex);}
    }
}
