package org.canceledagent.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.net.URLEncoder;
import java.util.regex.*;

/** In-APK scheduled jobs, with explicit write authorization and source-matched governor caps. */
public final class AppAutomation {
    private final LocalCore db;private final MoltbookEngine molt;private final EmbeddedServer app;
    private final Map<String,Integer> frequency=new LinkedHashMap<>();
    private int sessionTaglines=0;private long lastTaglineAt=0;
    public AppAutomation(Context ctx,LocalCore store,EmbeddedServer server,MoltbookEngine api){db=store;app=server;molt=api;
        frequency.put("feedscan",3600);frequency.put("digest",3600);frequency.put("patchwatch",1800);
        frequency.put("autofold",1800);frequency.put("autoreply",1800);frequency.put("autoupvote",3600);
    }
    private static String utc(){SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);format.setTimeZone(TimeZone.getTimeZone("UTC"));return format.format(new Date());}
    private JSONObject body(JSONObject r){return r.optJSONObject("json");}
    private JSONArray array(JSONObject r,String... names){if(r==null)return new JSONArray();for(String n:names){JSONArray a=r.optJSONArray(n);if(a!=null)return a;}return new JSONArray();}
    private String self()throws Exception{JSONObject me=body(molt.call("GET","/agents/me",null));if(me==null)return "";JSONObject user=me.optJSONObject("agent");return me.optString("name",user==null?"":user.optString("name")).toLowerCase(Locale.ROOT);}
    private String ai(String prompt,String instructions,int tokens)throws Exception{
        JSONObject result=app.route("POST","/api/ai/generate",LocalCore.obj("prompt",instructions+"\n\n"+prompt,"maxTokens",tokens));
        if(!result.optBoolean("ok"))throw new IllegalStateException("provider-unavailable: "+result.optString("err"));return result.optString("text");
    }
    private static String trim(String text,int limit){return text==null?"":text.length()>limit?text.substring(0,limit):text;}
    private void mark(String kind)throws Exception{String day=utc();db.getWritableDatabase().execSQL("INSERT INTO gov_events(day,kind,created) VALUES(?,?,?)",new Object[]{day,kind,System.currentTimeMillis()});}
    private int today(String kind){try(android.database.Cursor c=db.getReadableDatabase().rawQuery("SELECT count(*) FROM gov_events WHERE day=? AND kind=?",new String[]{utc(),kind})){return c.moveToFirst()?c.getInt(0):0;}}
    private boolean governorReady(){try{JSONObject g=db.governor();return g.optInt("remaining")>g.optInt("reserve",2)&&today("autoreply")<3&&System.currentTimeMillis()-db.readState("last-create",LocalCore.obj("at",0)).optLong("at")>=70000;}catch(Exception e){return false;}}
    private void charge()throws Exception{mark("create");db.saveState("last-create",LocalCore.obj("at",System.currentTimeMillis()));}
    public void tick(){try{taglineLoop();JSONObject control=db.autonomy();if(!control.optBoolean("master"))return;JSONObject enabled=control.optJSONObject("tasks"),last=control.optJSONObject("last");if(enabled==null||last==null)return;
        long now=System.currentTimeMillis();for(Map.Entry<String,Integer> task:frequency.entrySet()){
            String name=task.getKey();if(!enabled.optBoolean(name)||now-last.optLong(name,0)<task.getValue()*1000L)continue;
            run(name);
        }
    }catch(Exception e){android.util.Log.w("CanceledAgent","Scheduled tasks: "+e.getMessage());}}
    public synchronized JSONObject run(String task)throws Exception{
        if(!frequency.containsKey(task))return LocalCore.fail("unknown-task");JSONObject config=db.autonomy();
        boolean write=task.startsWith("auto");if(write&&!config.optBoolean("writesAuthorized"))return LocalCore.fail("writes-not-authorized");
        JSONObject result;
        try{
            switch(task){case "feedscan":result=feedscan();break;case "digest":result=digest();break;
                case "patchwatch":result=patchwatch();break;case "autoupvote":result=upvote();break;
                case "autofold":result=fold();break;case "autoreply":result=reply();break;
                default:return LocalCore.fail("unknown-task");}
        }catch(Exception ex){result=LocalCore.obj("ok",false,"err",ex.getClass().getSimpleName(),"reason",ex.getMessage());}
        JSONObject history=config.optJSONObject("last");if(history==null)history=new JSONObject();history.put(task,System.currentTimeMillis());
        config.remove("ok");config.put("last",history);config.put("latestTask",task).put("latestResult",result);
        db.saveState("autonomy",config);
        JSONObject events=db.readState("autonomy-log",LocalCore.obj("events",new JSONArray()));JSONArray all=events.optJSONArray("events"),logs=new JSONArray().put(LocalCore.obj("task",task,"at",System.currentTimeMillis(),"result",result));if(all!=null)for(int i=0;i<Math.min(199,all.length());i++)logs.put(all.opt(i));
        db.saveState("autonomy-log",LocalCore.obj("events",logs));return result;
    }
    /** The original Market loop: one missing ad every 10 minutes, max six per active service session, never while Market is hidden. */
    private void taglineLoop()throws Exception {
        JSONObject settings=db.readState("tagline",LocalCore.obj("enabled",false,"visibleAt",0));
        long now=System.currentTimeMillis();
        if(!settings.optBoolean("enabled")||now-settings.optLong("visibleAt")>90000L||sessionTaglines>=6||now-lastTaglineAt<600000L)return;
        JSONObject provider=app.route("GET","/api/ai/status",new JSONObject());if(!provider.optBoolean("configured"))return;
        JSONArray ads=db.records("ads",500).optJSONArray("items");if(ads==null)return;
        for(int i=0;i<ads.length();i++){
            JSONObject ad=ads.optJSONObject(i);if(ad==null||!ad.optString("tagline").trim().isEmpty())continue;
            lastTaglineAt=now;sessionTaglines++;
            JSONObject made=app.route("POST","/api/ai/generate",LocalCore.obj("prompt","Write one ad tagline under 80 chars, plain text, no quotes, for this Moltbook comment ad: "+ad.optString("pitch",ad.optString("url")),"maxTokens",60));
            if(!made.optBoolean("ok"))return;
            String text=trim(made.optString("text"),120).trim();if(text.isEmpty())return;
            db.updateRecord("ads",ad.optString("id"),LocalCore.obj("version",ad.optInt("version"),"changes",LocalCore.obj("tagline",text)));
            return;
        }
    }
    private JSONObject feedscan()throws Exception{
        JSONObject result=molt.call("GET","/feed?sort=new&filter=following&limit=20",null);if(!result.optBoolean("ok"))return result;
        JSONObject source=body(result);JSONArray posts=array(source,"posts","data","items"),list=new JSONArray();
        for(int i=0;i<posts.length();i++){JSONObject p=posts.optJSONObject(i);if(p==null)continue;JSONObject author=p.optJSONObject("author");list.put(LocalCore.obj("id",p.optString("id"),"title",trim(p.optString("title"),250),"author",p.optString("authorName",author==null?"":author.optString("name"))));}
        db.saveState("feedscan",LocalCore.obj("posts",list,"fetchedAt",System.currentTimeMillis()));return LocalCore.obj("ok",true,"task","feedscan","posts",list,"count",list.length());
    }
    private JSONObject digest()throws Exception{
        JSONObject feed=db.readState("feedscan",LocalCore.obj("posts",new JSONArray()));JSONArray posts=feed.optJSONArray("posts");if(posts==null||posts.length()==0){JSONObject scanned=feedscan();if(!scanned.optBoolean("ok"))return scanned;posts=scanned.optJSONArray("posts");}
        String content=ai(posts.toString(),"Summarize these followed Moltbook posts factually in concise bullets. No invented facts. Include unresolved questions.",350);
        db.saveState("digest",LocalCore.obj("text",content,"createdAt",System.currentTimeMillis()));return LocalCore.obj("ok",true,"task","digest","text",content);
    }
    private JSONObject patchwatch()throws Exception{
        JSONObject home=molt.call("GET","/home",null);if(!home.optBoolean("ok"))return home;
        String aiResult=ai(trim(home.optJSONObject("json").toString(),12000),"Draft up to three conservative PATCH lines. Return JSON {\"doc\":\"builds\",\"lines\":[\"...\"]}. Never write or publish. No URLs, wallets or claims not present in the input.",300);
        JSONObject drafts=db.readState("drafts",LocalCore.obj("drafts",new JSONArray()));JSONArray items=drafts.optJSONArray("drafts"),out=new JSONArray();if(items!=null)for(int i=Math.max(0,items.length()-99);i<items.length();i++)out.put(items.opt(i));
        out.put(LocalCore.obj("text",aiResult,"createdAt",System.currentTimeMillis(),"approved",false));db.saveState("drafts",LocalCore.obj("drafts",out));
        return LocalCore.obj("ok",true,"task","patchwatch","draft",aiResult,"posted",false);
    }
    private JSONObject upvote()throws Exception{
        if(today("autoupvote")>=5)return LocalCore.fail("upvote-cap");JSONObject feed=molt.call("GET","/feed?sort=new&filter=following&limit=20",null);
        if(!feed.optBoolean("ok"))return feed;JSONArray posts=array(body(feed),"posts","data","items");JSONObject history=db.readState("upvote-"+utc(),LocalCore.obj("ids",new JSONArray()));
        JSONArray done=history.optJSONArray("ids");for(int i=0;i<posts.length();i++){JSONObject p=posts.optJSONObject(i);if(p==null)continue;String id=p.optString("id");if(id.isEmpty())continue;boolean exists=false;for(int k=0;k<done.length();k++)if(done.optString(k).equals(id))exists=true;
            if(exists)continue;JSONObject vote=molt.call("POST","/posts/"+URLEncoder.encode(id,"UTF-8")+"/upvote",new JSONObject());if(!vote.optBoolean("ok"))return vote;
            done.put(id);db.saveState("upvote-"+utc(),LocalCore.obj("ids",done));mark("autoupvote");return LocalCore.obj("ok",true,"postId",id,"count",today("autoupvote"));}
        return LocalCore.obj("ok",true,"result","no-new-followed-posts");
    }
    private JSONObject ownComment(String postId,String commentId)throws Exception{
        String me=self();if(me.isEmpty())return null;JSONObject data=molt.call("GET","/posts/"+URLEncoder.encode(postId,"UTF-8")+"/comments?sort=new&limit=100",null);
        if(!data.optBoolean("ok"))return null;JSONArray list=array(body(data),"comments","data","items");for(int i=0;i<list.length();i++){
            JSONObject c=list.optJSONObject(i);if(c==null)continue;JSONObject a=c.optJSONObject("author");String owner=c.optString("authorName",a==null?"":a.optString("name"));if(c.optString("id").equals(commentId)&&owner.equalsIgnoreCase(me))return c;}
        return null;
    }
    private JSONObject fold()throws Exception{
        if(today("autofold")>=6)return LocalCore.fail("fold-cap");JSONArray docs=db.readState("docs",LocalCore.obj("docs",new JSONArray())).optJSONArray("docs");if(docs==null||docs.length()==0)return LocalCore.fail("owned-docs-not-configured");
        JSONArray drafts=db.readState("drafts",LocalCore.obj("drafts",new JSONArray())).optJSONArray("drafts");if(drafts==null||drafts.length()==0)return LocalCore.fail("no-drafts");JSONObject draft=drafts.optJSONObject(drafts.length()-1);if(draft==null||!draft.optBoolean("approved"))return LocalCore.fail("draft-requires-approval");
        if(draft.has("appliedAt")||draft.has("attemptedAt"))return LocalCore.fail("draft-already-applied-or-attempted-requires-manual-readback");
        String text=draft.optString("text");int left=text.indexOf('{'),right=text.lastIndexOf('}');if(left<0||right<=left)return LocalCore.fail("invalid-draft-json");JSONObject changes=new JSONObject(text.substring(left,right+1));
        String docName=changes.optString("doc");
        if(changes.has("doc")&&!docName.isEmpty()&&!Arrays.asList("builds","pricing","hello").contains(docName))return LocalCore.fail("invalid-draft-document");
        if(changes.has("wire")&&!changes.optString("wire").startsWith("WIRE v"))return LocalCore.fail("invalid-draft-wire");
        JSONArray lines=changes.optJSONArray("lines");if(lines==null||lines.length()==0||lines.length()>3)return LocalCore.fail("invalid-fold-lines");for(int i=0;i<lines.length();i++){
            String entry=lines.optString(i);if(entry.length()>300||entry.trim().isEmpty()||entry.toLowerCase(Locale.ROOT).contains("http")||entry.matches("(?s).*0x[0-9a-fA-F]{8,}.*"))return LocalCore.fail("unsafe-fold-line");}
        JSONObject doc=null;for(int i=0;i<docs.length();i++){JSONObject x=docs.optJSONObject(i);if(x!=null&&(changes.optString("doc").isEmpty()||x.optString("doc").equals(changes.optString("doc")))){doc=x;break;}}
        if(doc==null)return LocalCore.fail("document-not-bound");String post=doc.optString("postId"),commentId=doc.optString("commentId");JSONObject original=ownComment(post,commentId);if(original==null)return LocalCore.fail("document-not-owned-or-invisible");
        String old=original.optString("content",original.optString("text"));
        JSONObject fresh=ownComment(post,commentId);
        if(fresh==null||!old.equals(fresh.optString("content",fresh.optString("text"))))return LocalCore.fail("document-changed-during-fold");
        List<String> additions=new ArrayList<>();for(int i=0;i<lines.length();i++)additions.add(lines.optString(i));
        String result;try{result=WireProtocol.fold(old,additions);}catch(IllegalArgumentException e){return LocalCore.fail("missing-wire-footer");}
        // Persist the attempted draft before PATCH so a lagging readback cannot cause a duplicate write.
        draft.put("attemptedAt",System.currentTimeMillis()).put("attemptedOn",commentId);
        db.saveState("drafts",LocalCore.obj("drafts",drafts));
        mark("autofold");JSONObject patched=molt.call("PATCH","/comments/"+URLEncoder.encode(commentId,"UTF-8"),LocalCore.obj("content",result));
        if(!patched.optBoolean("ok"))return LocalCore.obj("ok",false,"err","patch-failed","writeAttempted",true,"retryWrite",false,"receipt",patched);
        JSONObject confirmed=ownComment(post,commentId);
        if(confirmed==null||!result.equals(confirmed.optString("content",confirmed.optString("text"))))return LocalCore.obj("ok",false,"err","patch-not-yet-visible","writeAttempted",true,"retryWrite",false);
        draft.put("appliedAt",System.currentTimeMillis()).put("appliedTo",commentId);
        db.saveState("drafts",LocalCore.obj("drafts",drafts));
        return LocalCore.obj("ok",true,"doc",doc.optString("doc"),"commentId",commentId,"foldCount",today("autofold"),"verified",true);
    }
    private void flattenComments(JSONArray source,List<JSONObject> out,int depth){
        if(source==null||depth>25||out.size()>500)return;
        for(int i=0;i<source.length();i++){
            JSONObject c=source.optJSONObject(i);if(c==null)continue;
            out.add(c);flattenComments(c.optJSONArray("replies"),out,depth+1);
        }
    }
    private static String commentAuthor(JSONObject c){JSONObject a=c.optJSONObject("author");return c.optString("authorName",c.optString("author_name",a==null?"":a.optString("name")));}
    private static String commentTime(JSONObject c){return c.optString("createdAt",c.optString("created_at"));}
    private JSONObject reply()throws Exception{
        if(!governorReady())return LocalCore.fail("governor-blocked");JSONObject home=molt.call("GET","/home",null);if(!home.optBoolean("ok"))return home;
        JSONArray activity=array(body(home),"activity_on_your_posts");if(activity.length()==0)return LocalCore.obj("ok",true,"result","quiet");String owner=self();
        JSONObject sent=db.readState("auto-replied-"+utc(),LocalCore.obj("posts",new JSONArray()));JSONArray done=sent.optJSONArray("posts");
        for(int i=0;i<Math.min(10,activity.length());i++){JSONObject event=activity.optJSONObject(i);if(event==null)continue;String post=event.optString("post_id");if(post.isEmpty())continue;boolean old=false;for(int j=0;j<done.length();j++)if(done.optString(j).equals(post))old=true;if(old)continue;
            JSONObject comments=molt.call("GET","/posts/"+URLEncoder.encode(post,"UTF-8")+"/comments?sort=new&limit=100",null);if(!comments.optBoolean("ok"))continue;
            List<JSONObject> thread=new ArrayList<>();flattenComments(array(body(comments),"comments","data","items"),thread,0);
            if(owner.isEmpty())return LocalCore.fail("owner-identity-unavailable");
            Set<String> ownIds=new HashSet<>();List<JSONObject> myComments=new ArrayList<>();
            for(JSONObject c:thread)if(commentAuthor(c).equalsIgnoreCase(owner)){ownIds.add(c.optString("id"));myComments.add(c);}
            JSONObject newest=null;
            for(JSONObject c:thread){
                if(commentAuthor(c).equalsIgnoreCase(owner))continue;
                String bodyText=c.optString("content",c.optString("text"));String ts=commentTime(c);
                String parent=c.optString("parent_id",c.optString("parentId"));
                boolean addressed=bodyText.toLowerCase(Locale.ROOT).contains(owner.toLowerCase(Locale.ROOT))||ownIds.contains(parent);
                if(!addressed||ts.isEmpty())continue;
                boolean answered=false;for(JSONObject mine:myComments)if(!commentTime(mine).isEmpty()&&commentTime(mine).compareTo(ts)>0)answered=true;
                if(answered)continue;
                if(newest==null||ts.compareTo(commentTime(newest))>0)newest=c;
            }
            if(newest==null)continue;
            long last=db.readState("auto-reply-cooldown-"+post,LocalCore.obj("at",0)).optLong("at");
            if(System.currentTimeMillis()-last<7200000L)continue;
            String context=trim(newest.optString("content",newest.optString("text")),600);
            String text=trim(ai(context,"Write a single factual reply to a comment on my Moltbook thread, under 600 characters, without URLs, wallets, hype or invented claims.",200).trim(),700);
            if(text.isEmpty()||text.matches("(?s).*https?://.*")||text.matches("(?s).*0x[0-9a-fA-F]{8,}.*")||text.toLowerCase(Locale.ROOT).matches("(?s).*(guaranteed|risk-free|free ad space|click here).*"))return LocalCore.fail("reply-gate-rejected");
            for(JSONObject mine:myComments){String previousReply=mine.optString("content",mine.optString("text"));
                if(!previousReply.isEmpty()&&(previousReply.contains(text.substring(0,Math.min(60,text.length())))||text.contains(previousReply.substring(0,Math.min(60,previousReply.length())))))return LocalCore.fail("duplicate-reply-gate");}
            charge();mark("autoreply");done.put(post);db.saveState("auto-replied-"+utc(),LocalCore.obj("posts",done));
            db.saveState("auto-reply-cooldown-"+post,LocalCore.obj("at",System.currentTimeMillis()));
            JSONObject receipt=molt.verifiedComment(post,text,newest.optString("id"));return LocalCore.obj("ok",receipt.optBoolean("ok"),"postId",post,"receipt",receipt,"posted",true,"doNotBlindRetry",true);
        }
        return LocalCore.obj("ok",true,"result","no-unanswered-threads");
    }
}
