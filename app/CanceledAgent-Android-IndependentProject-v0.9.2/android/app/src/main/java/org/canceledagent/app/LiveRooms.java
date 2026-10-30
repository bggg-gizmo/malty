package org.canceledagent.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLSocketFactory;

/** RFC 6455 WebSocket client implemented without Gradle, external libraries or Perchance. */
public final class LiveRooms {
    public interface Listener { void event(JSONObject json); void state(String state); }
    private final Activity activity;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newSingleThreadExecutor();
    private final SecureRandom random=new SecureRandom();
    private volatile Socket socket;
    private volatile OutputStream output;
    private String room="",nick="",base="",token="";
    private volatile boolean stopped=true;
    private int attempt;
    private long lastEdit;
    public LiveRooms(Activity a,Listener l){activity=a;listener=l;}
    private void state(String s){activity.runOnUiThread(()->listener.state(s));}
    private void event(JSONObject j){activity.runOnUiThread(()->listener.event(j));}
    public synchronized void join(String address,String r,String n,String bearer){
        leave();stopped=false;base=address.replaceAll("/+$","");token=bearer==null?"":bearer;
        room=r.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]","");
        if(room.length()>24)room=room.substring(0,24);
        if(room.length()<2){state("Invalid room");stopped=true;return;}
        nick=n.replaceAll("[<>&\"]","");if(nick.length()>24)nick=nick.substring(0,24);
        if(nick.isEmpty())nick="molty";
        connect();
    }
    private void connect(){
        if(stopped)return;
        workers.execute(()->{
            try {
                String target=base.replaceFirst("^http://","ws://").replaceFirst("^https://","wss://")+"/ws";
                URI uri=new URI(target);
                String scheme=uri.getScheme(),host=uri.getHost();
                if(host==null||(!"ws".equals(scheme)&&!"wss".equals(scheme)))throw new IOException("Invalid socket URL");
                if(!token.isEmpty()&&!"wss".equals(scheme)&&!host.equals("127.0.0.1")&&!host.equals("localhost"))throw new SecurityException("Remote tokens require WSS");
                int port=uri.getPort()>0?uri.getPort():"wss".equals(scheme)?443:80;
                Socket conn="wss".equals(scheme)?SSLSocketFactory.getDefault().createSocket(host,port):new Socket(host,port);
                conn.setSoTimeout(25000);socket=conn;
                InputStream in=conn.getInputStream();OutputStream out=conn.getOutputStream();
                byte[] nonce=new byte[16];random.nextBytes(nonce);
                String key=android.util.Base64.encodeToString(nonce,android.util.Base64.NO_WRAP);
                String path=uri.getRawPath()==null?"/ws":uri.getRawPath();
                StringBuilder req=new StringBuilder("GET "+path+" HTTP/1.1\r\nHost: "+host+":"+port+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "+key+"\r\n");
                if(!token.isEmpty())req.append("Authorization: Bearer ").append(token).append("\r\n");
                req.append("\r\n");out.write(req.toString().getBytes(StandardCharsets.US_ASCII));out.flush();
                ByteArrayOutputStream response=new ByteArrayOutputStream();
                while(true){int c=in.read();if(c<0)throw new EOFException("Handshake closed");response.write(c);int z=response.size();byte[] b=response.toByteArray();if(z>=4&&b[z-4]==13&&b[z-3]==10&&b[z-2]==13&&b[z-1]==10)break;if(z>16384)throw new IOException("Oversized handshake");}
                String headers=response.toString("ISO-8859-1");
                if(!headers.startsWith("HTTP/1.1 101 ")&&!headers.startsWith("HTTP/1.1 101\r"))throw new IOException("WebSocket handshake rejected: "+headers.split("\r\n")[0]);
                byte[] sha=MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
                String accept=android.util.Base64.encodeToString(sha,android.util.Base64.NO_WRAP);
                if(!headers.toLowerCase(Locale.ROOT).contains("sec-websocket-accept: "+accept.toLowerCase(Locale.ROOT)))throw new IOException("WebSocket accept mismatch");
                output=out;attempt=0;state("Room connected: "+room);
                send(new JSONObject().put("t","join").put("room",room).put("nick",nick).toString());
                long lastPong=System.currentTimeMillis();
                while(!stopped){
                    int a,b;
                    try{a=in.read();b=in.read();}
                    catch(SocketTimeoutException timeout){
                        if(System.currentTimeMillis()-lastPong>75000)throw new IOException("Heartbeat not acknowledged");
                        frame(9,new byte[]{0x43,0x41});continue;
                    }
                    if(a<0||b<0)throw new EOFException("Disconnected");
                    int code=a&15;long len=b&127;
                    if(len==126)len=((in.read()&255)<<8)|(in.read()&255);
                    if(len==127){len=0;for(int i=0;i<8;i++)len=(len<<8)|(in.read()&255);}
                    if(len>300000)throw new IOException("Frame exceeds room contract");
                    byte[] mask=new byte[4];if((b&128)!=0)readFull(in,mask);
                    byte[] payload=new byte[(int)len];readFull(in,payload);
                    if((b&128)!=0)for(int i=0;i<payload.length;i++)payload[i]^=mask[i%4];
                    if(code==8)throw new EOFException("Socket closed");
                    if(code==9){frame(10,payload);continue;}
                    if(code==10){lastPong=System.currentTimeMillis();continue;}
                    if(code==1)try{event(new JSONObject(new String(payload,StandardCharsets.UTF_8)));}catch(Exception ex){state("Room message malformed: "+ex.getMessage());}
                }
            }catch(Exception e){if(!stopped){state("Room disconnected: "+e.getMessage());retry();}}
            finally{try{Socket s=socket;if(s!=null)s.close();}catch(Exception ignored){}output=null;}
        });
    }
    private static void readFull(InputStream in,byte[] b)throws IOException{int p=0;while(p<b.length){int n=in.read(b,p,b.length-p);if(n<0)throw new EOFException();p+=n;}}
    private synchronized void frame(int opcode,byte[] bytes)throws IOException{
        OutputStream out=output;if(out==null)throw new IOException("Not connected");
        ByteArrayOutputStream f=new ByteArrayOutputStream();f.write(0x80|opcode);
        int n=bytes.length;if(n<126)f.write(0x80|n);else if(n<65536){f.write(0x80|126);f.write(n>>8);f.write(n);}else{f.write(0x80|127);long longLength=n;for(int k=7;k>=0;k--)f.write((int)(longLength>>>(k*8))&255);}
        byte[] mask=new byte[4];random.nextBytes(mask);f.write(mask);
        for(int i=0;i<n;i++)f.write(bytes[i]^mask[i%4]);out.write(f.toByteArray());out.flush();
    }
    private void send(String s)throws IOException{frame(1,s.getBytes(StandardCharsets.UTF_8));}
    private void retry(){if(stopped)return;int delay=Math.min(30000,1000*(1<<Math.min(5,attempt++)));handler.postDelayed(()->{if(!stopped)connect();},delay);}
    public void edit(String html){if(stopped||html.length()>40000||System.currentTimeMillis()-lastEdit<310)return;lastEdit=System.currentTimeMillis();
        try{send(new JSONObject().put("t","edit").put("html",html).put("mid",UUID.randomUUID().toString()).toString());}catch(Exception e){state(e.getMessage());}}
    public void save(String html){if(stopped||html.length()>40000){state("Room unavailable or HTML too large");return;}
        try{send(new JSONObject().put("t","rpc").put("method","save").put("requestId",UUID.randomUUID().toString()).put("data",new JSONObject().put("room",room).put("html",html)).toString());}catch(Exception e){state(e.getMessage());}}
    public synchronized void leave(){stopped=true;handler.removeCallbacksAndMessages(null);try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;output=null;}
}
