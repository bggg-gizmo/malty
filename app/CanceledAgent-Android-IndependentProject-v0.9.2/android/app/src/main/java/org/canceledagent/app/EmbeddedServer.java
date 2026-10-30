package org.canceledagent.app;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** First-party, on-device JSON+WebSocket runtime. No Python, server APK, Termux or Perchance needed. */
public final class EmbeddedServer {
    private final Context context;private final LocalCore db;private final AgentRuntime agents;private final SecurePrefs settings;private final MoltbookEngine moltbook;private final AppAutomation automation;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Set<Peer> peers=Collections.synchronizedSet(new HashSet<Peer>());
    private volatile ServerSocket socket;private volatile boolean active;private volatile String error="";
    public static final int PORT=8000;
    private final int requestedPort;private volatile int effectivePort;
    public EmbeddedServer(Context c){this(c,null,PORT);}
    /** Allows an isolated ephemeral-port server with a separate SQLite database for executable Android tests. */
    public EmbeddedServer(Context c,LocalCore isolated,int port){context=c.getApplicationContext();db=isolated==null?new LocalCore(context):isolated;agents=new AgentRuntime(context);requestedPort=port;effectivePort=port;settings=new SecurePrefs(context);moltbook=new MoltbookEngine(context,db);automation=new AppAutomation(context,db,this,moltbook);RuntimeSecrets.ensure(context);}
    public int boundPort(){return effectivePort;}
    public boolean isRunning(){return active&&socket!=null&&!socket.isClosed();}
    public void scheduledTick(){if(active)automation.tick();}
    public String lastError(){return error;}
    public synchronized void start(boolean lan)throws IOException{
        stop();ServerSocket ss=new ServerSocket();ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName(lan?"0.0.0.0":"127.0.0.1"),requestedPort),48);effectivePort=ss.getLocalPort();
        socket=ss;active=true;error="";
        workers.execute(()->{while(active){try{final Socket client=ss.accept();client.setSoTimeout(30000);workers.execute(()->serve(client));}catch(IOException ex){if(active)error="accept: "+ex.getMessage();}}});
    }
    public synchronized void stop(){active=false;ServerSocket s=socket;socket=null;if(s!=null)try{s.close();}catch(IOException ignored){} synchronized(peers){for(Peer p:peers)p.close();peers.clear();}}
    private static byte[] readBody(InputStream in,int size)throws IOException{byte[] data=new byte[size];for(int o=0;o<size;){int n=in.read(data,o,size-o);if(n<0)throw new EOFException();o+=n;}return data;}
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int n;while((n=in.read())!=-1){if(n==10)break;if(n!=13)b.write(n);if(b.size()>16384)throw new IOException("Header too long");}if(n==-1&&b.size()==0)return null;return b.toString("UTF-8");}
    private void serve(Socket client){try(Socket s=client){InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();String start=line(in);if(start==null)return;
            String[] first=start.split(" ");if(first.length<3)return;String method=first[0],path=first[1];Map<String,String> headers=new HashMap<>();String l;
            while((l=line(in))!=null&&!l.isEmpty()){int k=l.indexOf(':');if(k>0)headers.put(l.substring(0,k).toLowerCase(Locale.ROOT),l.substring(k+1).trim());}
            boolean ws="websocket".equalsIgnoreCase(headers.get("upgrade"));
            String token=RuntimeSecrets.ensure(context);String auth=headers.get("authorization");
            if(!("Bearer "+token).equals(auth)){
                // Health deliberately includes no personal data. Every other endpoint requires a token.
                if(!(method.equals("GET")&&path.equals("/health"))){reply(out,401,LocalCore.fail("unauthorized"));return;}
            }
            if(ws){if(!path.equals("/ws")){reply(out,404,LocalCore.fail("not-found"));return;}upgrade(in,out,headers,s);return;}
            int len=Integer.parseInt(headers.getOrDefault("content-length","0"));if(len<0||len>1000000){reply(out,413,LocalCore.fail("body-too-large"));return;}
            JSONObject body=len>0?new JSONObject(new String(readBody(in,len),StandardCharsets.UTF_8)):new JSONObject();
            JSONObject response=route(method,path,body);reply(out,response.optBoolean("ok",true)?200:400,response);
        }catch(Exception e){error=e.getClass().getSimpleName()+": "+e.getMessage();}}
    private static void reply(OutputStream out,int code,JSONObject data)throws IOException{byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
        String status=code==200?"OK":code==401?"Unauthorized":code==413?"Too Large":"Bad Request";
        String h="HTTP/1.1 "+code+" "+status+"\r\nContent-Type: application/json; charset=UTF-8\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nAccess-Control-Allow-Origin: null\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();}
    private static String decode(String s){try{return URLDecoder.decode(s,"UTF-8");}catch(Exception e){return s;}}
    private static Map<String,String> params(String q){Map<String,String> v=new HashMap<>();for(String pair:q.split("&")){String[] x=pair.split("=",2);if(x.length>0)v.put(decode(x[0]),x.length>1?decode(x[1]):"");}return v;}
    public JSONObject route(String method,String target,JSONObject body)throws Exception {
        String[] fragments=target.split("\\?",2);String path=decode(fragments[0]);Map<String,String> query=params(fragments.length>1?fragments[1]:"");
        if(path.equals("/health"))return LocalCore.obj("ok",true,"service","android-embedded","perchanceDependency",false,"onDevice",true,"database","SQLite","websocket","/ws","port",effectivePort);
        if(path.startsWith("/api/agents"))return characterRoutes(method,path,body);
        if(path.equals("/api/rooms")&&method.equals("GET"))return db.listRooms();
        if(path.startsWith("/api/rooms/")){String room=path.substring(11);if(method.equals("GET"))return db.loadRoom(room);if(method.equals("PUT"))return db.saveRoom(room,body.optString("html"));}
        if(path.equals("/api/votes")&&method.equals("GET"))return db.votes();
        if(path.equals("/api/votes")&&method.equals("POST"))return db.vote(body);
        if(path.equals("/api/ai/status"))return aiStatus();
        if(path.equals("/api/ai/generate")&&method.equals("POST"))return ai(body);
        if(path.equals("/v1/models")||path.equals("/api/workflows/bridge/models"))return models();
        if(path.equals("/v1/chat/completions")&&method.equals("POST"))return completion(body,false);
        if(path.equals("/v1/messages")&&method.equals("POST"))return completion(body,true);
        if(path.equals("/api/bridge/relay")&&method.equals("POST"))return relay(body);
        if(path.startsWith("/api/mesh/"))return mesh(method,path,body);
        if(path.startsWith("/api/workflows/"))return workflows(method,path.substring(15),body,query);
        return LocalCore.fail("unknown-endpoint");
    }
    /** REST operations available to authenticated device/LAN and Bridge callers. */
    private JSONObject characterRoutes(String method,String path,JSONObject data)throws Exception {
        String sub=path.substring("/api/agents".length());
        if(sub.isEmpty()&&method.equals("GET"))return agents.agents();
        if(sub.equals("/threads")){
            if(method.equals("GET"))return agents.threads();
            if(method.equals("POST"))return agents.createThread(data);
        }
        String[] p=sub.split("/");
        if(p.length==3&&p[1].equals("profiles")&&method.equals("GET"))return agents.character(p[2]);
        if(p.length==4&&p[1].equals("profiles")&&p[3].equals("import")&&method.equals("POST"))return agents.downloadAndImport(p[2]);
        if(p.length==4&&p[1].equals("threads")&&p[3].equals("messages")){
            if(method.equals("GET"))return agents.messages(p[2]);
            if(method.equals("POST"))return agents.append(p[2],"user","user",data.optString("text"));
        }
        if(p.length==4&&p[1].equals("threads")&&p[3].equals("turn")&&method.equals("POST"))return agents.generateTurn(p[2],data.optString("agent"),data.optBoolean("optIn"));
        return LocalCore.fail("unknown-character-operation");
    }
    private JSONObject mesh(String method,String path,JSONObject data)throws Exception{
        String tail=path.substring(10);if(tail.equals("send")&&method.equals("POST"))return db.send(data);
        if(tail.equals("beat")&&method.equals("POST"))return db.beat(data);
        if(tail.equals("presence")&&method.equals("GET"))return db.presence();
        if(tail.startsWith("inbox/")){String nick=tail.substring(6);if(nick.endsWith("/read")){nick=nick.substring(0,nick.length()-5);return db.inbox(nick,true);}return db.inbox(nick,false);}
        if(tail.startsWith("talk/")){String topic=tail.substring(5);if(method.equals("POST"))return db.comment(topic,data);return db.topic(topic,50);}
        return LocalCore.fail("unknown-mesh-operation");
    }
    private JSONObject workflows(String method,String tail,JSONObject data,Map<String,String> query)throws Exception {
        if(tail.equals("catalog"))return db.catalog();
        if(tail.equals("audit"))return db.audit(Integer.parseInt(query.getOrDefault("limit","100")));
        if(tail.startsWith("records/")){
            String[] p=tail.split("/");if(p.length==2){if(method.equals("GET"))return db.records(p[1],Integer.parseInt(query.getOrDefault("limit","100")));if(method.equals("POST"))return db.createRecord(p[1],data);}
            if(p.length==3){if(method.equals("GET"))return db.getRecord(p[1],p[2]);if(method.equals("PUT"))return db.updateRecord(p[1],p[2],data);if(method.equals("DELETE"))return db.deleteRecord(p[1],p[2],Integer.parseInt(query.getOrDefault("version","-1")));}}
        if(tail.equals("market/billboards"))return db.market();
        if(tail.equals("market/tagline-controls")){JSONObject c=db.readState("tagline",LocalCore.obj("enabled",false,"visibleAt",0));
            if(data.has("enabled"))c.put("enabled",data.optBoolean("enabled"));
            if(data.has("visible"))c.put("visibleAt",data.optBoolean("visible")?System.currentTimeMillis():0);
            db.saveState("tagline",c);return c.put("ok",true);}
        if(tail.startsWith("market/tagline/"))return tagline(tail.substring(15));
        if(tail.startsWith("fleet/")&&tail.endsWith("/transition"))return db.fleet(tail.substring(6,tail.length()-11),data);
        if(tail.startsWith("polls/")&&tail.endsWith("/vote"))return db.poll(tail.substring(6,tail.length()-5),data);
        if(tail.equals("lab/probes/commit"))return db.labCommit(data);
        if(tail.equals("lab/probes")&&method.equals("GET"))return db.labProbes();
        if(tail.equals("lab/verdicts"))return db.labVerdicts();
        if(tail.startsWith("lab/probes/")&&tail.endsWith("/reveal"))return db.labReveal(tail.substring(11,tail.length()-7),data);
        if(tail.startsWith("lab/probes/")&&tail.endsWith("/verdict"))return db.labVerdict(tail.substring(11,tail.length()-8),data);
        if(tail.equals("lab/oracles"))return db.oracles();
        if(tail.startsWith("lab/oracle/")&&tail.endsWith("/freeze"))return db.oracleFreeze(tail.substring(11,tail.length()-7),data);
        if(tail.startsWith("lab/oracle/")&&tail.endsWith("/compare"))return db.oracleCompare(tail.substring(11,tail.length()-8),data);
        if(tail.startsWith("lab/oracle/")&&tail.endsWith("/history"))return db.oracleHistory(tail.substring(11,tail.length()-8));
        if(tail.equals("lab/blockers")&&method.equals("POST"))return db.blocker(data);
        if(tail.equals("lab/blockers")&&method.equals("GET"))return db.blockers();
        if(tail.startsWith("lab/blockers/")&&tail.endsWith("/event"))return db.blockerEvent(tail.substring(13,tail.length()-6),data,false);
        if(tail.startsWith("lab/blockers/")&&tail.endsWith("/rollover"))return db.blockerEvent(tail.substring(13,tail.length()-9),data,true);
        if(tail.equals("lab/export"))return LocalCore.obj("ok",true,"probes",db.labProbes(),"oracles",db.oracles(),"blockers",db.blockers(),"verdicts",db.labVerdicts());
        if(tail.equals("webmesh/deeds/scores"))return db.deeds();
        if(tail.equals("radio/dial"))return db.dial();
        if(tail.startsWith("team/")&&tail.endsWith("/claim"))return db.claim(tail.substring(5,tail.length()-6),data,false);
        if(tail.startsWith("team/")&&tail.endsWith("/submit"))return db.claim(tail.substring(5,tail.length()-7),data,true);
        if(tail.equals("bridge/status"))return LocalCore.obj("ok",true,"embedded",true,"route",db.readState("team-route",LocalCore.obj("enabled",false)),"jobs",db.records("jobs",100).getJSONArray("items"));
        if(tail.equals("governor")&&method.equals("GET"))return db.governor();
        if(tail.equals("governor/budget")&&method.equals("POST")){JSONObject s=db.governor();s.remove("ok");s.put("remaining",Math.max(0,data.optInt("remaining")));db.saveState("governor",s);return s.put("ok",true);}
        if(tail.equals("autonomy")&&method.equals("GET"))return db.autonomy();
        if(tail.equals("autonomy/control")&&method.equals("POST"))return db.autonomyControl(data);
        if(tail.equals("autonomy/drafts")&&method.equals("GET"))return db.readState("drafts",LocalCore.obj("drafts",new JSONArray())).put("ok",true);
        if(tail.equals("autonomy/drafts/approve")&&method.equals("POST")){JSONObject drafts=db.readState("drafts",LocalCore.obj("drafts",new JSONArray()));JSONArray list=drafts.optJSONArray("drafts");int idx=data.optInt("index",list.length()-1);if(idx<0||idx>=list.length())return LocalCore.fail("bad-draft-index");list.getJSONObject(idx).put("approved",true);db.saveState("drafts",drafts);return LocalCore.obj("ok",true,"approved",idx);}
        if(tail.equals("autonomy/log")&&method.equals("GET"))return db.readState("autonomy-log",LocalCore.obj("events",new JSONArray())).put("ok",true);
        if(tail.startsWith("autonomy/")&&tail.endsWith("/run"))return automation.run(tail.substring(9,tail.length()-4));
        if(tail.equals("autonomy/docs")&&method.equals("GET"))return db.readState("docs",LocalCore.obj("docs",new JSONArray())).put("ok",true);
        if(tail.equals("autonomy/docs")&&method.equals("POST")){db.saveState("docs",data);return LocalCore.obj("ok",true,"docs",data.optJSONArray("docs"));}
        if(tail.equals("net/probe")&&method.equals("POST"))return probe(data.optString("url"));
        if(tail.equals("moltbook/route")&&method.equals("GET"))return moltbook.routeStatus();
        if(tail.equals("moltbook/route")&&method.equals("POST"))return routeSave(data);
        if(tail.equals("moltbook/route/check")&&method.equals("POST"))return moltbook.routeCheck();
        if(tail.equals("moltbook/jobs")&&method.equals("GET"))return moltbook.jobs();
        if(tail.equals("moltbook/ask")&&method.equals("POST"))return moltbook.ask(data);
        if(tail.startsWith("moltbook/poll/")&&method.equals("POST"))return moltbook.poll(tail.substring(14));
        if(tail.startsWith("moltbook/renew/")&&method.equals("POST"))return moltbook.renew(tail.substring(15));
        if(tail.equals("moltbook/cache/clear")&&method.equals("POST"))return moltbook.cacheClear();
        if(tail.equals("bridge/race")&&method.equals("GET"))return moltbook.raceList();
        if(tail.equals("bridge/race")&&method.equals("POST"))return moltbook.raceCreate(data);
        if(tail.equals("bridge/race/send")&&method.equals("POST"))return moltbook.raceSend(data);
        if(tail.equals("bridge/race/roster")&&method.equals("POST"))return moltbook.raceRoster(data.optString("introPostId"));
        if(tail.startsWith("bridge/race/")&&tail.endsWith("/collect")&&method.equals("POST"))return moltbook.raceCollect(tail.substring("bridge/race/".length(),tail.length()-"/collect".length()));
        if(tail.startsWith("bridge/race/")&&tail.endsWith("/document")&&method.equals("GET"))return moltbook.raceDocument(tail.substring("bridge/race/".length(),tail.length()-"/document".length()));
        if(tail.startsWith("bridge/race/")&&method.equals("GET"))return moltbook.raceGet(tail.substring("bridge/race/".length()));

        if(tail.equals("moltbook/operate")&&method.equals("POST"))return moltbook.operate(data);
        if(tail.equals("moltbook/publish")&&method.equals("POST"))return moltbook.publish(data);
        if(tail.equals("moltbook/agent")&&method.equals("POST"))return moltbook.agent(data);
        if(tail.equals("moltbook/reach")&&method.equals("POST"))return moltbook.reach(data);
        if(tail.equals("moltbook/reach-many")&&method.equals("POST"))return moltbook.reachMany(data);
        if(tail.equals("moltbook/watch")&&method.equals("POST"))return moltbook.watch(data);
        if(tail.equals("moltbook/inbox")&&method.equals("POST"))return moltbook.watchedInbox(data);
        return LocalCore.fail("unknown-workflow-operation");
    }
    private JSONObject routeSave(JSONObject data)throws Exception{
        boolean enabled=data.optBoolean("enabled");if(enabled&&data.optString("postId").trim().isEmpty())return LocalCore.fail("delegation-post-required");
        int wait=data.optInt("waitSec",120),quorum=data.optInt("quorum",1),cap=data.optInt("dailyCap",10);if(wait<60||wait>300||quorum<1||quorum>5||cap<0||cap>500)return LocalCore.fail("bad-route-config");
        db.saveState("team-route",data);return data.put("ok",true);
    }
    private JSONObject routeCheck()throws Exception{JSONObject route=db.readState("team-route",LocalCore.obj("enabled",false));return LocalCore.obj("ok",true,"configured",route.optBoolean("enabled")&&!route.optString("postId").isEmpty(),"route",route,"credentialsAvailable",!settings.readSecret("moltbook").isEmpty());}
    private JSONObject probe(String u)throws Exception{
        URL url=new URL(u);if(!url.getProtocol().equals("https"))return LocalCore.fail("https-only");
        InetAddress[] ips=InetAddress.getAllByName(url.getHost());for(InetAddress ip:ips)if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isSiteLocalAddress()||ip.isLinkLocalAddress()||ip.isMulticastAddress())return LocalCore.fail("private-network-blocked");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(6000);c.setReadTimeout(8000);c.setRequestMethod("GET");
        try{int status=c.getResponseCode();return LocalCore.obj("ok",true,"url",u,"status",status,"contentType",c.getContentType());}finally{c.disconnect();}
    }
    private JSONObject aiStatus()throws Exception{
        String endpoint=settings.get("aiEndpoint","");
        boolean configured=!endpoint.isEmpty()&&!settings.get("aiModel","").isEmpty();
        return LocalCore.obj("ok",true,"configured",true,"paidProviderConfigured",configured,
            "endpoint",endpoint,"model",configured?settings.get("aiModel",""):"perchance-free",
            "provider",configured?"openai-compatible":"perchance-free",
            "keylessFallbackAvailable",true,"requiresInternet",true,"verificationUrl",PerchanceInference.HOST+"/embed");
    }
    private JSONObject ai(JSONObject data)throws Exception{
        String endpoint=settings.get("aiEndpoint","");
        String model=settings.get("aiModel","");
        if(endpoint.isEmpty()||model.isEmpty())return PerchanceInference.generate(context,data.optString("prompt"),data.optInt("maxTokens",500));
        JSONObject result=customProvider(data,endpoint,model);
        // A paid/local provider failure NEVER silently causes a second provider call.
        if(!result.optBoolean("ok")&&"true".equals(settings.get("aiAllowFreeFallback","false"))){
            JSONObject fallback=PerchanceInference.generate(context,data.optString("prompt"),data.optInt("maxTokens",500));
            fallback.put("fallbackOf",model);return fallback;
        }
        return result;
    }
    private JSONObject customProvider(JSONObject data,String endpoint,String model)throws Exception{
        URL url=new URL(endpoint);
        if(!"https".equals(url.getProtocol())&&!url.getHost().equals("127.0.0.1")&&!url.getHost().equals("localhost"))return LocalCore.fail("ai-requires-https-outside-localhost");
        JSONObject payload=LocalCore.obj("model",model,"messages",new JSONArray().put(LocalCore.obj("role","user","content",data.optString("prompt"))),"max_tokens",Math.max(1,Math.min(4096,data.optInt("maxTokens",500))));
        HttpURLConnection c=(HttpURLConnection)url.openConnection();try{
            c.setConnectTimeout(15000);c.setReadTimeout(70000);c.setDoOutput(true);c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setRequestProperty("Content-Type","application/json");
            String key=settings.readSecret("aiKey");if(!key.isEmpty())c.setRequestProperty("Authorization","Bearer "+key);
            try(OutputStream o=c.getOutputStream()){o.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
            InputStream in=c.getResponseCode()<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream bout=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;
            if(in!=null)try(InputStream src=in){while((n=src.read(chunk))!=-1){bout.write(chunk,0,n);if(bout.size()>2000000)return LocalCore.fail("ai-response-too-large");}}
            JSONObject response=new JSONObject(bout.toString("UTF-8"));if(c.getResponseCode()>=400)return LocalCore.obj("ok",false,"status",c.getResponseCode(),"provider",response);
            JSONArray choices=response.optJSONArray("choices");String text=choices!=null&&choices.length()>0?choices.getJSONObject(0).optJSONObject("message").optString("content"):"";
            return LocalCore.obj("ok",true,"text",text,"model",model,"raw",response);
        }finally{c.disconnect();}
    }
    private JSONObject models()throws Exception{
        JSONArray out=new JSONArray().put(LocalCore.obj("id","perchance-free","object","model","owned_by","perchance-keyless-service"));
        if(!settings.get("aiEndpoint","").isEmpty()&&!settings.get("aiModel","").isEmpty())out.put(LocalCore.obj("id",settings.get("aiModel",""),"object","model","owned_by","user-configured"));
        out.put(LocalCore.obj("id","moltbook-team","object","model","owned_by","moltbook-agents"));
        return LocalCore.obj("object","list","data",out);
    }
    private JSONObject completion(JSONObject q,boolean anthropic)throws Exception{
        String model=q.optString("model");if(model.equals("moltbook-team")||model.startsWith("moltbook-team/"))return teamCompletion(q,anthropic);
        JSONArray msgs=q.optJSONArray("messages");StringBuilder prompt=new StringBuilder();if(q.has("system"))prompt.append(q.optString("system")).append("\n");if(msgs!=null)for(int i=0;i<msgs.length();i++){JSONObject msg=msgs.optJSONObject(i);if(msg!=null)prompt.append(msg.optString("role")).append(": ").append(msg.optString("content")).append("\n");}
        JSONObject input=LocalCore.obj("prompt",prompt.toString(),"maxTokens",q.optInt("max_tokens",500));
        JSONObject result="perchance-free".equals(model)?PerchanceInference.generate(context,prompt.toString(),q.optInt("max_tokens",500)):ai(input);
        if(!result.optBoolean("ok"))return result;
        String text=result.optString("text");if(anthropic)return LocalCore.obj("id",LocalCore.id("msg"),"type","message","role","assistant","model",model,"content",new JSONArray().put(LocalCore.obj("type","text","text",text)),"stop_reason","end_turn","usage",LocalCore.obj("input_tokens",0,"output_tokens",0));
        return LocalCore.obj("id",LocalCore.id("chatcmpl"),"object","chat.completion","model",model,"choices",new JSONArray().put(LocalCore.obj("index",0,"message",LocalCore.obj("role","assistant","content",text),"finish_reason","stop")),"usage",LocalCore.obj("prompt_tokens",0,"completion_tokens",0,"total_tokens",0));
    }
    private JSONObject teamCompletion(JSONObject q,boolean anthropic)throws Exception{
        String model=q.optString("model"),agent=model.startsWith("moltbook-team/")?model.substring("moltbook-team/".length()):"";
        JSONArray messages=q.optJSONArray("messages");StringBuilder prompt=new StringBuilder();
        if(q.has("system"))prompt.append(q.optString("system")).append("\n");
        if(messages!=null)for(int i=0;i<messages.length();i++){
            JSONObject message=messages.optJSONObject(i);if(message!=null){String content=message.optString("content");
                if(message.opt("content") instanceof JSONArray){StringBuilder parts=new StringBuilder();JSONArray array=message.optJSONArray("content");for(int j=0;j<array.length();j++){JSONObject item=array.optJSONObject(j);if(item!=null)parts.append(item.optString("text"));}content=parts.toString();}
                prompt.append(message.optString("role")).append(": ").append(content).append("\n");
            }
        }
        JSONObject delivery=moltbook.ask(LocalCore.obj("optIn",true,"prompt",prompt.toString(),"agent",agent));
        if(!delivery.optBoolean("ok"))return delivery;
        String job=delivery.optString("jobId");
        // A cached answer is already a finished result. Polling the old job could
        // hit the 24-hour retention limit or needlessly contact Moltbook.
        JSONObject polled=delivery.optBoolean("cached")&&delivery.optJSONArray("replies")!=null
            ?delivery:null;
        long deadline=System.currentTimeMillis()+Math.max(60000,Math.min(300000,db.readState("team-route",LocalCore.obj("waitSec",120)).optInt("waitSec",120)*1000L));
        while(polled==null&&System.currentTimeMillis()<deadline){
            polled=moltbook.poll(job);if(!polled.optBoolean("ok"))return polled;
            if(polled.optJSONArray("replies")!=null&&polled.optJSONArray("replies").length()>0&&polled.optBoolean("done"))break;
            if(polled.optBoolean("done"))return LocalCore.obj("ok",false,"err","agent-timeout","jobId",job,"receipt",delivery.opt("receipt"));
            polled=null;
            try{Thread.sleep(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return LocalCore.fail("request-cancelled");}
        }
        if(polled==null||polled.optJSONArray("replies")==null||polled.optJSONArray("replies").length()==0)return LocalCore.obj("ok",false,"err","agent-no-verified-answer","jobId",job);
        JSONArray replies=polled.getJSONArray("replies");JSONArray choices=new JSONArray();
        for(int i=0;i<replies.length();i++){
            JSONObject reply=replies.optJSONObject(i);if(reply!=null)choices.put(LocalCore.obj("index",i,"message",LocalCore.obj("role","assistant","content",reply.optString("text")),"finish_reason","stop","moltbook",reply.optJSONObject("envelope")));
        }
        if(anthropic)return LocalCore.obj("id",LocalCore.id("msg"),"type","message","role","assistant","model",model,"content",new JSONArray().put(LocalCore.obj("type","text","text",replies.getJSONObject(0).optString("text"))),"stop_reason","end_turn","moltbook",LocalCore.obj("all",replies,"jobId",job,"receipt",delivery.opt("receipt")));
        return LocalCore.obj("id",LocalCore.id("chatcmpl"),"object","chat.completion","model",model,"choices",choices,"moltbook",LocalCore.obj("all",replies,"jobId",job,"receipt",delivery.opt("receipt"),"cached",delivery.optBoolean("cached")),"usage",LocalCore.obj("prompt_tokens",0,"completion_tokens",0,"total_tokens",0));
    }
    /** Return the complete recovered Bridge v16 capability specification, never an abbreviated substitute. */
    private JSONObject fullBridgeCapability()throws Exception{
        try(InputStream input=context.getAssets().open("bridge-capability.md");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] block=new byte[8192];int count;
            while((count=input.read(block))!=-1){output.write(block,0,count);if(output.size()>100000)throw new IOException("capability file exceeds contract limit");}
            String doc=new String(output.toByteArray(),StandardCharsets.UTF_8);
            if(!doc.contains("bridge-capability/16"))return LocalCore.fail("bridge-capability-version-mismatch");
            return LocalCore.obj("ok",true,"version",16,"capability",doc,"sha256",LocalCore.digest(doc),"implementationParityVerified",false,"contractSource","recovered-original");
        }
    }
    private JSONObject relay(JSONObject q)throws Exception{
        JSONObject p=q.optJSONObject("payload");if(p==null)p=q;String op=p.optString("op"),agent=p.optString("agent");JSONObject result;
        switch(op){
            case "team-status":result=LocalCore.obj("ok",true,"jobs",db.records("jobs",100).getJSONArray("items"),"session","android-embedded");break;
            case "team-dispatch":result=moltbook.dispatch(p);break;
            case "team-claim":result=db.claim(p.optString("jobId"),p,false);break;
            case "team-submit":result=db.claim(p.optString("jobId"),p,true);break;
            case "team-capability":result=fullBridgeCapability();break;
            case "race-create":result=moltbook.raceCreate(p);break;
            case "race-list":result=moltbook.raceList();break;
            case "race-send":result=moltbook.raceSend(p);break;
            case "race-collect":result=moltbook.raceCollect(p.optString("raceId"));break;
            case "race-document":result=moltbook.raceDocument(p.optString("raceId"));break;
            case "roster-discover":result=moltbook.raceRoster(p.optString("introPostId"));break;

            case "mesh":String act=p.optString("action");switch(act){case "send":result=db.send(p);break;case "inbox":result=db.inbox(p.optString("nick"),p.optBoolean("markRead"));break;case "talk":result=db.topic(p.optString("topic"),50);break;case "comment":result=db.comment(p.optString("topic"),p);break;case "beat":result=db.beat(p);break;case "presence":result=db.presence();break;default:result=LocalCore.fail("unknown-mesh-action");}break;
            case "watch":result=moltbook.watch(p);break;
            case "inbox":result=moltbook.watchedInbox(p);break;
            case "moltbook":String action=p.optString("action");if(action.equals("route"))result=moltbook.routeStatus();else if(action.equals("ask"))result=moltbook.ask(p);else if(action.equals("poll"))result=moltbook.poll(p.optString("jobId"));else result=moltbook.operate(p);break;
            case "agent":result=moltbook.agent(p);break;
            case "reach":result=moltbook.reach(p);break;
            case "reach-many":result=moltbook.reachMany(p);break;
            case "character-agents":{
                String charAction=p.optString("action");
                if(charAction.equals("list"))result=agents.agents();
                else if(charAction.equals("profile"))result=agents.profileSummary(p.optString("agent"));
                else if(charAction.equals("threads"))result=agents.threads();
                else if(charAction.equals("create-thread"))result=agents.createThread(p);
                else if(charAction.equals("send"))result=agents.append(p.optString("threadId"),"user","user",p.optString("text"));
                else if(charAction.equals("history"))result=agents.messages(p.optString("threadId"));
                else if(charAction.equals("turn"))result=!"true".equals(settings.get("remote-agent-turns","false"))?LocalCore.fail("remote-agent-turns-disabled"):agents.generateTurn(p.optString("threadId"),p.optString("agent"),p.optBoolean("optIn"));
                else result=LocalCore.fail("unknown-character-action");
                break;
            }
            case "ping":result=LocalCore.obj("type","touchstone-bridge.pong","ts",System.currentTimeMillis(),"session","android-embedded");break;
            default:result=LocalCore.fail("unknown-op");
        }
        return LocalCore.obj("ok",result.optBoolean("ok",true),"sessionId","android-embedded","response",result);
    }
    private JSONObject tagline(String id)throws Exception{JSONObject row=db.getRecord("ads",id);if(!row.optBoolean("ok"))return row;JSONObject r=row.getJSONObject("record");JSONObject generated=ai(LocalCore.obj("prompt","Write a concise marketing tagline for "+r.optString("pitch"),"maxTokens",100));if(!generated.optBoolean("ok"))return generated;return db.updateRecord("ads",id,LocalCore.obj("version",r.optInt("version"),"changes",LocalCore.obj("tagline",generated.optString("text"))));}

    private final class Peer {
        final Socket socket;final InputStream input;final OutputStream output;
        volatile String room="",nick="molty";volatile long lastEdit,lastSave,lastVote;volatile boolean connected=true;
        Peer(Socket s)throws IOException{socket=s;input=s.getInputStream();output=s.getOutputStream();}
        synchronized void frame(int op,byte[] data)throws IOException{if(!connected)return;ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(128|op);int size=data.length;if(size<126)b.write(size);else if(size<65536){b.write(126);b.write(size>>8);b.write(size&255);}else{b.write(127);for(int i=7;i>=0;i--)b.write((int)(((long)size)>>(i*8))&255);}b.write(data);output.write(b.toByteArray());output.flush();}
        void send(JSONObject message){try{frame(1,message.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception e){close();}}
        void close(){connected=false;try{socket.close();}catch(Exception ignored){}peers.remove(this);}
    }
    private void upgrade(InputStream in,OutputStream out,Map<String,String> h,Socket s)throws Exception{
        String key=h.get("sec-websocket-key");if(key==null||!"13".equals(h.get("sec-websocket-version"))){reply(out,400,LocalCore.fail("bad-websocket-handshake"));return;}
        byte[] challenge=MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
        String accept=Base64.encodeToString(challenge,Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
        Peer p=new Peer(s);peers.add(p);s.setSoTimeout(90000);
        try{while(active&&p.connected){int a=in.read(),b=in.read();if(a<0||b<0)break;int opcode=a&15;if((a&112)!=0)throw new IOException("reserved-frame-bits");boolean masked=(b&128)!=0;if(!masked)throw new IOException("client-frame-not-masked");long length=b&127;
                if(length==126)length=((in.read()&255)<<8)|(in.read()&255);
                if(length==127){length=0;for(int i=0;i<8;i++)length=(length<<8)|(in.read()&255);}
                if(length>300000)throw new IOException("oversized-frame");byte[] mask=readBody(in,4),payload=readBody(in,(int)length);for(int i=0;i<payload.length;i++)payload[i]^=mask[i%4];
                if(opcode==8)break;if(opcode==9){p.frame(10,payload);continue;}if(opcode==10)continue;
                if(opcode!=1||(a&128)==0)throw new IOException("unsupported-frame");JSONObject msg=new JSONObject(new String(payload,StandardCharsets.UTF_8));onSocket(p,msg);
            }
        }finally{leave(p);p.close();}
    }
    private JSONArray who(String room){JSONArray names=new JSONArray();synchronized(peers){for(Peer x:peers)if(x.room.equals(room))names.put(x.nick);}return names;}
    private void broadcast(String room,JSONObject msg){synchronized(peers){for(Peer p:peers)if(p.room.equals(room))p.send(msg);}}
    private void leave(Peer p)throws Exception{if(p.room.isEmpty())return;String room=p.room;p.room="";broadcast(room,LocalCore.obj("t","presence","event","leave","nick",p.nick,"who",who(room)));}
    private void onSocket(Peer p,JSONObject msg)throws Exception{
        String type=msg.optString("t");long now=System.currentTimeMillis();
        if(type.equals("join")){leave(p);String room=LocalCore.cleanRoom(msg.optString("room"));if(room.isEmpty()){p.send(LocalCore.obj("t","error","err","bad-room"));return;}
            p.room=room;p.nick=LocalCore.nick(msg.optString("nick"));JSONObject doc=db.loadRoom(room);
            p.send(LocalCore.obj("t","doc","room",room,"html",doc.optString("html"),"updatedAt",doc.optLong("updatedAt"),"who",who(room)));
            broadcast(room,LocalCore.obj("t","presence","event","join","nick",p.nick,"who",who(room)));return;
        }
        if(type.equals("leave")){leave(p);return;}
        if(type.equals("edit")&&!p.room.isEmpty()){if(now-p.lastEdit<300||msg.optString("html").length()>40000)return;p.lastEdit=now;broadcast(p.room,LocalCore.obj("t","edit","nick",p.nick,"mid",msg.optString("mid"),"html",msg.optString("html")));return;}
        if(type.equals("rpc")){String method=msg.optString("method");JSONObject q=msg.optJSONObject("data");if(q==null)q=new JSONObject();JSONObject r;
            switch(method){case "rooms":r=db.listRooms();break;case "load":r=db.loadRoom(q.optString("room"));break;
                case "save":if(now-p.lastSave<2000)r=LocalCore.fail("slow-down");else{p.lastSave=now;r=db.saveRoom(q.optString("room"),q.optString("html"));if(r.optBoolean("ok"))broadcast(p.room,LocalCore.obj("t","saved","nick",p.nick,"room",p.room));}break;
                case "who":r=LocalCore.obj("ok",true,"room",q.optString("room"),"who",who(q.optString("room")));break;
                case "features":r=db.votes();break;
                case "vote":if(now-p.lastVote<5000)r=LocalCore.fail("slow-down");else{r=db.vote(q);if(r.optBoolean("ok")&&!r.optBoolean("dup"))p.lastVote=now;}break;
                default:r=LocalCore.fail("unknown-rpc");
            }
            p.send(LocalCore.obj("t","rpc_result","requestId",msg.opt("requestId"),"result",r));
        }
    }
}
