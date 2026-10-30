package org.canceledagent.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Executable isolated Android SQLite + HTTP + WebSocket integration tests. Never modifies live user data. */
public final class DeviceIntegrationTests {
    private DeviceIntegrationTests(){}
    private interface Probe { void check() throws Exception; }
    private static final String DB_NAME="independent-parity-tests.db";
    private static JSONObject report;private static JSONArray results;private static int passed,failed;
    private static LocalCore db;private static EmbeddedServer server;private static Context ctx;
    private static void ensure(boolean good,String reason){if(!good)throw new AssertionError(reason);}
    private static void test(String name,Probe p){try{p.check();passed++;results.put(item(name,true,""));}
        catch(Throwable t){failed++;results.put(item(name,false,t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage())));}}
    private static JSONObject item(String name,boolean ok,String detail){JSONObject j=new JSONObject();try{j.put("name",name).put("passed",ok).put("detail",detail);}catch(Exception ignored){}return j;}
    private static JSONObject j(Object...args)throws Exception{return LocalCore.obj(args);}
    private static String makeRoom(String n){return "parity-"+n;}
    private static JSONObject http(String method,String url,String token,JSONObject body)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();connection.setConnectTimeout(4000);connection.setReadTimeout(5000);connection.setRequestMethod(method);
        if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
        if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");try(OutputStream w=connection.getOutputStream()){w.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
        int status=connection.getResponseCode();InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();String raw="";
        if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buf=new byte[2048];int c;while((c=in.read(buf))!=-1)output.write(buf,0,c);raw=new String(output.toByteArray(),StandardCharsets.UTF_8);}
        connection.disconnect();return j("status",status,"response",raw.isEmpty()?new JSONObject():new JSONObject(raw));
    }
    private static String line(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();int a;while((a=in.read())!=-1){if(a==10)break;if(a!=13)out.write(a);if(out.size()>16000)throw new IOException("line-too-long");}return out.toString("UTF-8");}
    private static void websocket()throws Exception{
        String token=RuntimeSecrets.ensure(ctx);Socket conn=new Socket("127.0.0.1",server.boundPort());conn.setSoTimeout(5000);
        try{
            InputStream input=conn.getInputStream();OutputStream output=conn.getOutputStream();String key="dGhlIHNhbXBsZSBub25jZQ==";
            String request="GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer "+token+"\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));output.flush();String status=line(input);ensure(status.contains("101"),"websocket handshake status "+status);
            String header;boolean digest=false;while(!(header=line(input)).isEmpty()){if(header.toLowerCase().startsWith("sec-websocket-accept:"))digest=true;}
            ensure(digest,"missing authenticated handshake digest");
            byte[] payload=j("t","join","room","testroom","nick","device-test").toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame=new ByteArrayOutputStream();frame.write(0x81);if(payload.length<126)frame.write(0x80|payload.length);else{frame.write(0xfe);frame.write(payload.length>>>8);frame.write(payload.length&255);}
            byte[] mask={3,2,1,0};frame.write(mask);for(int i=0;i<payload.length;i++)frame.write(payload[i]^mask[i%4]);output.write(frame.toByteArray());output.flush();
            int a=input.read(),b=input.read();ensure((a&15)==1,"server did not return text frame");long size=b&127;
            if(size==126)size=(input.read()<<8)|input.read();if(size==127){size=0;for(int i=0;i<8;i++)size=(size<<8)|(input.read()&255);}
            ensure(size>0&&size<=300000,"invalid server frame length");byte[] bytes=new byte[(int)size];int offset=0;
            while(offset<bytes.length){int r=input.read(bytes,offset,bytes.length-offset);if(r<0)throw new EOFException();offset+=r;}
            JSONObject result=new JSONObject(new String(bytes,StandardCharsets.UTF_8));ensure("doc".equals(result.optString("t")),"expected room document");ensure("testroom".equals(result.optString("room")),"room identity mismatch");
        }finally{conn.close();}
    }
    public static synchronized JSONObject run(Context context){
        ctx=context.getApplicationContext();passed=0;failed=0;results=new JSONArray();report=new JSONObject();String suffix=UUID.randomUUID().toString();long started=System.currentTimeMillis();
        try{
            // The tests use their own database and ephemeral socket; the actual application service remains untouched.
            ctx.deleteDatabase(DB_NAME);db=new LocalCore(ctx,DB_NAME);server=new EmbeddedServer(ctx,db,0);
            test("SQLite schema and table creation",()->ensure(db.catalog().optBoolean("independent"),"catalog failed"));
            test("40-room capacity, persistence and eviction",()->{
                for(int i=0;i<40;i++)ensure(db.saveRoom("room-"+i,"<p>"+i+"</p>").optBoolean("ok"),"failed room "+i);
                ensure(db.listRooms().getJSONArray("rooms").length()==40,"capacity mismatch");
                ensure(db.loadRoom("room-0").optString("html").equals("<p>0</p>"),"not persisted");
                JSONObject eviction=db.saveRoom("room-41","<b>next</b>");ensure(eviction.optBoolean("ok"),"eviction save failed");ensure(!eviction.optString("evicted").isEmpty(),"eviction receipt missing");
                ensure(db.listRooms().getJSONArray("rooms").length()==40,"capacity exceeded");
                ensure(!db.saveRoom("z","bad").optBoolean("ok"),"invalid room accepted");
                StringBuilder oversized=new StringBuilder();for(int i=0;i<40001;i++)oversized.append('x');ensure(!db.saveRoom("valid-room",oversized.toString()).optBoolean("ok"),"oversize document accepted");
            });
            test("Vote totals, donor weighting and re-votes",()->{
                JSONObject first=db.vote(j("feat","editor","nick","agent-a","dir","on","donor",true,"handle","tipid"));ensure(first.optBoolean("ok"),"first ballot rejected");
                JSONObject before=db.votes();JSONArray features=before.getJSONArray("features");int yes=0;for(int i=0;i<features.length();i++)if("editor".equals(features.getJSONObject(i).optString("key")))yes=features.getJSONObject(i).optInt("yes");ensure(yes==2,"donor double weight missing");
                db.vote(j("feat","editor","nick","agent-a","dir","off"));JSONArray after=db.votes().getJSONArray("features");for(int i=0;i<after.length();i++)if("editor".equals(after.getJSONObject(i).optString("key"))){ensure(after.getJSONObject(i).optInt("yes")==0,"re-vote left stale weight");ensure(after.getJSONObject(i).optInt("no")==1,"new vote weight incorrect");}
            });
            test("Versioned registry updates and conflict detection",()->{
                JSONObject item=db.createRecord("ads",j("nick","agent-a","url","https://example.org","price","5"));ensure(item.optBoolean("ok"),"create failed");
                JSONObject row=item.getJSONObject("record");String id=row.getString("id");JSONObject updated=db.updateRecord("ads",id,j("version",row.getInt("version"),"changes",j("pitch","new heading")));
                ensure(updated.optBoolean("ok"),"update failed");ensure(!db.updateRecord("ads",id,j("version",row.getInt("version"),"changes",j("pitch","stale"))).optBoolean("ok"),"stale write accepted");
            });
            test("Fleet transitions and claimant enforcement",()->{
                JSONObject item=db.createRecord("fleet",j("title","A task","brief","A brief"));String id=item.getJSONObject("record").getString("id");
                ensure(!db.fleet(id,j("action","submit","agent","agent-a","result","premature")).optBoolean("ok"),"premature submit accepted");
                ensure(db.fleet(id,j("action","claim","agent","agent-a")).optBoolean("ok"),"claim rejected");
                ensure(!db.fleet(id,j("action","submit","agent","agent-b","result","other")).optBoolean("ok"),"claim theft accepted");
                ensure(db.fleet(id,j("action","submit","agent","agent-a","result","done")).optBoolean("ok"),"submit rejected");
                ensure(db.fleet(id,j("action","accept","agent","lead")).optBoolean("ok"),"accept rejected");
            });
            test("Lab sealed commit/reveal",()->{
                String digest=LocalCore.digest("nonce\nproof");String id=db.labCommit(j("sha256",digest,"note","test")).getString("id");
                ensure(!db.labReveal(id,j("nonce","nonce","proof","wrong")).optBoolean("ok"),"invalid proof accepted");
                ensure(db.labReveal(id,j("nonce","nonce","proof","proof")).optBoolean("ok"),"valid reveal rejected");
            });
            test("Lab oracle history and claimed/unclaimed drift",()->{
                ensure(db.oracleFreeze("contract",j("contract","{\"a\":1,\"b\":2}")).optBoolean("ok"),"freeze failed");
                ensure("STABLE".equals(db.oracleCompare("contract",j("contract","{\"b\":2,\"a\":1}")).optString("status")),"reordered JSON misclassified");
                ensure("UNCLAIMED".equals(db.oracleCompare("contract",j("contract","{\"a\":2}")).optString("status")),"unclaimed drift misclassified");
                ensure("CLAIMED".equals(db.oracleCompare("contract",j("contract","{\"a\":2}","reason","approved")).optString("status")),"claimed drift misclassified");
                ensure(db.oracleHistory("contract").getJSONArray("history").length()==1,"oracle history lost");
            });
            test("Webmesh delivery and read receipts",()->{
                ensure(db.send(j("from","a","to","b","text","hello")).optBoolean("ok"),"send failed");
                ensure(db.inbox("b",false).getJSONArray("messages").length()==1,"inbox empty");
                ensure(db.inbox("b",true).getJSONArray("messages").length()==1,"mark read failed");
                ensure(db.beat(j("nick","b","note","online")).optBoolean("ok"),"presence failed");
            });
            test("Team claims, slots, submissions",()->{
                JSONObject item=db.createRecord("jobs",j("task","Review module","slots",1));String id=item.getJSONObject("record").getString("id");
                ensure(db.claim(id,j("agent","one"),false).optBoolean("ok"),"claim failed");
                ensure(!db.claim(id,j("agent","two"),false).optBoolean("ok"),"slot exceeded");
                ensure(db.claim(id,j("agent","one","result","accepted"),true).optBoolean("ok"),"submission failed");
            });
            test("Bridge race: persisted assignments, missing sections, deny unapproved spend",()->{
                JSONArray targets=new JSONArray().put(j("agent","first-agent","postId","post-one","assignment","Review permissions","targetWords",300))
                    .put(j("agent","second-agent","postId","post-two","assignment","Review reliability","targetWords",250));
                JSONObject created=server.route("POST","/api/workflows/bridge/race",j("brief","Audit permissions and reliability","targets",targets));
                ensure(created.optBoolean("ok"),"race creation rejected");String raceId=created.getJSONObject("race").getString("id");
                JSONObject document=server.route("GET","/api/workflows/bridge/race/"+raceId+"/document",new JSONObject());
                ensure(document.optBoolean("ok"),"race document failed");
                ensure(document.optString("document").contains("Author: first-agent"),"first author absent");
                ensure(document.optString("document").contains("Status: MISSING"),"missing author fabricated");
                JSONObject denied=server.route("POST","/api/workflows/bridge/race/send",j("raceId",raceId,"index",0));
                ensure(!denied.optBoolean("ok")&&denied.optString("err").contains("optIn"),"race delivery bypassed opt-in");
                JSONObject stored=server.route("GET","/api/workflows/bridge/race/"+raceId,new JSONObject());
                ensure(stored.optBoolean("ok")&&stored.getJSONObject("race").getJSONArray("targets").length()==2,"race state not persisted");
                ensure(stored.getJSONObject("race").optInt("spend")==0,"free creation spent money");
            });
            test("Market billboard rotation contracts",()->{
                JSONObject market=db.market();ensure(market.optInt("slots")==3,"slot count wrong");ensure(market.optInt("intervalSeconds")==45,"rotation period wrong");
            });
            test("Isolated Thinking migration, rating, trace chains and snapshot rollback",()->{
                String tmpDb="parity-thinking-"+suffix+".db";
                ctx.deleteDatabase(tmpDb);
                LocalProjects projects=new LocalProjects(ctx,tmpDb);
                try{
                    String p=projects.create("Thinking tests","<h1>Before</h1>");
                    String root=projects.pipe(p,"think","Reasoning started",null,false,"",null);
                    String pivot=projects.pipe(p,"change","Changed heading",root,true,"heading-update","<h1>Before</h1>");
                    projects.rate(p,pivot,-1,"Heading change not accepted");
                    JSONArray trace=projects.detailedTrace(p);
                    ensure(trace.length()==2,"trace entries lost");
                    ensure(trace.getJSONObject(1).optInt("rating")==-1,"pivot rating lost");
                    ensure(trace.getJSONObject(1).optString("parentId").equals(root),"trace chain broken");
                    ensure(projects.feedbackExport(p).contains("TRACE-BACK:"),"feedback chains missing");
                    int version=projects.save(p,"Thinking tests","<h1>After</h1>","source change",1);
                    ensure(version==2,"save revision mismatch");
                    int restored=projects.restoreTraceSnapshot(p,pivot,version);
                    ensure(restored==3,"snapshot restore did not create new revision");
                    ensure(projects.project(p).optString("source").equals("<h1>Before</h1>"),"snapshot incorrect");
                    projects.close();
                    projects=new LocalProjects(ctx,tmpDb);
                    ensure(projects.detailedTrace(p).length()==2,"trace lost after SQLite reopen");
                }finally{projects.close();ctx.deleteDatabase(tmpDb);}
            });
            test("Free AI registration and verification contract without network spend",()->{
                JSONObject ai=server.route("GET","/api/ai/status",new JSONObject());
                ensure(ai.optBoolean("keylessFallbackAvailable"),"missing free AI provider");
                JSONObject models=server.route("GET","/v1/models",new JSONObject());
                JSONArray list=models.getJSONArray("data");boolean available=false;
                for(int i=0;i<list.length();i++)if("perchance-free".equals(list.getJSONObject(i).optString("id")))available=true;
                ensure(available,"Perchance model missing from registry");
                ensure(PerchanceInference.formatInstruction("singleword").endsWith("\u00a0"),"instruction marker missing");
            });
            test("Write-gate defaults deny autonomous paid operations",()->{
                ensure(!db.autonomy().optBoolean("writesAuthorized"),"writes enabled by default");
            });
            server.start(false);
            String root="http://127.0.0.1:"+server.boundPort();String token=RuntimeSecrets.ensure(ctx);
            test("Authenticated HTTP transport and unauthorized rejection",()->{
                JSONObject unauth=http("GET",root+"/api/votes",null,null);ensure(unauth.optInt("status")==401,"unauthorized read allowed");
                JSONObject authorized=http("GET",root+"/api/votes",token,null);ensure(authorized.optInt("status")==200&&authorized.getJSONObject("response").optBoolean("ok"),"authenticated request failed");
            });
            test("HTTP-to-SQLite read/write integration",()->{
                JSONObject create=http("POST",root+"/api/workflows/records/polls",token,j("question","Is it integrated?"));
                ensure(create.optInt("status")==200,"create HTTP rejected: "+create);
                JSONObject list=http("GET",root+"/api/workflows/records/polls",token,null);ensure(list.getJSONObject("response").getJSONArray("items").length()>0,"saved record missing over HTTP");
            });
            test("Complete Bridge v16 capability served via app assets",()->{
                JSONObject r=http("POST",root+"/api/bridge/relay",token,j("payload",j("op","team-capability")));
                ensure(r.optInt("status")==200,"route error");JSONObject inner=r.getJSONObject("response").getJSONObject("response");
                ensure(inner.optInt("version")==16,"version mismatch");String doc=inner.optString("capability");ensure(doc.length()>20000&&doc.contains("bridge-capability/16"),"truncated contract");
                ensure(LocalCore.digest(doc).equals(inner.optString("sha256")),"contract digest mismatch");
            });
            test("Character registry seeded with supplied four identities",()->{
                JSONObject r=http("GET",root+"/api/agents",token,null);
                ensure(r.optInt("status")==200,"agent HTTP status failed");
                JSONArray list=r.getJSONObject("response").getJSONArray("agents");
                ensure(list.length()>=4,"not all characters present");
                for(String id:new String[]{"mk-naomi","mk-often","mk-ultra","mina"}){
                    boolean found=false;
                    for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).optString("id")))found=true;
                    ensure(found,"missing "+id);
                }
            });
            test("Character share URL validation rejects foreign hosts",()->{
                String valid=AgentRuntime.cloudFile("https://perchance.org/ai-character-chat?data=Mk_Naomi~cc2ba375eac0f4a9022c27fb4177f318.gz");
                ensure(valid.equals("https://user.uploads.dev/file/cc2ba375eac0f4a9022c27fb4177f318.gz"),"wrong asset resolution");
                boolean rejected=false;
                try{AgentRuntime.cloudFile("https://example.org/ai-character-chat?data=Test~cc2ba375eac0f4a9022c27fb4177f318.gz");}catch(IllegalArgumentException expected){rejected=true;}
                ensure(rejected,"unsafe host allowed");
            });
            test("Character Bridge operations retain owner auth",()->{
                JSONObject unauth=http("GET",root+"/api/agents",null,null);ensure(unauth.optInt("status")==401,"unauthenticated profile registry allowed");
                JSONObject bridge=http("POST",root+"/api/bridge/relay",token,j("payload",j("op","character-agents","action","list")));
                ensure(bridge.optInt("status")==200,"Bridge character operation blocked");
                ensure(bridge.getJSONObject("response").getJSONObject("response").getJSONArray("agents").length()>=4,"Bridge registry empty");
            });
            test("Authenticated WebSocket room join and document response",DeviceIntegrationTests::websocket);
        }catch(Throwable error){test("Integration harness setup",()->{throw new AssertionError(error);});}
        finally{if(server!=null)server.stop();if(db!=null)db.close();ctx.deleteDatabase(DB_NAME);}
        try{report.put("schema","canceled-agent/device-integration/v1").put("isolated",true).put("paidNetworkWrites",false).put("startedAt",started).put("completedAt",System.currentTimeMillis()).put("passed",passed).put("failed",failed).put("tests",results);}catch(Exception e){throw new IllegalStateException(e);}
        return report;
    }
}
