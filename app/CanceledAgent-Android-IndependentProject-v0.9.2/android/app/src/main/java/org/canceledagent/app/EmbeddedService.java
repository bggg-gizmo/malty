package org.canceledagent.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.content.Context;

/** Foreground runtime: stays active when UI is in background, until explicitly stopped. */
public final class EmbeddedService extends Service {
    private EmbeddedServer runtime;private OutboundBridge bridge;private java.util.concurrent.ScheduledExecutorService scheduler;
    private static final String CHANNEL="canceled-independent-runtime";
    public static void launch(Context context){Intent i=new Intent(context,EmbeddedService.class);i.setAction("run");if(Build.VERSION.SDK_INT>=26)context.startForegroundService(i);else context.startService(i);}
    public static void restart(Context context){Intent i=new Intent(context,EmbeddedService.class);i.setAction("restart");if(Build.VERSION.SDK_INT>=26)context.startForegroundService(i);else context.startService(i);}
    public static void shutdown(Context context){Intent i=new Intent(context,EmbeddedService.class);i.setAction("stop");if(Build.VERSION.SDK_INT>=26)context.startForegroundService(i);else context.startService(i);}
    @Override public void onCreate(){super.onCreate();runtime=new EmbeddedServer(this);bridge=new OutboundBridge(this,runtime);scheduler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();scheduler.scheduleWithFixedDelay(()->{if(runtime!=null)runtime.scheduledTick();},60,60,java.util.concurrent.TimeUnit.SECONDS);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"run":intent.getAction();if("stop".equals(action)){if(bridge!=null)bridge.stop();if(runtime!=null)runtime.stop();stopForeground(true);stopSelf();return START_NOT_STICKY;}
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Canceled Agent embedded services",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,11,open,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Notification note=nb.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Canceled Agent services").setContentText("On-device rooms, Mesh, Bridge and workflows · port 8000").setOngoing(true).setContentIntent(pi).build();
        startForeground(2006,note);
        if(runtime!=null&&(!runtime.isRunning()||"restart".equals(action))){try{boolean lan=new SecurePrefs(this).get("lanHosting","false").equals("true");runtime.start(lan);}catch(Exception ex){android.util.Log.e("CanceledAgent","Local server failed",ex);}}
        if(bridge!=null){if(new SecurePrefs(this).get("bridge-outbound","false").equals("true"))bridge.start();else bridge.stop();}
        return START_STICKY;
    }
    @Override public void onDestroy(){if(bridge!=null)bridge.stop();if(runtime!=null)runtime.stop();if(scheduler!=null)scheduler.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
