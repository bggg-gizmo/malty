package org.canceledagent.app;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLSocketFactory;

/** Optional Socket.IO / Engine.IO v4 bridge connection, fully initiated and maintained by the APK.
 * An outbound relay is required only when the phone has no publicly reachable inbound route.
 */
public final class OutboundBridge {
    private final Context context;private final SecurePrefs prefs;private final EmbeddedServer api;private final ExecutorService work=Executors.newFixedThreadPool(3);
    private volatile Socket connection;private volatile boolean active;private volatile OutputStream out;
    private final Set<String> processed=Collections.synchronizedSet(new LinkedHashSet<String>());
    private final SecureRandom random=new SecureRandom();
    private volatile String connectionChallenge="";
    public OutboundBridge(Context ctx,EmbeddedServer server){context=ctx.getApplicationContext();prefs=new SecurePrefs(ctx);api=server;}
    public void start(){if(active)return;active=true;work.execute(this::run);}
    public void stop(){active=false;Socket c=connection;if(c!=null)try{c.close();}catch(Exception ignored){}prefs.put("bridge-status","offline");}
    public String status(){return prefs.get("bridge-status","offline");}
    private String session(){String id=prefs.get("bridge-session","");if(id.isEmpty()){id="android-"+UUID.randomUUID();prefs.put("bridge-session",id);}return id;}
    private void run(){int attempt=0;while(active){
        try{connect();attempt=0;}catch(Exception e){prefs.put("bridge-status","error: "+e.getMessage());}
        if(!active)break;try{Thread.sleep(Math.min(30000,(long)(1000*Math.pow(2,Math.min(attempt++,5)))));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
    }}
    private void connect()throws Exception{
        String address=prefs.get("bridge-relay","").trim();if(address.isEmpty())throw new IOException("Bridge relay address not configured");
        URI u=new URI(address);String host=u.getHost();if(host==null||!u.getScheme().equals("https")||u.getUserInfo()!=null)throw new IOException("Bridge relay must be an HTTPS origin");
        int port=u.getPort()>0?u.getPort():443;String query="EIO=4&transport=websocket";
        String path=(u.getPath()==null||u.getPath().equals("/")?"/socket.io/":u.getPath());
        if(!path.endsWith("/"))path+="/";
        if(u.getQuery()!=null&&!u.getQuery().isEmpty())query=u.getQuery()+"&"+query;
        Socket s=BridgeTransportSecurity.connect((SSLSocketFactory)SSLSocketFactory.getDefault(),host,port,70000);
        connection=s;
        byte[] challengeBytes=new byte[24];random.nextBytes(challengeBytes);
        connectionChallenge=Base64.encodeToString(challengeBytes,Base64.NO_WRAP|Base64.URL_SAFE);
        synchronized(processed){processed.clear();}
        InputStream input=s.getInputStream();OutputStream output=s.getOutputStream();byte[] nonce=new byte[16];random.nextBytes(nonce);
        String key=Base64.encodeToString(nonce,Base64.NO_WRAP);String h="GET "+path+"?"+query+" HTTP/1.1\r\nHost: "+host+(port==443?"":":"+port)+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n";
        output.write(h.getBytes(StandardCharsets.US_ASCII));output.flush();ByteArrayOutputStream headers=new ByteArrayOutputStream();byte[] last=new byte[4];int n;
        while((n=input.read())!=-1){headers.write(n);last[0]=last[1];last[1]=last[2];last[2]=last[3];last[3]=(byte)n;if(last[0]==13&&last[1]==10&&last[2]==13&&last[3]==10)break;if(headers.size()>16000)throw new IOException("oversized handshake");}
        String response=headers.toString("ISO-8859-1");if(!response.startsWith("HTTP/1.1 101"))throw new IOException("WebSocket upgrade rejected: "+response.split("\\r\\n")[0]);
        String expected=Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),Base64.NO_WRAP);
        if(!response.toLowerCase(Locale.ROOT).contains(("sec-websocket-accept: "+expected).toLowerCase(Locale.ROOT)))throw new IOException("Invalid WebSocket accept digest");
        out=output;prefs.put("bridge-status","connected");
        try{while(active){int a=input.read(),b=input.read();if(a<0||b<0)throw new EOFException("relay socket closed");int op=a&15;long len=b&127;
            if(len==126)len=((input.read()&255)<<8)|(input.read()&255);if(len==127){len=0;for(int i=0;i<8;i++)len=(len<<8)|(input.read()&255);}
            if(len>1500000)throw new IOException("relay frame too large");byte[] mask=(b&128)!=0?read(input,4):null,raw=read(input,(int)len);if(mask!=null)for(int i=0;i<raw.length;i++)raw[i]^=mask[i%4];
            if(op==8)throw new EOFException("relay shutdown");if(op==9){frame(10,raw);continue;}if(op!=1)continue;
            handle(new String(raw,StandardCharsets.UTF_8));
        }}finally{out=null;try{s.close();}catch(Exception ignored){}connection=null;}
    }
    private static byte[] read(InputStream in,int length)throws IOException{byte[] data=new byte[length];for(int i=0;i<length;){int n=in.read(data,i,length-i);if(n<0)throw new EOFException();i+=n;}return data;}
    private synchronized void frame(int type,byte[] bytes)throws IOException{
        if(out==null)throw new IOException("not connected");ByteArrayOutputStream buf=new ByteArrayOutputStream();buf.write(128|type);int len=bytes.length;
        if(len<126)buf.write(128|len);else if(len<65536){buf.write(128|126);buf.write((len>>8)&255);buf.write(len&255);}else{buf.write(128|127);for(int i=7;i>=0;i--)buf.write((int)(((long)len>>>(i*8))&255));}
        byte[] mask=new byte[4];random.nextBytes(mask);buf.write(mask);for(int i=0;i<bytes.length;i++)buf.write(bytes[i]^mask[i%4]);out.write(buf.toByteArray());out.flush();
    }
    private void send(String payload)throws IOException{frame(1,payload.getBytes(StandardCharsets.UTF_8));}
    private void emit(String op,JSONObject data)throws IOException{send("42"+new JSONArray().put(op).put(data));}
    /** Scope is independently recomputed from the exact authenticated payload. */
    private static String requiredScope(JSONObject call){
        if(call.has("op")&&call.has("request"))return "invalid";
        if(call.has("request")){
            JSONObject q=call.optJSONObject("request");
            return q!=null&&q.optString("model").startsWith("moltbook-team")?"moltbook:write":"inference:generate";
        }
        String op=call.optString("op"),action=call.optString("action");
        if(op.equals("ping")||op.equals("team-status")||op.equals("team-capability")
           ||op.equals("race-list")||op.equals("race-document")||op.equals("roster-discover")
           ||op.equals("watch")||op.equals("inbox"))return "bridge:read";
        if(op.equals("mesh")&&(action.equals("inbox")||action.equals("talk")||action.equals("presence")))return "bridge:read";
        if(op.equals("character-agents")&&(action.equals("list")||action.equals("profile")||action.equals("threads")||action.equals("history")))return "bridge:read";
        if(op.equals("agent")&&action.equals("status"))return "bridge:read";
        if(op.equals("moltbook")&&action.equals("route"))return "bridge:read";
        return op.equals("moltbook")||op.equals("team-dispatch")||op.equals("race-create")
               ||op.equals("race-send")||op.equals("race-collect")||op.equals("reach")
               ||op.equals("reach-many")?"moltbook:write":"bridge:write";
    }
    private void handle(String raw)throws Exception{
        if(raw.equals("2")){send("3");return;}
        if(raw.startsWith("0")){send("40");return;}
        if(raw.startsWith("40")){prefs.put("bridge-status","registered: "+session());emit("bridge:register",LocalCore.obj("sessionId",session(),"hasChild",true,"authVersion",1,"challenge",connectionChallenge));return;}
        if(!raw.startsWith("42"))return;JSONArray event=new JSONArray(raw.substring(2));String type=event.optString(0);JSONObject message=event.optJSONObject(1);
        if(message==null||!(type.equals("bridge:model_response")||type.equals("bridge:crossframe_out")))return;
        String requestId=message.optString("requestId"),payloadJson=message.optString("payloadJson","");
        if(payloadJson.isEmpty()||payloadJson.length()>262144)return;
        final JSONObject call=new JSONObject(payloadJson);
        String scope=requiredScope(call);
        if(!scope.equals(message.optString("scope")))return;
        if(!BridgeRequestSecurity.verify(RuntimeSecrets.ensure(context),connectionChallenge,session(),
            message.optString("challenge"),message.optString("sessionId"),requestId,message.optLong("issuedAt",0),
            scope,payloadJson,message.optString("signature"),System.currentTimeMillis()))return;
        synchronized(processed){if(processed.contains(requestId))return;processed.add(requestId);
            if(processed.size()>500){Iterator<String> it=processed.iterator();for(int i=0;i<100&&it.hasNext();i++){it.next();it.remove();}}}
        work.execute(()->{
            try{JSONObject result;
                if(call.has("op"))result=api.route("POST","/api/bridge/relay",LocalCore.obj("payload",call)).optJSONObject("response");
                else if(call.has("request")){
                    JSONObject req=call.optJSONObject("request");if(req==null)req=new JSONObject();String kind=call.optString("kind");
                    result=api.route("POST",kind.toLowerCase(Locale.ROOT).contains("anthropic")?"/v1/messages":"/v1/chat/completions",req);
                }else result=LocalCore.fail("unrecognized-bridge-payload");
                if(result==null)result=LocalCore.fail("empty-bridge-response");
                emit("bridge:crossframe_in",LocalCore.obj("requestId",requestId,"sessionId",session(),"source","canceled-agent-android","payload",result));
            }catch(Exception ex){try{emit("bridge:crossframe_in",LocalCore.obj("requestId",requestId,"sessionId",session(),"source","canceled-agent-android","payload",LocalCore.obj("ok",false,"err",ex.getMessage())));}catch(Exception ignored){}}
        });
    }
}
