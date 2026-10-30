package org.canceledagent.app;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Exact recovered DonationLink2Ad banner renderer, using user's settings where configured. */
public final class DonationActivity extends Activity {
    private SecurePrefs prefs;private WebView web;private TextView info;
    private boolean compact=false;private String dark="auto";
    private static final String PP="https://www.paypal.com/qrcodes/p2pqrc/WGMNG3RKN97BL";
    private static final String CA="https://cash.app/$falcox904";
    private static final String SOL="4guatesjezaC5h2niYR6ZDmZ6pQagQAAvtnwBf7NMNpK";
    private static final String ETH="0xFBdB59298ea0B9D867897CCEDDb0De1e2B03909c";
    @Override public void onCreate(Bundle b){super.onCreate(b);prefs=new SecurePrefs(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);setContentView(root);
        info=new TextView(this);info.setPadding(15,12,15,12);info.setText("DonationLink2Ad · recovered original banner");root.addView(info);
        Button mode=new Button(this);mode.setText("Toggle compact layout");root.addView(mode);mode.setOnClickListener(v->{compact=!compact;render();});
        Button theme=new Button(this);theme.setText("Cycle auto / light / dark");root.addView(theme);theme.setOnClickListener(v->{dark=dark.equals("auto")?"light":dark.equals("light")?"dark":"auto";render();});
        web=new WebView(this);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);s.setJavaScriptCanOpenWindowsAutomatically(false);
        web.addJavascriptInterface(new NativeActions(),"SupportNative");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                Uri uri=req.getUrl();if("https".equals(uri.getScheme())){
                    startActivity(new Intent(Intent.ACTION_VIEW,uri));
                }return true;
            }
        });root.addView(web,new LinearLayout.LayoutParams(-1,0,1));render();
    }
    private final class NativeActions {
        @JavascriptInterface public void copy(String chain,String address){runOnUiThread(()->{
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(chain+" wallet address",address));info.setText(chain+" address copied");
        });}
    }
    private static String load(InputStream input)throws Exception{
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private void render(){try{
        String sol=prefs.get("sol",SOL),eth=prefs.get("eth",ETH);
        JSONObject o=new JSONObject();o.put("compact",compact);o.put("dark",dark);o.put("name",prefs.get("supportName","I am canceled"));
        o.put("paypal",new JSONObject().put("label","PayPal").put("url",prefs.get("paypal",PP)));
        o.put("cashApp",new JSONObject().put("label","Cash App").put("tag","$falcox904").put("url",prefs.get("cashapp",CA)));
        o.put("solana",new JSONObject().put("label","SOL").put("address",sol).put("explorerUrl","https://solscan.io/account/"+sol));
        o.put("eth",new JSONObject().put("label","ETH").put("address",eth).put("explorerUrl","https://etherscan.io/address/"+eth));
        String js=load(getAssets().open("donation-banner.js"));
        String bridge="<script>"+js+"\nconst opts="+o.toString()+";document.getElementById('banner').innerHTML=donationBanner(opts);"+
            "document.getElementById('banner').addEventListener('click',function(event){"+
            "let target=event.target.closest('.dl2a-sb,.dl2a-et');if(!target)return;event.preventDefault();event.stopPropagation();"+
            "let sol=target.classList.contains('dl2a-sb');SupportNative.copy(sol?'SOL':'ETH',sol?opts.solana.address:opts.eth.address);},true);</script>";
        String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body><div id='banner'></div>"+bridge+"</body></html>";
        web.loadDataWithBaseURL("https://appassets.androidplatform.net/support/",html,"text/html","UTF-8",null);
        info.setText("DonationLink2Ad · "+dark+(compact?" · compact":" · full"));
    }catch(Exception e){info.setText("Donation renderer failed: "+e.getMessage());}}
    @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("SupportNative");web.destroy();}super.onDestroy();}
}
