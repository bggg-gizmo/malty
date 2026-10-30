package org.canceledagent.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Original recovered deterministic solver, executing in Android's built-in, network-blocked JS engine. */
public final class HeadlessSolver {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    public HeadlessSolver(Context ctx){context=ctx.getApplicationContext();}
    public JSONObject solve(String challenge)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Solver must execute from a worker thread");
        CountDownLatch done=new CountDownLatch(1);AtomicReference<JSONObject> result=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();
        main.post(()->{
            try{
                final String code;
                try(InputStream in=context.getAssets().open("solver-engine.js");ByteArrayOutputStream data=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))>=0)data.write(buf,0,n);code=data.toString("UTF-8");
                }
                WebView browser=new WebView(context);WebSettings s=browser.getSettings();s.setJavaScriptEnabled(true);s.setBlockNetworkLoads(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setDomStorageEnabled(false);
                browser.setWebViewClient(new WebViewClient(){
                    @Override public void onPageFinished(WebView view,String url){
                        browser.evaluateJavascript("window.solveChallenge("+JSONObject.quote(challenge)+")",value->{
                            try{String decoded=new JSONArray("["+value+"]").getString(0);result.set(new JSONObject(decoded));}catch(Exception e){error.set(e);}finally{browser.destroy();done.countDown();}
                        });
                    }
                    @Override public void onReceivedError(WebView view,android.webkit.WebResourceRequest req,android.webkit.WebResourceError err){
                        if(req.isForMainFrame()){error.set(new IOException("Solver engine failed loading"));browser.destroy();done.countDown();}
                    }
                });
                browser.loadDataWithBaseURL("https://appassets.androidplatform.net/solver/","<!doctype html><html><head><meta charset='utf-8'></head><body><script>"+code+"</script></body></html>","text/html","UTF-8",null);
            }catch(Exception e){error.set(e);done.countDown();}
        });
        if(!done.await(25,TimeUnit.SECONDS))throw new IOException("Solver timed out; no automatic verification attempted");
        if(error.get()!=null)throw error.get();if(result.get()==null)throw new IOException("Solver returned no result");return result.get();
    }
}
