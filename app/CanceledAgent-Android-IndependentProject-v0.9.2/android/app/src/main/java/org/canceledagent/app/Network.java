package org.canceledagent.app;

import android.app.Activity;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Network {
    public interface Result { void success(JSONObject json); void failure(String message); }
    private static final ExecutorService WORKERS=Executors.newFixedThreadPool(3);
    private final Activity activity;
    private final SecurePrefs settings;
    public Network(Activity activity,SecurePrefs settings){this.activity=activity;this.settings=settings;}
    private void error(Result callback,String error){activity.runOnUiThread(()->callback.failure(error));}
    private void ok(Result callback,JSONObject json){activity.runOnUiThread(()->callback.success(json));}
    public void backend(String method,String path,JSONObject body,Result callback){
        String base=settings.get("backend","http://127.0.0.1:8000").replaceAll("/+$","");
        if(!path.startsWith("/")){error(callback,"Invalid backend path");return;}
        request(method,base+path,RuntimeSecrets.local(base)?RuntimeSecrets.ensure(activity):settings.readSecret("backendToken"),body,callback);
    }
    public void moltbook(String method,String path,JSONObject body,Result callback){
        if(!path.startsWith("/")||path.startsWith("//")){error(callback,"Invalid Moltbook API path");return;}
        String key=settings.readSecret("moltbook");
        if(key.isEmpty()){error(callback,"Save a Moltbook key under Settings");return;}
        // Credential is exclusively sent to the official www.moltbook.com hostname.
        request(method,"https://www.moltbook.com/api/v1"+path,key,body,callback);
    }
    private void request(String method,String target,String bearer,JSONObject body,Result callback){
        WORKERS.execute(()->{
            HttpURLConnection conn=null;
            try {
                URL url=new URL(target);
                if(target.startsWith("https://www.moltbook.com/api/v1")){
                    if(!"https".equals(url.getProtocol())||!"www.moltbook.com".equals(url.getHost()))
                        throw new SecurityException("Moltbook credentials blocked for unauthorized hostname");
                } else if(bearer!=null && !bearer.isEmpty() && !"https".equals(url.getProtocol())
                      && !"127.0.0.1".equals(url.getHost()) && !"localhost".equals(url.getHost())) {
                    throw new SecurityException("Backend tokens require HTTPS outside localhost");
                }
                conn=(HttpURLConnection)url.openConnection();
                conn.setConnectTimeout(12000);conn.setReadTimeout(70000);conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod(method);conn.setRequestProperty("Accept","application/json");
                if(bearer!=null && !bearer.isEmpty())conn.setRequestProperty("Authorization","Bearer "+bearer);
                if(body!=null){
                    conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");
                    try(OutputStream out=conn.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
                }
                int code=conn.getResponseCode();
                InputStream stream=code>=400?conn.getErrorStream():conn.getInputStream();
                byte[] bytes;
                if(stream==null)bytes=new byte[0];
                else try(InputStream in=stream;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                    byte[] chunk=new byte[8192];int n;
                    while((n=in.read(chunk))!=-1)out.write(chunk,0,n);
                    bytes=out.toByteArray();
                }
                JSONObject value;
                try{value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
                catch(JSONException ex){value=new JSONObject().put("body",new String(bytes,StandardCharsets.UTF_8));}
                value.put("httpStatus",code);
                if(code>=400){error(callback,"HTTP "+code+": "+value.toString());return;}
                ok(callback,value);
            }catch(Exception ex){error(callback,ex.getClass().getSimpleName()+": "+ex.getMessage());}
            finally{if(conn!=null)conn.disconnect();}
        });
    }
}
