package org.canceledagent.app;

import android.content.Context;
import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Keyless Perchance AI adapter. This provider is a network service, not a local model.
 * API shape is derived from the recovered official ai-text-plugin. User verification is
 * handled explicitly in PerchanceVerificationActivity. Never simulates verification.
 */
public final class PerchanceInference {
    static final String HOST="https://text-generation.perchance.org";
    static final String GENERATE=HOST+"/api/generate";
    private static final int MAX_STREAM_CHARS=2_000_000;
    private PerchanceInference(){}

    static String formatInstruction(String prompt){
        if(prompt==null||prompt.trim().isEmpty())throw new IllegalArgumentException("empty-instruction");
        String s=prompt.replaceFirst(" ","\u00a0");
        if(s.indexOf('\u00a0')<0)s=s+"\u00a0";
        return s;
    }
    private static String safeError(String msg){
        String result=msg==null?"provider-error":msg.replaceAll("[\\r\\n]"," ");
        return result.length()>300?result.substring(0,300):result;
    }
    public static JSONObject generate(Context context,String prompt,int tokens)throws Exception{
        JSONObject request=new JSONObject().put("instruction",formatInstruction(prompt))
           .put("startWith","").put("stopSequences",new JSONArray())
           .put("generatorName","canceled-perchance-agent");
        HttpURLConnection conn=(HttpURLConnection)new URL(GENERATE).openConnection();
        conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);conn.setRequestMethod("POST");conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type","application/json");
        conn.setRequestProperty("Accept","text/event-stream, application/x-ndjson, application/json");
        // These cookies come ONLY from Android's first-party Perchance verification WebView.
        // A verification failure returns an error and requires user interaction.
        String cookie=CookieManager.getInstance().getCookie(HOST);
        if(cookie!=null&&!cookie.trim().isEmpty())conn.setRequestProperty("Cookie",cookie);
        try{
            try(OutputStream out=conn.getOutputStream()){out.write(request.toString().getBytes(StandardCharsets.UTF_8));}
            int status=conn.getResponseCode();
            if(status==401||status==403)return LocalCore.obj("ok",false,"error","perchance-verification-required","httpStatus",status,"verificationUrl",HOST+"/embed","provider","perchance-free");
            if(status==429)return LocalCore.obj("ok",false,"error","perchance-rate-limited","httpStatus",status,"provider","perchance-free");
            if(status<200||status>=300)return LocalCore.obj("ok",false,"error","perchance-http-"+status,"httpStatus",status,"provider","perchance-free");
            StringBuilder text=new StringBuilder();boolean complete=false;String reason="";
            try(InputStream stream=conn.getInputStream();BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
                String line;int count=0;
                while((line=reader.readLine())!=null){
                    count+=line.length();if(count>MAX_STREAM_CHARS)throw new IOException("perchance-stream-too-large");
                    line=line.trim();if(line.isEmpty()||line.startsWith(":"))continue;
                    if(line.startsWith("data:"))line=line.substring(5).trim();
                    if(line.equals("[DONE]")){complete=true;break;}
                    JSONObject event;
                    try{event=new JSONObject(line);}catch(Exception ex){
                        // Only parse JSON-bearing events. Don't append raw protocol metadata to answers.
                        continue;
                    }
                    String type=event.optString("type");
                    if(type.equals("streamError")||event.has("error"))return LocalCore.obj("ok",false,"error",safeError(event.optString("status",event.optString("error","perchance-stream-error"))),"provider","perchance-free");
                    if(type.equals("streamEnd")){complete=true;break;}
                    JSONObject value=event.optJSONObject("value");
                    if(value==null)value=event;
                    if(value.has("text")){
                        String chunk=value.optString("text");text.append(chunk);
                        if(text.length()>MAX_STREAM_CHARS)throw new IOException("perchance-output-too-large");
                    }
                    if(value.optBoolean("final")){complete=true;reason=value.optString("stopReason");break;}
                }
            }
            if(!complete)return LocalCore.obj("ok",false,"error","perchance-stream-incomplete","partialChars",text.length(),"provider","perchance-free");
            if(text.toString().trim().isEmpty())return LocalCore.obj("ok",false,"error","perchance-empty-result","provider","perchance-free");
            return LocalCore.obj("ok",true,"text",text.toString(),"model","perchance-free","provider","perchance-free","stopReason",reason,"keyless",true,"moltbookCreditsSpent",0);
        }finally{conn.disconnect();}
    }
}
