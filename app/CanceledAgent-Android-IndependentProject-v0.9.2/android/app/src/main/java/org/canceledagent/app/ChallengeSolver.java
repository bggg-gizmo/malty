package org.canceledagent.app;

import android.app.Activity;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs the recovered deterministic solver as standalone JS in a sandboxed
 * Android JavaScript engine. Perchance is not loaded or contacted.
 * Unsure means NO automatic verify; caller must request human intervention.
 */
public final class ChallengeSolver {
    public interface Callback { void complete(JSONObject answer); void failed(String reason); }
    private final Activity activity;
    private WebView engine;
    public ChallengeSolver(Activity activity){this.activity=activity;}
    public void solve(String challenge,Callback callback){
        try{
            if(engine!=null){engine.destroy();engine=null;}
            try(InputStream in=activity.getAssets().open("solver-engine.js");ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] block=new byte[8192];int n;
                while((n=in.read(block))!=-1)output.write(block,0,n);
                String js=output.toString(StandardCharsets.UTF_8.name());
                engine=new WebView(activity);
                WebSettings s=engine.getSettings();s.setJavaScriptEnabled(true);s.setAllowContentAccess(false);
                s.setAllowFileAccess(false);s.setBlockNetworkLoads(true);s.setDomStorageEnabled(false);
                engine.setWebViewClient(new WebViewClient(){
                    @Override public void onPageFinished(WebView view,String url){
                        view.evaluateJavascript("window.solveChallenge("+JSONObject.quote(challenge)+")",encoded->{
                            try{
                                String decoded=new JSONArray("["+encoded+"]").getString(0);
                                JSONObject result=new JSONObject(decoded);
                                callback.complete(result);
                            }catch(Exception e){callback.failed("Could not interpret solver result: "+e.getMessage());}
                        });
                    }
                });
                engine.loadDataWithBaseURL("https://appassets.androidplatform.net/solver/","<!doctype html><html><head><meta charset='utf-8'></head><body><script>"+js+"</script></body></html>","text/html","UTF-8",null);
            }
        }catch(Exception e){callback.failed("Solver initialization failed: "+e.getMessage());}
    }
    public void dispose(){if(engine!=null){engine.destroy();engine=null;}}
}
