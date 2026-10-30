package org.canceledagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The recovered Canvas Editor 1.2.0 runs from packaged, same-origin local assets only.
 * Native project revisions remain the authoritative persistence layer.
 */
public final class CanvasEditorActivity extends Activity {
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private LocalProjects projects;private WebView browser;private TextView state;
    private String id,name,source;private int revision;private boolean loaded=false;
    @SuppressLint("SetJavaScriptEnabled") @Override public void onCreate(Bundle b){super.onCreate(b);
        projects=new LocalProjects(this);id=getIntent().getStringExtra("projectId");
        JSONObject project=id==null?null:projects.project(id);
        if(project==null){id=projects.create("Canvas Editor project","<!doctype html><html><body></body></html>");project=projects.project(id);}
        name=project.optString("name");source=project.optString("source");revision=project.optInt("revision");
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);setContentView(box);
        state=new TextView(this);state.setPadding(12,12,12,12);state.setText("Canvas Editor · "+name+" · revision "+revision);box.addView(state);
        Button nativeEditor=new Button(this);nativeEditor.setText("Open native editor / preview fallback");box.addView(nativeEditor);
        nativeEditor.setOnClickListener(v->{Intent i=new Intent(this,EditorActivity.class);i.putExtra("projectId",id);startActivity(i);});
        browser=new WebView(this);WebSettings config=browser.getSettings();config.setJavaScriptEnabled(true);
        config.setDomStorageEnabled(true);config.setAllowFileAccess(false);config.setAllowContentAccess(false);
        config.setAllowFileAccessFromFileURLs(false);config.setAllowUniversalAccessFromFileURLs(false);
        config.setJavaScriptCanOpenWindowsAutomatically(false);
        browser.addJavascriptInterface(new CanvasNative(),"AndroidCanvas");
        browser.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                String url=request.getUrl().toString(),path=request.getUrl().getEncodedPath();
                if(!"https".equals(request.getUrl().getScheme())||!"appassets.androidplatform.net".equals(request.getUrl().getHost())||!path.startsWith("/assets/canvas-editor/")||path.contains("..")||path.contains("%")){
                    return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
                }
                String file=path.substring("/assets/".length());
                try{InputStream data=getAssets().open(file);String mime=file.endsWith(".html")?"text/html":"text/javascript";
                    return new WebResourceResponse(mime,"UTF-8",data);
                }catch(Exception ex){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
        });
        box.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        browser.loadUrl(ORIGIN+"/assets/canvas-editor/studio.html");
    }
    private final class CanvasNative {
        @JavascriptInterface public void onReady(String ignored){runOnUiThread(()->{loaded=true;browser.evaluateJavascript("window.canceledEditor.setSource("+JSONObject.quote(source)+")",null);});}
        @JavascriptInterface public void onChanged(String current){source=current;}
        @JavascriptInterface public void onSave(String content){runOnUiThread(()->{try{revision=projects.save(id,name,content,"Recovered Canvas Editor save",revision);source=content;state.setText("Saved revision "+revision);}catch(Exception ex){state.setText("Save failure: "+ex.getMessage());}});}
        @JavascriptInterface public void onError(String error){runOnUiThread(()->state.setText("Canvas rendering unavailable: "+error+". Native editor remains available."));}
    }
    @Override public void onBackPressed(){
        JSONObject current=projects.project(id);
        if(current!=null&&!current.optString("source").equals(source))new AlertDialog.Builder(this).setTitle("Unsaved Canvas changes")
            .setMessage("Save changes before leaving?")
            .setPositiveButton("Save",(d,w)->{try{revision=projects.save(id,name,source,"Canvas close save",revision);super.onBackPressed();}catch(Exception e){state.setText("Save failed: "+e.getMessage());}})
            .setNegativeButton("Discard",(d,w)->super.onBackPressed()).setNeutralButton("Stay",null).show();
        else super.onBackPressed();
    }
    @Override protected void onDestroy(){if(browser!=null){browser.removeJavascriptInterface("AndroidCanvas");browser.destroy();}super.onDestroy();}
}
