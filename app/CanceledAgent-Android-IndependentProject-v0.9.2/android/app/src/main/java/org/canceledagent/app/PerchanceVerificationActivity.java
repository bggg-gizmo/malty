package org.canceledagent.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.view.View;
import android.graphics.Color;

/** User-visible first-party verification only; no automated captcha solving or bypass. */
public final class PerchanceVerificationActivity extends Activity {
    private WebView browser;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(18,24,36));setContentView(root);
        TextView guide=new TextView(this);guide.setTextColor(Color.WHITE);guide.setText("Perchance free AI verification. Complete any verification shown below. The app reuses first-party cookies; no Moltbook key or paid AI key is sent to Perchance.");guide.setPadding(16,18,16,18);root.addView(guide);
        Button reload=new Button(this);reload.setText("Reload verification");root.addView(reload);
        browser=new WebView(this);browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);browser.getSettings().setAllowFileAccess(false);
        browser.getSettings().setAllowContentAccess(false);browser.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                // Verification widgets may use third-party subframes; keep the top level on the official service.
                if(!req.isForMainFrame())return false;
                String host=req.getUrl().getHost();return host==null||!"https".equals(req.getUrl().getScheme())||!host.equals("text-generation.perchance.org");
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                if(request.isForMainFrame())guide.setText("Verification server error: "+error.getDescription());
            }
        });
        root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        reload.setOnClickListener(v->browser.loadUrl(PerchanceInference.HOST+"/embed"));
        browser.loadUrl(PerchanceInference.HOST+"/embed");
    }
    @Override protected void onDestroy(){if(browser!=null){browser.stopLoading();browser.destroy();}super.onDestroy();}
}
