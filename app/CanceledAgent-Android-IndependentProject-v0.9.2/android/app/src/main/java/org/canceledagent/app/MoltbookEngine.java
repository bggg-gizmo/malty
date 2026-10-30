package org.canceledagent.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/** Device-owned Moltbook API client + opted-in, governor-accounted agent delegation. */
public final class MoltbookEngine {
    private static final String BASE="https://www.moltbook.com/api/v1";
    private final SecurePrefs secrets;private final LocalCore db;private final HeadlessSolver solver;
    private final Context context;
    public MoltbookEngine(Context context,LocalCore db){this.context=context;this.db=db;secrets=new SecurePrefs(context);solver=new HeadlessSolver(context);}
    private static String encode(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return "";}}
    private static String utc(){SimpleDateFormat d=new SimpleDateFormat("yyyy-MM-dd",Locale.US);d.setTimeZone(TimeZone.getTimeZone("UTC"));return d.format(new Date());}
    private static String safeAgent(String s){return (s==null?"":s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]","");}
    private JSONObject req(String method,String path,JSONObject body)throws Exception{
        String key=secrets.readSecret("moltbook");if(key.trim().isEmpty())return LocalCore.fail("moltbook-key-not-configured");
        if(!path.startsWith("/")||path.startsWith("//")||path.contains("\\")||path.contains(".."))return LocalCore.fail("unsafe-api-path");
        URL url=new URL(BASE+path);if(!"https".equals(url.getProtocol())||!"www.moltbook.com".equals(url.getHost()))return LocalCore.fail("credential-host-mismatch");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(30000);
        c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");c.setRequestProperty("Authorization","Bearer "+key);
        try{
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();InputStream stream=code>=400?c.getErrorStream():c.getInputStream();byte[] bytes=new byte[0];if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1){out.write(chunk,0,n);if(out.size()>2000000)return LocalCore.fail("moltbook-response-too-large");}bytes=out.toByteArray();}
            JSONObject data;try{data=new JSONObject(new String(bytes,StandardCharsets.UTF_8));}catch(Exception e){data=LocalCore.obj("raw",new String(bytes,StandardCharsets.UTF_8));}
            return LocalCore.obj("ok",code>=200&&code<300,"status",code,"json",data);
        }finally{c.disconnect();}
    }
    JSONObject call(String method,String path,JSONObject body)throws Exception{return req(method,path,body);}
    JSONObject verifiedComment(String post,String content)throws Exception{return verified(post,content);}
    JSONObject verifiedComment(String post,String content,String parentId)throws Exception{return verified(post,content,parentId);}
    public JSONObject operate(JSONObject q)throws Exception{
        String action=q.optString("action"),method="GET",path="";
        switch(action){
            case "status":path="/agents/status";break;case "me":path="/agents/me";break;case "home":path="/home";break;
            case "feed":path="/feed";break;case "posts":path="/posts";break;case "notifications":path="/notifications";break;
            case "submolts":path="/submolts";break;case "search":path="/search";break;
            case "profile":path="/agents/profile";break;
            case "post":path="/posts/"+encode(q.optString("id"));break;
            case "comments":path="/posts/"+encode(q.optString("postId"))+"/comments";break;
            case "upvote-post":method="POST";path="/posts/"+encode(q.optString("id"))+"/upvote";break;
            case "upvote-comment":method="POST";path="/comments/"+encode(q.optString("id"))+"/upvote";break;
            case "follow":method="POST";path="/agents/"+encode(q.optString("name"))+"/follow";break;
            case "read-all":method="POST";path="/notifications/read-all";break;
            case "read-by-post":method="POST";path="/notifications/read-by-post/"+encode(q.optString("postId"));break;
            default:return LocalCore.fail("unknown-action");
        }
        boolean write=method.equals("POST");
        if(write){JSONObject limit=db.readState("free-write",LocalCore.obj("at",0));if(LocalCore.now()-limit.optLong("at")<5000)return LocalCore.fail("free-write-throttled");}
        if(!write){StringBuilder qs=new StringBuilder();for(String k:new String[]{"sort","limit","filter","submolt","type","q","name"})if(q.has(k)){qs.append(qs.length()==0?'?':'&').append(k).append('=').append(encode(q.optString(k)));}path+=qs;}
        JSONObject result=req(method,path,write?new JSONObject():null);
        if(write&&result.optBoolean("ok"))db.saveState("free-write",LocalCore.obj("at",LocalCore.now()));return result;
    }
    private JSONObject route()throws Exception{return db.readState("team-route",LocalCore.obj("enabled",false,"postId","","roster",new JSONArray(),"waitSec",120,"dailyCap",10,"quorum",1,"cacheTtlMin",0));}
    private JSONObject spent()throws Exception{JSONObject s=db.readState("route-budget",LocalCore.obj("day",utc(),"count",0,"lastAt",0));if(!utc().equals(s.optString("day"))){s=LocalCore.obj("day",utc(),"count",0,"lastAt",0);db.saveState("route-budget",s);}return s;}
    public JSONObject routeStatus()throws Exception{return LocalCore.obj("ok",true,"route",route(),"spentToday",spent().optInt("count"),"keyConfigured",!secrets.readSecret("moltbook").isEmpty(),"governor",db.governor(),"cache",db.readState("route-cache",new JSONObject()),"responderStats",db.readState("responder-stats",new JSONObject()));}
    public JSONObject routeCheck()throws Exception{
        JSONObject cfg=route(),budget=spent(),gov=db.governor();JSONArray errors=new JSONArray();
        if(!cfg.optBoolean("enabled"))errors.put("route-disabled");if(cfg.optString("postId").isEmpty())errors.put("delegation-post-required");
        if(secrets.readSecret("moltbook").isEmpty())errors.put("moltbook-key-required");
        if(budget.optInt("count")>=cfg.optInt("dailyCap",10))errors.put("daily-route-cap");
        if(LocalCore.now()-budget.optLong("lastAt")<60000)errors.put("cooldown");
        if(gov.optInt("remaining")<=gov.optInt("reserve",2))errors.put("governor-reserve");
        JSONObject last=db.readState("last-create",LocalCore.obj("at",0));if(LocalCore.now()-last.optLong("at")<70000)errors.put("governor-create-spacing");
        return LocalCore.obj("ok",errors.length()==0,"dry",true,"checks",errors,"postId",cfg.optString("postId"),"quorum",cfg.optInt("quorum",1),"waitSec",cfg.optInt("waitSec",120));
    }
    private static JSONArray items(JSONObject j){if(j==null)return new JSONArray();for(String k:new String[]{"comments","data","items"}){JSONArray a=j.optJSONArray(k);if(a!=null)return a;}return new JSONArray();}
    private static String author(JSONObject c){JSONObject a=c.optJSONObject("author");return c.optString("authorName",c.optString("author_name",a==null?"":a.optString("name")));}
    private static JSONArray flatten(JSONArray input)throws Exception{JSONArray result=new JSONArray();for(int i=0;i<input.length();i++){JSONObject item=input.optJSONObject(i);if(item!=null)walk(item,item.optString("parent_id",item.optString("parentId")),result,0);}return result;}
    private static void walk(JSONObject c,String parent,JSONArray out,int depth)throws Exception{if(depth>25||out.length()>400)return;out.put(LocalCore.obj("comment",c,"parent",parent));JSONArray replies=c.optJSONArray("replies");if(replies!=null)for(int i=0;i<replies.length();i++){JSONObject child=replies.optJSONObject(i);if(child!=null)walk(child,c.optString("id"),out,depth+1);}}
    private JSONObject verified(String post,String content)throws Exception{return verified(post,content,"");}
    private JSONObject verified(String post,String content,String parentId)throws Exception{
        JSONObject payload=LocalCore.obj("content",content);
        if(parentId!=null&&!parentId.isEmpty())payload.put("parent_id",parentId);
        JSONObject created=req("POST","/posts/"+encode(post)+"/comments",payload);
        if(!created.optBoolean("ok"))return LocalCore.obj("ok",false,"err","create-failed","receipt",created);
        JSONObject root=created.optJSONObject("json");if(root==null)return LocalCore.obj("ok",false,"err","create-receipt-unknown","pending",true);
        JSONObject comment=root.optJSONObject("comment");if(comment==null)comment=root.optJSONObject("data");
        String id=comment==null?root.optString("id"):comment.optString("id");
        if(id.isEmpty())return LocalCore.obj("ok",false,"err","create-receipt-unknown","receipt",root,"pending",true);
        JSONObject check=comment==null?null:comment.optJSONObject("verification");if(check==null)check=root.optJSONObject("verification");
        if(check==null&&!root.optBoolean("verification_required"))
            return LocalCore.obj("ok",true,"commentId",id,"postId",post,"parentId",parentId,"verification",false,"spendRecorded",true,"dailyRemaining",root.opt("daily_remaining"));
        if(check==null||check.optString("verification_code").isEmpty())
            return LocalCore.obj("ok",false,"err","verification-receipt-missing","commentId",id,"pending",true);
        JSONObject answer;
        try{answer=solver.solve(check.optString("challenge_text"));}catch(Exception ex){return LocalCore.obj("ok",false,"err","solver-unavailable","reason",ex.getMessage(),"commentId",id,"pending",true);}
        if(!answer.optBoolean("sure"))return LocalCore.obj("ok",false,"err","solver-unsure","reason",answer.optString("reason"),"commentId",id,"pending",true);
        JSONObject verify=req("POST","/verify",LocalCore.obj("verification_code",check.optString("verification_code"),"answer",answer.optString("answer")));
        if(!verify.optBoolean("ok"))return LocalCore.obj("ok",false,"err","verification-failed","commentId",id,"pending",true,"verification",verify);
        return LocalCore.obj("ok",true,"commentId",id,"postId",post,"parentId",parentId,"verification",true,"spendRecorded",true,"dailyRemaining",root.opt("daily_remaining"));
    }
    /** Explicitly approved human publishing using the same deterministic challenge solver and reserve lock as agent delegation. */
    public synchronized JSONObject publish(JSONObject q)throws Exception {
        if(!q.optBoolean("optIn"))return LocalCore.fail("manual-publish-approval-required");
        String kind=q.optString("kind");JSONObject budget=canSpend();if(!budget.optBoolean("ok"))return budget;
        String title=q.optString("title").trim(),content=q.optString("content").trim();
        if(content.isEmpty()||content.length()>8000)return LocalCore.fail("invalid-publish-content");
        if(kind.equals("reply")){
            String post=q.optString("postId").trim();if(post.isEmpty())return LocalCore.fail("post-id-required");
            chargePaid();JSONObject receipt=verified(post,content,q.optString("parentId",q.optString("parent_id")));
            return LocalCore.obj("ok",receipt.optBoolean("ok"),"kind",kind,"receipt",receipt,"writeAttempted",true,"doNotBlindRetry",true);
        }
        if(kind.equals("post")){
            String sub=q.optString("submolt_name").trim();if(sub.isEmpty()||title.isEmpty()||title.length()>300)return LocalCore.fail("title-and-submolt-required");
            chargePaid();JSONObject sent=req("POST","/posts",LocalCore.obj("submolt_name",sub,"title",title,"content",content));
            if(!sent.optBoolean("ok"))return LocalCore.obj("ok",false,"err","create-failed","receipt",sent,"writeAttempted",true,"doNotBlindRetry",true);
            JSONObject response=sent.optJSONObject("json");if(response==null)return LocalCore.obj("ok",false,"err","receipt-unreadable","writeAttempted",true,"doNotBlindRetry",true);
            JSONObject post=response.optJSONObject("post");if(post==null)post=response.optJSONObject("data");
            String id=post==null?response.optString("id"):post.optString("id");
            if(id.isEmpty())return LocalCore.obj("ok",false,"err","post-id-missing","pending",true,"writeAttempted",true,"doNotBlindRetry",true);
            JSONObject challenge=post==null?null:post.optJSONObject("verification");if(challenge==null)challenge=response.optJSONObject("verification");
            if(challenge==null&&!response.optBoolean("verification_required"))return LocalCore.obj("ok",true,"kind",kind,"postId",id,"verification",false,"writeAttempted",true,"doNotBlindRetry",true);
            if(challenge==null||challenge.optString("verification_code").isEmpty())return LocalCore.obj("ok",false,"err","verification-receipt-missing","postId",id,"pending",true,"writeAttempted",true,"doNotBlindRetry",true);
            JSONObject answer;try{answer=solver.solve(challenge.optString("challenge_text"));}
            catch(Exception e){return LocalCore.obj("ok",false,"err","solver-unavailable","reason",e.getMessage(),"postId",id,"pending",true,"doNotBlindRetry",true);}
            if(!answer.optBoolean("sure"))return LocalCore.obj("ok",false,"err","solver-unsure","reason",answer.optString("reason"),"postId",id,"pending",true,"doNotBlindRetry",true);
            JSONObject verified=req("POST","/verify",LocalCore.obj("verification_code",challenge.optString("verification_code"),"answer",answer.optString("answer")));
            return LocalCore.obj("ok",verified.optBoolean("ok"),"kind",kind,"postId",id,"verification",verified,"writeAttempted",true,"doNotBlindRetry",true);
        }
        return LocalCore.fail("unknown-publish-kind");
    }
    public synchronized JSONObject ask(JSONObject q)throws Exception{
        if(!q.optBoolean("optIn"))return LocalCore.fail("paid-delivery-requires-optIn-true");
        String prompt=q.optString("prompt").trim(),agent=safeAgent(q.optString("agent")),expect=q.optString("expect").trim();if(prompt.isEmpty()||prompt.length()>6000)return LocalCore.fail("invalid-prompt");if(expect.length()>200)return LocalCore.fail("expect-too-long");
        JSONObject cfg=route();JSONArray roster=cfg.optJSONArray("roster");if(roster!=null&&roster.length()>0&&!agent.isEmpty()){
            boolean accepted=false;for(int i=0;i<roster.length();i++)if(agent.equals(safeAgent(roster.optString(i))))accepted=true;if(!accepted)return LocalCore.fail("agent-not-allowed");}
        String cacheKey=LocalCore.digest(prompt+"\n"+agent+"\n"+cfg.optString("postId")+"\n"+cfg.optInt("quorum")+"\n"+expect);JSONObject cache=db.readState("route-cache",new JSONObject());JSONObject cached=cache.optJSONObject(cacheKey);
        if(cached!=null&&cached.optLong("expiresAt")>LocalCore.now()&&cfg.optBoolean("enabled"))return LocalCore.obj("ok",true,"cached",true,"jobId",cached.optString("jobId"),"replies",cached.optJSONArray("replies"),"spend",0);
        JSONObject check=routeCheck();if(!check.optBoolean("ok"))return LocalCore.obj("ok",false,"err","route-unavailable","checks",check.optJSONArray("checks"));
        String id=LocalCore.id("mb"),post=cfg.optString("postId");String content="[moltbook-team request "+id+" for "+(agent.isEmpty()?"any qualifying agent":agent)+"]\n"+prompt+"\n\n(reply here; your reply is relayed verbatim to a REST caller. State assumptions and unresolved questions.)";
        // Consume the opportunity BEFORE hitting the paid API: never blind-retry an uncertain receipt.
        JSONObject spent=spent();spent.put("count",spent.optInt("count")+1).put("lastAt",LocalCore.now());db.saveState("route-budget",spent);db.saveState("last-create",LocalCore.obj("at",LocalCore.now()));
        JSONObject receipt=verified(post,content);JSONObject job=LocalCore.obj("id",id,"createdAt",LocalCore.now(),"postId",post,"commentId",receipt.optString("commentId"),"promptHash",cacheKey,"expect",expect,"agent",agent,"roster",roster==null?new JSONArray():roster,"quorum",cfg.optInt("quorum",1),"expiresAt",LocalCore.now()+cfg.optInt("waitSec",120)*1000L,"responses",new JSONArray(),"done",false,"status",receipt.optBoolean("ok")?"pending":receipt.optString("err"),"receipt",receipt);
        db.saveState("route-job-"+id,job);JSONObject index=db.readState("route-jobs",LocalCore.obj("ids",new JSONArray()));JSONArray ids=index.optJSONArray("ids"),updated=new JSONArray().put(id);if(ids!=null)for(int i=0;i<Math.min(199,ids.length());i++)updated.put(ids.optString(i));db.saveState("route-jobs",LocalCore.obj("ids",updated));
        return LocalCore.obj("ok",receipt.optBoolean("ok"),"jobId",id,"receipt",receipt,"spend",1,"retryDelivery",false,"pollUrl","/api/workflows/moltbook/poll/"+id);
    }
    /** Team dispatch with an optional explicitly authorized, governor-accounted Moltbook anchor. */
    public synchronized JSONObject dispatch(JSONObject request)throws Exception{
        String task=request.optString("task").trim();if(task.isEmpty())return LocalCore.fail("task-required");
        int slots=request.optInt("slots",2);if(slots<1||slots>20)return LocalCore.fail("bad-slot-count");
        boolean anchor=request.optBoolean("anchor");String post=request.optString("postId").trim();
        if(anchor){
            if(!request.optBoolean("optIn"))return LocalCore.fail("anchor-explicit-optIn-required");
            if(post.isEmpty())return LocalCore.fail("anchor-postId-required");
            if(task.length()>1600)return LocalCore.fail("anchor-task-too-long");
            JSONObject guard=canSpend();if(!guard.optBoolean("ok"))return guard;
        }
        JSONObject created=db.createRecord("jobs",LocalCore.obj("task",task,"slots",slots,"anchor",false));
        if(!created.optBoolean("ok"))return created;
        JSONObject job=created.getJSONObject("record");
        if(!anchor)return LocalCore.obj("ok",true,"job",job,"anchored",false,"anchorSkipped","not-requested","spend",0);
        // The attempt is recorded before the network request, so uncertain responses never auto-retry.
        chargePaid();
        JSONObject receipt;
        try{receipt=verified(post,"[team-dispatch "+job.getString("id")+"] "+task);}
        catch(Exception ex){receipt=LocalCore.obj("ok",false,"pending",true,"err","anchor-network-uncertain","detail",ex.toString());}
        JSONObject changes=LocalCore.obj("anchorRequested",true,"anchored",receipt.optBoolean("ok"),"anchorReceipt",receipt,
                "anchorPostId",post,"anchorAttemptedAt",LocalCore.now(),"doNotBlindRetry",true);
        JSONObject updated=db.updateRecord("jobs",job.getString("id"),LocalCore.obj("version",job.optInt("version"),"changes",changes,"actor","owner"));
        return LocalCore.obj("ok",receipt.optBoolean("ok"),"job",updated.optJSONObject("record"),"anchorReceipt",receipt,
                "anchorPending",!receipt.optBoolean("ok"),"spend",1,"doNotBlindRetry",true);
    }
    public JSONObject jobs()throws Exception{JSONArray a=new JSONArray(),ids=db.readState("route-jobs",LocalCore.obj("ids",new JSONArray())).optJSONArray("ids");if(ids!=null)for(int i=0;i<ids.length();i++){JSONObject job=db.readState("route-job-"+ids.optString(i),null);if(job!=null)a.put(job);}return LocalCore.obj("ok",true,"jobs",a);}
    public synchronized JSONObject poll(String id)throws Exception{
        JSONObject job=db.readState("route-job-"+id,null);if(job==null)return LocalCore.fail("job-not-found");if(job.has("doneAt")&&LocalCore.now()>job.optLong("doneAt")+86400000)return LocalCore.fail("late-collection-expired");
        if(!Arrays.asList("pending","complete","timeout").contains(job.optString("status")))return LocalCore.obj("ok",false,"err",job.optString("status"),"receipt",job.opt("receipt"));
        JSONObject comments=req("GET","/posts/"+encode(job.optString("postId"))+"/comments?sort=new&limit=100",null);if(!comments.optBoolean("ok"))return LocalCore.fail("comments-unavailable");
        JSONArray flat=flatten(items(comments.optJSONObject("json"))),responses=job.optJSONArray("responses");if(responses==null)responses=new JSONArray();String delivery=job.optString("commentId");Set<String> descendants=new HashSet<>(),existing=new HashSet<>();descendants.add(delivery);
        for(int i=0;i<responses.length();i++)existing.add(responses.optJSONObject(i).optString("commentId"));
        String owner=secrets.get("nick","canceled-perchance-agent");try{JSONObject me=req("GET","/agents/me",null).optJSONObject("json");if(me!=null){JSONObject mine=me.optJSONObject("agent");owner=me.optString("name",mine==null?owner:mine.optString("name",owner));}}catch(Exception ignored){}boolean visible=false;
        for(int i=0;i<flat.length();i++){JSONObject pair=flat.optJSONObject(i),c=pair.optJSONObject("comment");if(c==null)continue;String ident=c.optString("id"),parent=c.optString("parent_id",c.optString("parentId",pair.optString("parent")));
            if(ident.equals(delivery))visible=true;if(!descendants.contains(parent))continue;descendants.add(ident);
            String agent=author(c),normalized=safeAgent(agent);if(existing.contains(ident)||normalized.isEmpty()||normalized.equals(safeAgent(owner))||ident.equals(delivery))continue;
            if(!job.optString("agent").isEmpty()&&!job.optString("agent").equals(normalized))continue;
            JSONArray roster=job.optJSONArray("roster");if(roster!=null&&roster.length()>0){boolean allowed=false;for(int k=0;k<roster.length();k++)if(safeAgent(roster.optString(k)).equals(normalized))allowed=true;if(!allowed)continue;}
            String text=c.optString("content",c.optString("text",c.optString("body"))).trim();if(text.isEmpty()||text.equalsIgnoreCase("deleted comment"))continue;if(text.length()>2000)text=text.substring(0,2000);
            JSONArray unresolved=new JSONArray();java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("[A-Za-z][^.!?]{5,160}\\?").matcher(text);while(matcher.find()&&unresolved.length()<3)unresolved.put(matcher.group());
            String expect=job.optString("expect");boolean mapped=!expect.isEmpty()&&text.contains(expect);long latencyMs=Math.max(0,LocalCore.now()-job.optLong("createdAt"));
            JSONObject envelope=LocalCore.obj("author",agent,"commentId",ident,"postId",job.optString("postId"),"commitment","partial","settled",false,"unresolved",unresolved,"deliveredAt",LocalCore.now());
            JSONObject answer=LocalCore.obj("agent",agent,"commentId",ident,"text",text,"envelope",envelope,"latencyMs",latencyMs);
            if(!expect.isEmpty())answer.put("mapped",mapped);
            responses.put(answer);existing.add(ident);
            recordResponder(agent,latencyMs,!expect.isEmpty(),mapped);
        }
        job.put("deliveryVisible",visible).put("responses",responses);
        if(responses.length()>=job.optInt("quorum",1)){job.put("done",true).put("status","complete").put("doneAt",LocalCore.now());}
        else if(LocalCore.now()>=job.optLong("expiresAt")){job.put("done",true).put("status","timeout");if(!job.has("doneAt"))job.put("doneAt",LocalCore.now());}
        db.saveState("route-job-"+id,job);int ttl=route().optInt("cacheTtlMin");if(ttl>0&&responses.length()>0){JSONObject cache=db.readState("route-cache",new JSONObject());cache.put(job.optString("promptHash"),LocalCore.obj("jobId",id,"replies",responses,"expiresAt",LocalCore.now()+ttl*60000L));db.saveState("route-cache",cache);}
        return LocalCore.obj("ok",true,"jobId",id,"status",job.optString("status"),"done",job.optBoolean("done"),"replies",responses,"deliveryVisible",visible,"quorum",job.optInt("quorum"),"expect",job.optString("expect"),"receipt",job.opt("receipt"),"lateCollectUntil",job.optLong("doneAt",job.optLong("expiresAt"))+86400000L);
    }
    /** Count each newly discovered reply once, including replies collected after timeout. */
    private synchronized void recordResponder(String name,long latency,boolean hasCanary,boolean mapped)throws Exception{
        String key=safeAgent(name);if(key.isEmpty())return;
        JSONObject scores=db.readState("responder-stats",new JSONObject());JSONObject r=scores.optJSONObject(key);
        if(r==null)r=LocalCore.obj("agent",name,"answers",0,"totalLatencyMs",0,"canaryMapped",0,"canaryUnmapped",0,"lastAt",0);
        r.put("answers",r.optInt("answers")+1).put("totalLatencyMs",r.optLong("totalLatencyMs")+latency).put("lastAt",LocalCore.now());
        if(hasCanary)r.put(mapped?"canaryMapped":"canaryUnmapped",r.optInt(mapped?"canaryMapped":"canaryUnmapped")+1);
        r.put("averageLatencyMs",r.optLong("totalLatencyMs")/Math.max(1,r.optInt("answers")));
        scores.put(key,r);db.saveState("responder-stats",scores);
    }
    public JSONObject renew(String id)throws Exception{JSONObject job=db.readState("route-job-"+id,null);if(job==null)return LocalCore.fail("job-not-found");if(job.optBoolean("done"))return LocalCore.fail("job-closed");long time=Math.min(job.optLong("createdAt")+1800000L,Math.max(LocalCore.now(),job.optLong("expiresAt"))+60000);job.put("expiresAt",time);db.saveState("route-job-"+id,job);return LocalCore.obj("ok",true,"jobId",id,"expiresAt",time,"spend",0);}
    public JSONObject cacheClear()throws Exception{JSONObject cache=db.readState("route-cache",new JSONObject());int n=cache.length();db.saveState("route-cache",new JSONObject());return LocalCore.obj("ok",true,"removed",n);}
    private String myName()throws Exception{
        JSONObject info=req("GET","/agents/me",null).optJSONObject("json");if(info==null)return "";
        JSONObject agent=info.optJSONObject("agent");return safeAgent(info.optString("name",agent==null?"":agent.optString("name")));
    }
    private JSONObject ownedComment(String postId,String commentId)throws Exception{
        String owner=myName();if(owner.isEmpty())return null;
        JSONObject data=req("GET","/posts/"+encode(postId)+"/comments?sort=new&limit=100",null);
        if(!data.optBoolean("ok"))return null;
        JSONArray flat=flatten(items(data.optJSONObject("json")));
        for(int i=0;i<flat.length();i++){
            JSONObject c=flat.optJSONObject(i).optJSONObject("comment");if(c!=null&&commentId.equals(c.optString("id"))&&safeAgent(author(c)).equals(owner))return c;
        }return null;
    }
    private JSONObject canSpend()throws Exception{
        JSONObject budget=db.governor();JSONArray checks=new JSONArray();if(budget.optInt("remaining")<=budget.optInt("reserve",2))checks.put("governor-reserve");
        if(LocalCore.now()-db.readState("last-create",LocalCore.obj("at",0)).optLong("at")<70000)checks.put("create-spacing-70s");
        return LocalCore.obj("ok",checks.length()==0,"checks",checks);
    }
    private void chargePaid()throws Exception{
        db.saveState("last-create",LocalCore.obj("at",LocalCore.now()));String day=utc();
        db.getWritableDatabase().execSQL("INSERT INTO gov_events(day,kind,created) VALUES(?,?,?)",new Object[]{day,"create",LocalCore.now()});
    }
    public synchronized JSONObject agent(JSONObject q)throws Exception{
        String agent=safeAgent(q.optString("agent")),action=q.optString("action");if(agent.isEmpty())return LocalCore.fail("agent-required");
        JSONObject bindings=db.readState("agent-bindings",new JSONObject());JSONObject item=bindings.optJSONObject(agent);
        if(action.equals("status"))return LocalCore.obj("ok",true,"agent",agent,"binding",item);
        if(action.equals("unbind")){bindings.remove(agent);db.saveState("agent-bindings",bindings);return LocalCore.obj("ok",true,"agent",agent,"unbound",item!=null);}
        if(action.equals("bind")){
            String post=q.optString("postId"),comment=q.optString("commentId");if(post.isEmpty()||comment.isEmpty())return LocalCore.fail("postId-and-commentId-required");
            JSONObject owned=ownedComment(post,comment);if(owned==null)return LocalCore.fail("comment-not-owned-or-not-visible");
            JSONObject binding=LocalCore.obj("postId",post,"commentId",comment,"mirror",owned.optString("content",owned.optString("text")),"boundAt",LocalCore.now());
            bindings.put(agent,binding);db.saveState("agent-bindings",bindings);return LocalCore.obj("ok",true,"agent",agent,"commentId",comment);
        }
        if(action.equals("create")){
            if(!q.optBoolean("optIn"))return LocalCore.fail("paid-create-optIn-required");
            String post=q.optString("postId"),text=q.optString("text").trim();if(post.isEmpty()||text.isEmpty())return LocalCore.fail("post-and-text-required");
            JSONObject guard=canSpend();if(!guard.optBoolean("ok"))return guard;
            chargePaid();JSONObject receipt=verified(post,trim(text,2000));if(!receipt.optBoolean("ok"))return LocalCore.obj("ok",false,"receipt",receipt,"spent",1,"doNotBlindRetry",true);
            bindings.put(agent,LocalCore.obj("postId",post,"commentId",receipt.optString("commentId"),"mirror",trim(text,2000),"boundAt",LocalCore.now()));db.saveState("agent-bindings",bindings);
            return LocalCore.obj("ok",true,"agent",agent,"receipt",receipt);
        }
        if(action.equals("log")){
            if(item==null)return LocalCore.fail("unbound");String text=q.optString("text").trim();if(text.isEmpty())return LocalCore.fail("text-required");
            String updated=trim(item.optString("mirror")+"\n"+trim(text,1000)+"\n",6000);
            JSONObject patched=req("PATCH","/comments/"+encode(item.optString("commentId")),LocalCore.obj("content",updated));if(!patched.optBoolean("ok"))return LocalCore.obj("ok",false,"err","patch-failed","details",patched);
            item.put("mirror",updated).put("updatedAt",LocalCore.now());db.saveState("agent-bindings",bindings);return LocalCore.obj("ok",true,"agent",agent,"length",updated.length());
        }
        return LocalCore.fail("unknown-agent-operation");
    }
    private static String trim(String value,int max){return value.length()>max?value.substring(0,max):value;}
    public synchronized JSONObject reach(JSONObject q)throws Exception{
        if(!q.optBoolean("optIn"))return LocalCore.fail("paid-reach-optIn-required");
        String agent=safeAgent(q.optString("agent")),post=q.optString("postId"),text=q.optString("text").trim();
        if(agent.isEmpty()||post.isEmpty()||text.isEmpty())return LocalCore.fail("agent-postId-text-required");
        String me=myName();if(me.isEmpty()||agent.equals(me))return LocalCore.fail("target-is-self-or-owner-unavailable");
        JSONObject profile=req("GET","/agents/profile?name="+encode(agent),null);if(!profile.optBoolean("ok"))return LocalCore.fail("target-agent-unavailable");
        JSONObject guard=canSpend();if(!guard.optBoolean("ok"))return guard;
        chargePaid();JSONObject receipt=verified(post,"[reach for "+agent+"]\n"+trim(text,3500));
        return LocalCore.obj("ok",receipt.optBoolean("ok"),"agent",agent,"receipt",receipt,"spent",1,"doNotBlindRetry",true);
    }
    public synchronized JSONObject reachMany(JSONObject q)throws Exception{
        if(!q.optBoolean("optIn"))return LocalCore.fail("paid-reach-many-optIn-required");JSONArray targets=q.optJSONArray("targets");
        if(targets==null||targets.length()<1||targets.length()>5)return LocalCore.fail("targets-must-be-1-to-5");
        JSONArray results=new JSONArray();for(int i=0;i<targets.length();i++){
            JSONObject one=targets.optJSONObject(i);if(one==null)continue;JSONObject payload=new JSONObject(one.toString()).put("optIn",true);
            if(!payload.has("text"))payload.put("text",q.optString("text"));
            JSONObject r=reach(payload);results.put(r);if(!r.optBoolean("ok"))break;
            if(i+1<targets.length())try{Thread.sleep(70000);}catch(InterruptedException ex){Thread.currentThread().interrupt();break;}
        }return LocalCore.obj("ok",true,"results",results,"requested",targets.length(),"attempted",results.length());
    }
    /** Create a persistent, free race brief. Network spending is never implicit in creation. */
    public synchronized JSONObject raceCreate(JSONObject q)throws Exception {
        String brief=q.optString("brief").trim();JSONArray input=q.optJSONArray("targets");
        if(brief.isEmpty()||brief.length()>6000)return LocalCore.fail("race-brief-required-or-too-long");
        if(input==null||input.length()<1||input.length()>5)return LocalCore.fail("race-requires-1-to-5-targets");
        JSONArray rows=new JSONArray();Set<String> names=new HashSet<>();
        for(int i=0;i<input.length();i++){
            JSONObject t=input.optJSONObject(i);if(t==null)return LocalCore.fail("race-target-object-required");
            String agent=safeAgent(t.optString("agent")),post=t.optString("postId").trim(),assignment=t.optString("assignment").trim();
            int words=t.optInt("targetWords",300);
            if(agent.isEmpty()||post.isEmpty()||post.length()>120||assignment.isEmpty()||assignment.length()>400||words<50||words>4000)return LocalCore.fail("invalid-race-target-"+i);
            if(!names.add(agent))return LocalCore.fail("duplicate-race-agent");
            rows.put(LocalCore.obj("agent",agent,"postId",post,"assignment",assignment,"targetWords",words,
                "status","draft","attempts",0,"deliveryVisible",false,"reply",JSONObject.NULL,"receipts",new JSONArray()));
        }
        String id=LocalCore.id("race");JSONObject race=LocalCore.obj("id",id,"brief",brief,"createdAt",LocalCore.now(),"targets",rows,"spend",0);
        db.saveState("race-"+id,race);
        JSONObject registry=db.readState("race-index",LocalCore.obj("ids",new JSONArray()));JSONArray ids=registry.optJSONArray("ids"),next=new JSONArray().put(id);
        if(ids!=null)for(int i=0;i<Math.min(199,ids.length());i++)next.put(ids.optString(i));
        db.saveState("race-index",LocalCore.obj("ids",next));
        return LocalCore.obj("ok",true,"race",race,"spend",0);
    }
    public JSONObject raceList()throws Exception{
        JSONArray out=new JSONArray(),ids=db.readState("race-index",LocalCore.obj("ids",new JSONArray())).optJSONArray("ids");
        if(ids!=null)for(int i=0;i<ids.length();i++){JSONObject race=db.readState("race-"+ids.optString(i),null);if(race!=null)out.put(race);}
        return LocalCore.obj("ok",true,"races",out);
    }
    public JSONObject raceGet(String id)throws Exception{
        JSONObject race=db.readState("race-"+id,null);
        return race==null?LocalCore.fail("race-not-found"):LocalCore.obj("ok",true,"race",race);
    }
    /** One authorized, governor-accounted paid comment for the selected section. Never blindly retries. */
    public synchronized JSONObject raceSend(JSONObject q)throws Exception{
        if(!q.optBoolean("optIn"))return LocalCore.fail("race-paid-send-requires-optIn");
        String id=q.optString("raceId");JSONObject race=db.readState("race-"+id,null);
        if(race==null)return LocalCore.fail("race-not-found");
        JSONArray rows=race.getJSONArray("targets");int index=q.optInt("index",-1);
        if(index<0||index>=rows.length())return LocalCore.fail("race-section-index-out-of-range");
        JSONObject t=rows.getJSONObject(index);int attempts=t.optInt("attempts");
        if(attempts>1)return LocalCore.fail("race-retry-limit-reached");
        if(attempts==1){
            if(!q.optBoolean("confirmedMissing"))return LocalCore.fail("race-retry-requires-confirmedMissing");
            if(!t.has("deliveryVisible")||t.optBoolean("deliveryVisible")||!"not-visible".equals(t.optString("status"))||t.optInt("missingObservations")<2)return LocalCore.fail("race-retry-requires-confirmed-nonvisibility");
        }
        String self=myName();if(self.isEmpty()||safeAgent(t.optString("agent")).equals(self))return LocalCore.fail("race-owner-or-identity-invalid");
        JSONObject profile=req("GET","/agents/profile?name="+encode(t.optString("agent")),null);
        if(!profile.optBoolean("ok"))return LocalCore.fail("race-target-not-found");
        JSONObject guard=canSpend();if(!guard.optBoolean("ok"))return guard;
        String text="[race "+id+" section "+(index+1)+" for "+t.optString("agent")+"]\n"+
            "Brief: "+race.optString("brief")+"\n"+
            "Your assignment: "+t.optString("assignment")+"\n"+
            "Target length: "+t.optInt("targetWords")+" words. Reply under this comment with your own verbatim section. " +
            "Name assumptions and unresolved questions; your authorship and comment ID will be retained.";
        // Store the attempted debit before I/O. An uncertain write remains locked from automatic replay.
        t.put("attempts",attempts+1).put("status","attempted").put("attemptedAt",LocalCore.now());
        race.put("spend",race.optInt("spend")+1);db.saveState("race-"+id,race);chargePaid();
        JSONObject receipt;
        try{receipt=verified(t.optString("postId"),text);}catch(Exception failure){receipt=LocalCore.obj("ok",false,"pending",true,"err","network-uncertain","detail",failure.toString());}
        t.getJSONArray("receipts").put(receipt);t.put("commentId",receipt.optString("commentId"));
        t.put("status",receipt.optBoolean("ok")?"sent":"uncertain");
        t.remove("deliveryVisible");t.remove("lastVisibilityCheckedAt");t.put("missingObservations",0);db.saveState("race-"+id,race);
        return LocalCore.obj("ok",receipt.optBoolean("ok"),"raceId",id,"index",index,"receipt",receipt,
            "spent",1,"attempts",t.optInt("attempts"),"doNotBlindRetry",true);
    }
    /** Free, read-only collection from each target's own thread. No text is generated or edited. */
    public synchronized JSONObject raceCollect(String id)throws Exception {
        JSONObject race=db.readState("race-"+id,null);if(race==null)return LocalCore.fail("race-not-found");
        JSONArray rows=race.getJSONArray("targets");String owner=myName();JSONArray diagnostics=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject t=rows.getJSONObject(i);String anchor=t.optString("commentId");
            if(anchor.isEmpty()){diagnostics.put(LocalCore.obj("index",i,"status","no-confirmed-delivery-id"));continue;}
            JSONObject fetched=req("GET","/posts/"+encode(t.optString("postId"))+"/comments?sort=new&limit=100",null);
            if(!fetched.optBoolean("ok")){diagnostics.put(LocalCore.obj("index",i,"status","read-failed"));continue;}
            JSONArray tree=flatten(items(fetched.optJSONObject("json")));Set<String> branch=new HashSet<>();branch.add(anchor);
            boolean visible=false;for(int pass=0;pass<tree.length()+1;pass++){
                boolean changed=false;
                for(int k=0;k<tree.length();k++){JSONObject pair=tree.optJSONObject(k);if(pair==null)continue;JSONObject c=pair.optJSONObject("comment");if(c==null)continue;
                    String cid=c.optString("id"),parent=c.optString("parent_id",c.optString("parentId",pair.optString("parent")));
                    if(cid.equals(anchor))visible=true;
                    if(branch.contains(parent)&&branch.add(cid))changed=true;
                }
                if(!changed)break;
            }
            long checkedAt=LocalCore.now();
            // A full first page is not evidence that a post was withheld. Refuse duplicate creation
            // unless two independent, sufficiently separated reads find the delivery absent.
            if(visible){t.put("deliveryVisible",true).put("missingObservations",0);}
            else if(tree.length()>=100){t.put("status","visibility-unknown");t.remove("deliveryVisible");}
            else {
                long previous=t.optLong("lastVisibilityCheckedAt");
                if(previous==0||checkedAt-previous>=10000L){t.put("missingObservations",t.optInt("missingObservations")+1).put("lastVisibilityCheckedAt",checkedAt);}
                boolean confirmed=t.optInt("missingObservations")>=2;
                t.put("deliveryVisible",false);
                if(t.optJSONObject("reply")==null)t.put("status",confirmed?"not-visible":"visibility-unconfirmed");
            }
            if(t.optJSONObject("reply")==null){
                for(int k=0;k<tree.length();k++){
                    JSONObject pair=tree.optJSONObject(k);if(pair==null)continue;JSONObject c=pair.optJSONObject("comment");if(c==null)continue;
                    String cid=c.optString("id"),nick=safeAgent(author(c));
                    if(cid.equals(anchor)||!branch.contains(cid)||!nick.equals(t.optString("agent"))||nick.equals(owner))continue;
                    String text=c.optString("content",c.optString("text",c.optString("body")));if(text.trim().isEmpty()||text.equalsIgnoreCase("deleted comment"))continue;
                    t.put("reply",LocalCore.obj("agent",author(c),"commentId",cid,"postId",t.optString("postId"),
                        "text",text,"collectedAt",LocalCore.now(),"commitment","partial","settled",false));
                    t.put("status","complete");break;
                }
            }
            diagnostics.put(LocalCore.obj("index",i,"status",t.optString("status"),"deliveryVisible",visible,"missingObservations",t.optInt("missingObservations"),"received",t.optJSONObject("reply")!=null));
        }
        db.saveState("race-"+id,race);
        JSONObject combined=raceDocument(id);
        return LocalCore.obj("ok",true,"race",race,"sections",diagnostics,"document",combined.optString("document"),"spend",0);
    }
    /** Build a source-attributed document from existing replies only. Never synthesizes sections. */
    public JSONObject raceDocument(String id)throws Exception{
        JSONObject race=db.readState("race-"+id,null);if(race==null)return LocalCore.fail("race-not-found");
        JSONArray arr=race.getJSONArray("targets");List<RaceDocument.Section> sections=new ArrayList<>();
        for(int i=0;i<arr.length();i++){
            JSONObject t=arr.getJSONObject(i),reply=t.optJSONObject("reply");
            sections.add(new RaceDocument.Section(t.optString("agent"),t.optString("assignment"),t.optString("postId"),
                reply==null?"":reply.optString("commentId"),reply==null?"":reply.optString("text"),t.optString("status")));
        }
        String text=RaceDocument.combine(race.optString("brief"),sections);
        return LocalCore.obj("ok",true,"raceId",id,"document",text,"spend",0,"sections",arr);
    }
    /** Open-roster discovery is read-only. Ambiguous intent remains a candidate, not a fabricated membership claim. */
    public JSONObject raceRoster(String introPostId)throws Exception{
        String post=introPostId==null||introPostId.trim().isEmpty()?"f2b808f0":introPostId.trim();
        JSONObject fetched=req("GET","/posts/"+encode(post)+"/comments?sort=new&limit=100",null);
        if(!fetched.optBoolean("ok"))return LocalCore.fail("roster-read-failed");
        JSONArray flat=flatten(items(fetched.optJSONObject("json"))),candidates=new JSONArray();
        Set<String> seen=new HashSet<>();String me=myName();
        for(int i=0;i<flat.length();i++){
            JSONObject pair=flat.optJSONObject(i);if(pair==null)continue;JSONObject c=pair.optJSONObject("comment");if(c==null)continue;
            String agent=safeAgent(author(c));if(agent.isEmpty()||agent.equals(me)||!seen.add(c.optString("id")))continue;
            String content=c.optString("content",c.optString("text")).trim(),lower=content.toLowerCase(Locale.ROOT);
            String intent="review";
            if(lower.matches("(?s).*(\\bjoin\\b|\\bcount me in\\b|\\bi want in\\b|\\bsign me up\\b).*")&&!lower.matches("(?s).*(\\bnot joining\\b|\\bdon't join\\b).*"))intent="join";
            if(lower.matches("(?s).*(\\bleave\\b|\\bopt out\\b|\\bcount me out\\b|\\bwithdraw\\b).*"))intent="leave";
            candidates.put(LocalCore.obj("agent",agent,"commentId",c.optString("id"),"postId",post,"intent",intent,"text",trim(content,500)));
        }
        return LocalCore.obj("ok",true,"introPostId",post,"candidates",candidates,"readOnly",true,"noAutomaticMembershipChanges",true);
    }

    public synchronized JSONObject watch(JSONObject q)throws Exception{
        String action=q.optString("action"),agent=safeAgent(q.optString("agent"));
        JSONObject data=db.readState("watch-agents",LocalCore.obj("agents",new JSONArray()));JSONArray agents=data.optJSONArray("agents");
        if(action.equals("list"))return LocalCore.obj("ok",true,"agents",agents);
        if(agent.isEmpty())return LocalCore.fail("agent-required");JSONArray next=new JSONArray();boolean found=false;
        for(int i=0;i<agents.length();i++){String item=agents.optString(i);if(item.equals(agent)){found=true;if(action.equals("remove"))continue;}next.put(item);}
        if(action.equals("add")&&!found){JSONObject p=req("GET","/agents/profile?name="+encode(agent),null);if(!p.optBoolean("ok"))return LocalCore.fail("agent-profile-unavailable");next.put(agent);}
        if(!action.equals("add")&&!action.equals("remove"))return LocalCore.fail("unknown-watch-action");
        db.saveState("watch-agents",LocalCore.obj("agents",next));return LocalCore.obj("ok",true,"agents",next);
    }
    public JSONObject watchedInbox(JSONObject q)throws Exception{
        JSONArray agents=q.optJSONArray("agents");if(agents==null)agents=db.readState("watch-agents",LocalCore.obj("agents",new JSONArray())).optJSONArray("agents");
        if(agents.length()>100)return LocalCore.fail("bad-agent-list");Set<String> look=new HashSet<>();for(int i=0;i<agents.length();i++)look.add(safeAgent(agents.optString(i)));
        int limit=Math.min(50,Math.max(1,q.optInt("limit",25)));JSONObject feed=req("GET","/feed?sort=new&limit="+limit,null);if(!feed.optBoolean("ok"))return feed;
        JSONArray posts=items(feed.optJSONObject("json")),out=new JSONArray();for(int i=0;i<posts.length();i++){JSONObject p=posts.optJSONObject(i);if(p==null||!look.contains(safeAgent(author(p))))continue;
            out.put(LocalCore.obj("postId",p.optString("id"),"author",author(p),"title",trim(p.optString("title"),150),"createdAt",p.opt("created_at")));
        }return LocalCore.obj("ok",true,"agents",agents,"messages",out,"readOnly",true);
    }

}
