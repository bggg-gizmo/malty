package org.canceledagent.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Native Android implementation of the independent backend's durable contracts. */
public final class LocalCore extends SQLiteOpenHelper {
    public static final String[] KINDS={"ads","deeds","readers","bounties","services","stations","roster","fleet","polls","allies","infra","intel","watch","providers","jobs","agents","net-requests"};
    public static final String[] FEATURES={"donations","touchscreen","editor","thinking","playground","replies"};
    public static final String[] TASKS={"feedscan","digest","patchwatch","autofold","autoreply","autoupvote"};
    public static final String[] REQUIRED={"ads:nick,url,price","deeds:nick,url,upkeep","readers:nick,deedId","bounties:title","services:name,url,kind","stations:name,url","roster:nick,role","fleet:title,brief","polls:question","allies:agent","infra:nick,url,kind","intel:title","watch:agent","providers:name,endpoint,model","jobs:task","agents:agent","net-requests:kind,description"};
    public LocalCore(Context ctx){this(ctx,"independent-services.db");}
    public LocalCore(Context ctx,String name){super(ctx,name,null,1);}
    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE rooms(name TEXT PRIMARY KEY,html TEXT NOT NULL,slot INTEGER NOT NULL UNIQUE,updated INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE ballots(feat TEXT NOT NULL,nickhash INTEGER NOT NULL,direction INTEGER NOT NULL,weight INTEGER NOT NULL,donor INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(feat,nickhash))");
        d.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,version INTEGER NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("CREATE TABLE state(key TEXT PRIMARY KEY,json TEXT NOT NULL)");
        d.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,kind TEXT NOT NULL,recordId TEXT NOT NULL,operation TEXT NOT NULL,actor TEXT NOT NULL,detail TEXT NOT NULL)");
        d.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,sender TEXT NOT NULL,receiver TEXT NOT NULL,text TEXT NOT NULL,created INTEGER NOT NULL,seen INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE comments(id TEXT PRIMARY KEY,topic TEXT NOT NULL,parent TEXT NOT NULL,author TEXT NOT NULL,text TEXT NOT NULL,created INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE presence(nick TEXT PRIMARY KEY,note TEXT NOT NULL,created INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE probes(id TEXT PRIMARY KEY,digest TEXT NOT NULL,note TEXT NOT NULL,created INTEGER NOT NULL,revealed INTEGER NOT NULL DEFAULT 0)");
        d.execSQL("CREATE TABLE verdicts(id INTEGER PRIMARY KEY AUTOINCREMENT,probe TEXT NOT NULL,caught INTEGER NOT NULL,created INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE oracles(name TEXT PRIMARY KEY,digest TEXT NOT NULL,contract TEXT NOT NULL,version INTEGER NOT NULL,updated INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE oracle_history(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,version INTEGER NOT NULL,oldContract TEXT NOT NULL,newContract TEXT NOT NULL,reason TEXT NOT NULL,created INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE blockers(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,scheduled INTEGER NOT NULL,resolved INTEGER NOT NULL DEFAULT 0,rollovers INTEGER NOT NULL DEFAULT 0,events TEXT NOT NULL)");
        d.execSQL("CREATE TABLE gov_events(id INTEGER PRIMARY KEY AUTOINCREMENT,day TEXT NOT NULL,kind TEXT NOT NULL,created INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int from,int to){throw new IllegalStateException("Explicit migration required to preserve independent records");}
    static long now(){return System.currentTimeMillis();}
    static JSONObject obj(Object...pairs)throws Exception{JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);return o;}
    static JSONObject fail(String msg)throws Exception{return obj("ok",false,"err",msg);}
    static String id(String prefix){return prefix+"-"+UUID.randomUUID();}
    static String digest(String s){return BlueprintProtocol.sha256(s);}
    /** Match the original backend's normalized JSON contract digests. */
    static String canonicalContract(String raw){
        try{return canonicalJson(new org.json.JSONTokener(raw).nextValue());}
        catch(Exception ignored){return raw;}
    }
    static String canonicalJson(Object value)throws Exception{
        if(value instanceof JSONObject){
            JSONObject obj=(JSONObject)value;java.util.TreeSet<String> keys=new java.util.TreeSet<>();
            for(java.util.Iterator<String> i=obj.keys();i.hasNext();)keys.add(i.next());
            StringBuilder out=new StringBuilder("{");int n=0;
            for(String k:keys){if(n++>0)out.append(',');out.append(JSONObject.quote(k)).append(':').append(canonicalJson(obj.get(k)));}
            return out.append('}').toString();
        }
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder out=new StringBuilder("[");
            for(int i=0;i<a.length();i++){if(i>0)out.append(',');out.append(canonicalJson(a.get(i)));}
            return out.append(']').toString();}
        if(value==JSONObject.NULL||value==null)return "null";
        if(value instanceof String)return JSONObject.quote((String)value);
        if(value instanceof Boolean)return Boolean.TRUE.equals(value)?"true":"false";
        return String.valueOf(value);
    }
    static String cleanRoom(String s){if(s==null)s="";s=s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]","");return s.length()>24?s.substring(0,24):s.length()>=2?s:"";}
    static String nick(String s){if(s==null||s.isEmpty())s="molty";s=s.replaceAll("[<>&\"]","");return s.length()>24?s.substring(0,24):s.isEmpty()?"molty":s;}
    static long hash(String s){long h=2166136261L;for(char ch:s.toCharArray()){h^=ch;h=(long)(((double)h)*16777619d)&0xffffffffL;}return h;}
    static boolean known(String name,String[] list){for(String k:list)if(k.equals(name))return true;return false;}
    private JSONObject row(Cursor c)throws Exception{JSONObject r=new JSONObject(c.getString(3));r.put("id",c.getString(1));r.put("kind",c.getString(0));r.put("version",c.getInt(2));r.put("createdAt",c.getLong(4));r.put("updatedAt",c.getLong(5));return r;}
    private JSONObject record(String kind,String id)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT kind,id,version,body,created,updated FROM records WHERE kind=? AND id=?",new String[]{kind,id})){return c.moveToFirst()?row(c):null;}}
    private void event(String kind,String rec,String op,String who,String detail){getWritableDatabase().execSQL("INSERT INTO audit(at,kind,recordId,operation,actor,detail) VALUES(?,?,?,?,?,?)",new Object[]{now(),kind,rec,op,who,detail});}
    public synchronized JSONObject catalog()throws Exception{return obj("ok",true,"resources",new JSONArray(KINDS),"autonomy",new JSONArray(TASKS),"independent",true,"embedded",true);}
    public synchronized JSONObject records(String kind,int limit)throws Exception{
        if(!known(kind,KINDS))return fail("unknown-kind");JSONArray data=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT kind,id,version,body,created,updated FROM records WHERE kind=? ORDER BY updated DESC,id LIMIT ?",new String[]{kind,String.valueOf(Math.max(1,Math.min(500,limit)))})){while(c.moveToNext())data.put(row(c));}
        return obj("ok",true,"kind",kind,"items",data);
    }
    public synchronized JSONObject getRecord(String kind,String id)throws Exception{JSONObject r=record(kind,id);return r==null?fail("not-found"):obj("ok",true,"record",r);}
    public synchronized JSONObject createRecord(String kind,JSONObject data)throws Exception{
        if(!known(kind,KINDS))return fail("unknown-kind");if(data.toString().length()>130000)return fail("record-too-large");
        for(String rule:REQUIRED){String[] pair=rule.split(":",2);if(!pair[0].equals(kind))continue;for(String r:pair[1].split(","))if(data.optString(r).trim().isEmpty())return fail("missing-required-fields");}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM records WHERE kind=?",new String[]{kind})){c.moveToFirst();int max=(kind.equals("roster")?40:kind.equals("infra")?60:kind.equals("jobs")||kind.equals("agents")||kind.equals("polls")?100:500);if(c.getInt(0)>=max)return fail("registry-full");}
        JSONObject body=new JSONObject(data.toString());for(String k:new String[]{"id","kind","version","createdAt","updatedAt"})body.remove(k);
        if(kind.equals("ads")){if(!body.has("tagline"))body.put("tagline","");body.put("status","listed").put("claimed",true);}
        if(kind.equals("fleet"))body.put("state","open").put("claimer","").put("result","").put("link","");
        if(kind.equals("infra")&&!Arrays.asList("spotted","invited","committed").contains(body.optString("status")))body.put("status","committed");
        if(kind.equals("bounties")&&!body.has("state"))body.put("state","PROPOSED");
        if(kind.equals("jobs")){if(body.optBoolean("anchor"))return fail("anchor-requires-explicit-external-post-and-verification");body.put("slots",Math.max(1,Math.min(20,body.optInt("slots",2)))).put("state","open").put("claims",new JSONArray()).put("results",new JSONArray()).put("anchor",false);}
        if(kind.equals("polls"))body.put("votes",new JSONObject());
        if(kind.equals("deeds"))body.put("verified",false);
        String id=id(kind.substring(0,2));long ts=now();getWritableDatabase().execSQL("INSERT INTO records VALUES(?,?,?,?,?,?)",new Object[]{kind,id,1,body.toString(),ts,ts});
        event(kind,id,"create",body.optString("nick",body.optString("agent")),"");return getRecord(kind,id);
    }
    public synchronized JSONObject updateRecord(String kind,String id,JSONObject req)throws Exception{
        JSONObject rec=record(kind,id);if(rec==null)return fail("not-found");if(rec.optInt("version")!=req.optInt("version",-1))return fail("version-conflict");
        JSONObject changes=req.optJSONObject("changes");if(changes==null)return fail("changes-object-required");
        for(String k:new String[]{"id","kind","version","createdAt","updatedAt"})changes.remove(k);
        if(Arrays.asList("jobs","fleet","polls").contains(kind))for(String k:new String[]{"results","claims","votes","state","claimer","result"})if(changes.has(k))return fail("use-transition-endpoint");
        if(kind.equals("infra")&&changes.has("status")){
            List<String> stages=Arrays.asList("spotted","invited","committed");if(!stages.contains(changes.optString("status")))return fail("bad-status");
            if(stages.indexOf(changes.optString("status"))<stages.indexOf(rec.optString("status")))return fail("status-regression");
        }
        JSONObject body=new JSONObject(rec.toString());for(String k:new String[]{"id","kind","version","createdAt","updatedAt"})body.remove(k);
        Iterator<String> it=changes.keys();while(it.hasNext()){String k=it.next();body.put(k,changes.opt(k));}
        return commit(kind,id,body,"update",req.optString("actor"));
    }
    private JSONObject commit(String kind,String id,JSONObject body,String action,String actor)throws Exception{
        getWritableDatabase().execSQL("UPDATE records SET body=?,version=version+1,updated=? WHERE kind=? AND id=?",new Object[]{body.toString(),now(),kind,id});
        event(kind,id,action,actor,"");return getRecord(kind,id);
    }
    public synchronized JSONObject deleteRecord(String kind,String id,int version)throws Exception{
        JSONObject r=record(kind,id);if(r==null)return fail("not-found");if(version!=r.optInt("version"))return fail("version-conflict");
        getWritableDatabase().delete("records","kind=? AND id=?",new String[]{kind,id});event(kind,id,"delete","","");return obj("ok",true,"id",id);
    }
    public synchronized JSONObject audit(int limit)throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT at,kind,recordId,operation,actor,detail FROM audit ORDER BY id DESC LIMIT ?",new String[]{String.valueOf(Math.min(500,Math.max(1,limit)))})){
        while(c.moveToNext())a.put(obj("at",c.getLong(0),"kind",c.getString(1),"id",c.getString(2),"operation",c.getString(3),"actor",c.getString(4),"detail",c.getString(5)));}
        return obj("ok",true,"events",a);
    }
    public synchronized JSONObject listRooms()throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT name,html,updated FROM rooms ORDER BY slot",null)){while(c.moveToNext())a.put(obj("name",c.getString(0),"size",c.getString(1).length(),"updatedAt",c.getLong(2)));}return obj("ok",true,"rooms",a);}
    public synchronized JSONObject loadRoom(String raw)throws Exception{String name=cleanRoom(raw);if(name.isEmpty())return fail("bad-room");try(Cursor c=getReadableDatabase().rawQuery("SELECT html,updated FROM rooms WHERE name=?",new String[]{name})){return c.moveToFirst()?obj("ok",true,"room",name,"html",c.getString(0),"updatedAt",c.getLong(1)):obj("ok",true,"room",name,"html","","updatedAt",0);}}
    public synchronized JSONObject saveRoom(String raw,String html)throws Exception{
        String name=cleanRoom(raw);if(name.isEmpty())return fail("bad-room");if(html.length()>40000)return obj("ok",false,"err","too-big","max",40000);
        SQLiteDatabase d=getWritableDatabase();String evicted="";int slot=-1;
        try(Cursor c=d.rawQuery("SELECT slot FROM rooms WHERE name=?",new String[]{name})){if(c.moveToFirst())slot=c.getInt(0);}
        if(slot<0){boolean[] slots=new boolean[40];try(Cursor c=d.rawQuery("SELECT slot FROM rooms",null)){while(c.moveToNext())slots[c.getInt(0)]=true;}
            for(int i=0;i<40;i++)if(!slots[i]){slot=i;break;}
            if(slot<0){try(Cursor c=d.rawQuery("SELECT name,slot FROM rooms ORDER BY updated,slot LIMIT 1",null)){c.moveToFirst();evicted=c.getString(0);slot=c.getInt(1);}
                d.delete("rooms","slot=?",new String[]{String.valueOf(slot)});
            }
        }
        d.execSQL("INSERT OR REPLACE INTO rooms(name,html,slot,updated) VALUES(?,?,?,?)",new Object[]{name,html,slot,now()});return obj("ok",true,"room",name,"evicted",evicted);
    }
    public synchronized JSONObject votes()throws Exception{JSONArray a=new JSONArray();for(String f:FEATURES){int yes=0,no=0;try(Cursor c=getReadableDatabase().rawQuery("SELECT direction,COALESCE(SUM(weight),0) FROM ballots WHERE feat=? GROUP BY direction",new String[]{f})){while(c.moveToNext())if(c.getInt(0)==1)yes=c.getInt(1);else no=c.getInt(1);}a.put(obj("key",f,"yes",yes,"no",no,"enabled",yes>=no));}return obj("ok",true,"features",a);}
    public synchronized JSONObject vote(JSONObject q)throws Exception{
        String feat=q.optString("feat");if(!known(feat,FEATURES))return fail("bad-feature");String name=nick(q.optString("nick")).toLowerCase(Locale.ROOT);long hashed=hash(name);
        int dir=q.optString("dir").equals("off")?0:1;int weight=q.optBoolean("donor")&&q.optString("handle").length()>=2?2:1;
        boolean existing=false;try(Cursor c=getReadableDatabase().rawQuery("SELECT direction,weight FROM ballots WHERE feat=? AND nickhash=?",new String[]{feat,String.valueOf(hashed)})){
            if(c.moveToFirst()){existing=true;if(c.getInt(0)==dir&&c.getInt(1)==weight)return obj("ok",true,"dup",true,"feat",feat);}
        }
        if(!existing)try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM ballots",null)){c.moveToFirst();if(c.getInt(0)>=384)return fail("ballot-full");}
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO ballots(feat,nickhash,direction,weight,donor,updated) VALUES(?,?,?,?,?,?)",new Object[]{feat,hashed,dir,weight,weight==2?1:0,now()});
        JSONArray feats=votes().getJSONArray("features");for(int i=0;i<feats.length();i++)if(feats.getJSONObject(i).optString("key").equals(feat))return obj("ok",true,"feat",feat,"yes",feats.getJSONObject(i).optInt("yes"),"no",feats.getJSONObject(i).optInt("no"),"weight",weight);
        return fail("bad-feature");
    }
    public synchronized JSONObject readState(String key,JSONObject defaults)throws Exception{
        JSONObject stored=null;try(Cursor c=getReadableDatabase().rawQuery("SELECT json FROM state WHERE key=?",new String[]{key})){if(c.moveToFirst())stored=new JSONObject(c.getString(0));}
        if(stored==null)return defaults==null?new JSONObject():new JSONObject(defaults.toString());
        if(defaults==null)return stored;JSONObject merged=new JSONObject(defaults.toString());Iterator<String> keys=stored.keys();while(keys.hasNext()){String k=keys.next();merged.put(k,stored.get(k));}return merged;
    }
    public synchronized void saveState(String key,JSONObject value){getWritableDatabase().execSQL("INSERT OR REPLACE INTO state VALUES(?,?)",new Object[]{key,value.toString()});}
    public synchronized JSONObject send(JSONObject q)throws Exception{String receiver=nick(q.optString("to")),from=nick(q.optString("from")),text=q.optString("text");if(text.trim().isEmpty()||text.length()>40000)return fail("bad-message");String id=id("mi");long t=now();getWritableDatabase().execSQL("INSERT INTO messages VALUES(?,?,?,?,?,0)",new Object[]{id,from,receiver,text,t});return obj("ok",true,"id",id,"to",receiver,"from",from,"createdAt",t);}
    public synchronized JSONObject inbox(String who,boolean mark)throws Exception{String nick=nick(who);JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,sender,text,created,seen FROM messages WHERE receiver=? ORDER BY created DESC LIMIT 300",new String[]{nick})){while(c.moveToNext())a.put(obj("id",c.getString(0),"from",c.getString(1),"text",c.getString(2),"createdAt",c.getLong(3),"read",c.getInt(4)!=0));}
        if(mark)getWritableDatabase().execSQL("UPDATE messages SET seen=1 WHERE receiver=?",new Object[]{nick});return obj("ok",true,"nick",nick,"messages",a);
    }
    public synchronized JSONObject comment(String topic,JSONObject q)throws Exception{String author=nick(q.optString("nick",q.optString("from"))),text=q.optString("text"),parent=q.optString("parent","");if(text.isEmpty()||text.length()>40000)return fail("bad-message");String id=id("mc");getWritableDatabase().execSQL("INSERT INTO comments VALUES(?,?,?,?,?,?)",new Object[]{id,topic,parent,author,text,now()});return obj("ok",true,"id",id,"topic",topic);}
    public synchronized JSONObject topic(String topic,int limit)throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,author,parent,text,created FROM comments WHERE topic=? ORDER BY created DESC LIMIT ?",new String[]{topic,String.valueOf(Math.max(1,Math.min(limit,500)))})){while(c.moveToNext())a.put(obj("id",c.getString(0),"nick",c.getString(1),"parent",c.getString(2),"text",c.getString(3),"createdAt",c.getLong(4)));}return obj("ok",true,"topic",topic,"comments",a);}
    public synchronized JSONObject beat(JSONObject q)throws Exception{String nick=nick(q.optString("nick"));long t=now();getWritableDatabase().execSQL("INSERT OR REPLACE INTO presence VALUES(?,?,?)",new Object[]{nick,q.optString("note"),t});return obj("ok",true,"nick",nick,"updatedAt",t);}
    public synchronized JSONObject presence()throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT nick,note,created FROM presence WHERE created>=? ORDER BY created DESC",new String[]{String.valueOf(now()-900000)})){while(c.moveToNext())a.put(obj("nick",c.getString(0),"note",c.getString(1),"updatedAt",c.getLong(2)));}return obj("ok",true,"presence",a);}
    public synchronized JSONObject market()throws Exception{JSONArray ads=records("ads",500).getJSONArray("items"),selected=new JSONArray();int n=ads.length();long frame=now()/45000L;for(int i=0;i<Math.min(3,n);i++)selected.put(ads.getJSONObject((int)((frame*3+i)%n)));return obj("ok",true,"slots",3,"intervalSeconds",45,"rotation",frame,"billboards",selected);}
    public synchronized JSONObject fleet(String id,JSONObject q)throws Exception{JSONObject r=record("fleet",id);if(r==null)return fail("not-found");String action=q.optString("action"),agent=nick(q.optString("agent"));String state=r.optString("state");
        JSONObject changes=new JSONObject(r.toString());for(String k:new String[]{"id","kind","version","createdAt","updatedAt"})changes.remove(k);
        if(action.equals("claim")&&state.equals("open"))changes.put("state","claimed").put("claimer",agent);
        else if(action.equals("submit")&&state.equals("claimed")&&agent.equals(r.optString("claimer")))changes.put("state","submitted").put("result",q.optString("result"));
        else if(action.equals("accept")&&state.equals("submitted"))changes.put("state","accepted");
        else if(action.equals("reopen")&&(state.equals("submitted")||state.equals("accepted")))changes.put("state","open").put("claimer","").put("result","");
        else return fail("bad-transition");return commit("fleet",id,changes,action,agent);
    }
    public synchronized JSONObject poll(String id,JSONObject q)throws Exception{JSONObject r=record("polls",id);if(r==null)return fail("not-found");String agent=nick(q.optString("agent")),choice=q.optString("choice");if(!Arrays.asList("yes","no","abstain").contains(choice))return fail("bad-choice");
        JSONObject body=new JSONObject(r.toString());for(String k:new String[]{"id","kind","version","createdAt","updatedAt"})body.remove(k);JSONObject votes=body.optJSONObject("votes");votes.put(agent,choice);return commit("polls",id,body,"vote",agent);
    }
    public synchronized JSONObject claim(String id,JSONObject q,boolean submit)throws Exception{JSONObject r=record("jobs",id);if(r==null)return fail("not-found");String agent=nick(q.optString("agent"));JSONObject body=new JSONObject(r.toString());for(String k:new String[]{"id","kind","version","createdAt","updatedAt"})body.remove(k);
        JSONArray claims=body.optJSONArray("claims"),results=body.optJSONArray("results");boolean found=false;for(int i=0;i<claims.length();i++)if(agent.equals(claims.optString(i)))found=true;
        if(!submit){if(found)return obj("ok",true,"dup",true,"record",r);if(claims.length()>=body.optInt("slots",2))return fail("slots-full");claims.put(agent);}
        else{if(!found)return fail("not-claimed");for(int i=0;i<results.length();i++)if(agent.equals(results.optJSONObject(i).optString("agent")))return fail("already-submitted");results.put(obj("agent",agent,"result",q.optString("result"),"at",now()));if(results.length()>=body.optInt("slots"))body.put("state","complete");}
        return commit("jobs",id,body,submit?"submit":"claim",agent);
    }
    public synchronized JSONObject labCommit(JSONObject q)throws Exception{String sha=q.optString("sha256");if(!sha.matches("(?i)[a-f0-9]{64}"))return fail("invalid-sha256");String id=id("probe");getWritableDatabase().execSQL("INSERT INTO probes(id,digest,note,created) VALUES(?,?,?,?)",new Object[]{id,sha.toLowerCase(Locale.ROOT),q.optString("note"),now()});return obj("ok",true,"id",id,"sha256",sha);}
    public synchronized JSONObject labReveal(String id,JSONObject q)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT digest,revealed FROM probes WHERE id=?",new String[]{id})){
            if(!c.moveToFirst())return fail("not-found");String calculated=digest(q.optString("nonce")+"\n"+q.optString("proof"));if(!calculated.equals(c.getString(0)))return fail("commit-mismatch");
            getWritableDatabase().execSQL("UPDATE probes SET revealed=1 WHERE id=?",new Object[]{id});return obj("ok",true,"id",id,"revealed",true,"sha256",calculated);
        }
    }
    public synchronized JSONObject labProbes()throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,digest,note,created,revealed FROM probes ORDER BY created DESC",null)){
        while(c.moveToNext())a.put(obj("id",c.getString(0),"sha256",c.getString(1),"note",c.getString(2),"createdAt",c.getLong(3),"revealed",c.getInt(4)!=0));}
        return obj("ok",true,"probes",a);
    }
    public synchronized JSONObject labVerdict(String id,JSONObject q)throws Exception{try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM probes WHERE id=?",new String[]{id})){if(!c.moveToFirst())return fail("not-found");}
        int caught=q.optBoolean("caught")?1:0;getWritableDatabase().execSQL("INSERT INTO verdicts(probe,caught,created) VALUES(?,?,?)",new Object[]{id,caught,now()});return obj("ok",true,"probeId",id,"caught",caught==1);
    }
    public synchronized JSONObject labVerdicts()throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT probe,caught,created FROM verdicts ORDER BY id DESC",null)){while(c.moveToNext())a.put(obj("probeId",c.getString(0),"caught",c.getInt(1)!=0,"at",c.getLong(2)));}return obj("ok",true,"verdicts",a);}
    public synchronized JSONObject oracleFreeze(String name,JSONObject q)throws Exception{String contract=canonicalContract(q.optString("contract"));if(name.trim().isEmpty()||contract.isEmpty())return fail("bad-contract");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT version FROM oracles WHERE name=?",new String[]{name})){if(c.moveToFirst())return fail("already-frozen");}
        getWritableDatabase().execSQL("INSERT INTO oracles VALUES(?,?,?,?,?)",new Object[]{name,digest(contract),contract,1,now()});return obj("ok",true,"key",name,"version",1,"sha256",digest(contract));
    }
    public synchronized JSONObject oracleCompare(String name,JSONObject q)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT digest,contract,version FROM oracles WHERE name=?",new String[]{name})){
            if(!c.moveToFirst())return fail("not-frozen");String oldHash=c.getString(0),old=c.getString(1);int version=c.getInt(2);
            if(q.has("expectedVersion")&&q.optInt("expectedVersion")!=version)return fail("version-conflict");
            String contract=canonicalContract(q.optString("contract")),hash=digest(contract),reason=q.optString("reason");
            if(hash.equals(oldHash))return obj("ok",true,"key",name,"status","STABLE","version",version);
            if(reason.trim().isEmpty())return obj("ok",true,"key",name,"status","UNCLAIMED","version",version,"oldSha256",oldHash,"newSha256",hash);
            getWritableDatabase().execSQL("INSERT INTO oracle_history(name,version,oldContract,newContract,reason,created) VALUES(?,?,?,?,?,?)",new Object[]{name,version,old,contract,reason,now()});
            getWritableDatabase().execSQL("UPDATE oracles SET digest=?,contract=?,version=?,updated=? WHERE name=?",new Object[]{hash,contract,version+1,now(),name});
            return obj("ok",true,"key",name,"status","CLAIMED","version",version+1,"reason",reason);
        }
    }
    public synchronized JSONObject oracleHistory(String name)throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT version,oldContract,newContract,reason,created FROM oracle_history WHERE name=? ORDER BY id",new String[]{name})){
        while(c.moveToNext())a.put(obj("fromVersion",c.getInt(0),"oldContract",c.getString(1),"newContract",c.getString(2),"reason",c.getString(3),"at",c.getLong(4)));}
        return obj("ok",true,"key",name,"history",a);
    }
    public synchronized JSONObject oracles()throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT name,digest,version,updated FROM oracles ORDER BY updated DESC",null)){
        while(c.moveToNext())a.put(obj("key",c.getString(0),"sha256",c.getString(1),"version",c.getInt(2),"updatedAt",c.getLong(3)));}return obj("ok",true,"oracles",a);
    }
    public synchronized JSONObject blocker(JSONObject q)throws Exception{String title=q.optString("title");if(title.trim().isEmpty())return fail("bad-title");String id=id("block");long ts=now();getWritableDatabase().execSQL("INSERT INTO blockers VALUES(?,?,?,?,0,0,?)",new Object[]{id,title,ts,ts,"[]"});return obj("ok",true,"id",id,"createdAt",ts);}
    public synchronized JSONObject blockerEvent(String id,JSONObject q,boolean rollover)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT events,rollovers,created FROM blockers WHERE id=?",new String[]{id})){
            if(!c.moveToFirst())return fail("not-found");JSONArray events=new JSONArray(c.getString(0));events.put(obj("at",now(),"type",rollover?"rollover":"note","note",q.optString("note")));int n=c.getInt(1);
            getWritableDatabase().execSQL("UPDATE blockers SET events=?,scheduled=?,rollovers=? WHERE id=?",new Object[]{events.toString(),now(),n+(rollover?1:0),id});return obj("ok",true,"id",id,"createdAt",c.getLong(2),"rollovers",n+(rollover?1:0));
        }
    }
    public synchronized JSONObject blockers()throws Exception{JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,created,scheduled,resolved,rollovers,events FROM blockers ORDER BY created",null)){
        while(c.moveToNext())a.put(obj("id",c.getString(0),"title",c.getString(1),"createdAt",c.getLong(2),"scheduledAt",c.getLong(3),"ageMs",now()-c.getLong(2),"resolved",c.getInt(4)!=0,"rollovers",c.getInt(5),"history",new JSONArray(c.getString(6))));}
        return obj("ok",true,"blockers",a);
    }
    public synchronized JSONObject dial()throws Exception{
        JSONArray stations=new JSONArray();
        stations.put(obj("id","house-dronezone","nick","house","name","Drone Zone","url","https://ice1.somafm.com/dronezone-128-mp3","genre","ambient","source","house"));
        stations.put(obj("id","house-deepspaceone","nick","house","name","Deep Space One","url","https://ice1.somafm.com/deepspaceone-128-mp3","genre","deep ambient","source","house"));
        stations.put(obj("id","house-groovesalad","nick","house","name","Groove Salad","url","https://ice1.somafm.com/groovesalad-128-mp3","genre","ambient beats","source","house"));
        JSONArray custom=records("stations",300).getJSONArray("items");for(int i=0;i<custom.length();i++)stations.put(custom.get(i));
        return obj("ok",true,"channels",new JSONArray(new String[]{"Still Becoming","Plugg Dusk","Trap Night"}),"stations",stations);
    }
    public synchronized JSONObject deeds()throws Exception{JSONArray a=records("deeds",500).getJSONArray("items");for(int i=0;i<a.length();i++){
        JSONObject r=a.getJSONObject(i);r.put("score",2*r.optInt("upvotes")+3*r.optInt("replies")+10*r.optInt("receipts")+5*r.optInt("rooms"));}
        return obj("ok",true,"deeds",a);
    }
    public synchronized JSONObject governor()throws Exception{
        JSONObject s=readState("governor",obj("remaining",0,"reserve",2,"replyMax",3,"foldMax",6,"upvoteMax",5,"gapSec",70));
        String day=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US){{setTimeZone(TimeZone.getTimeZone("UTC"));}}.format(new Date());
        JSONObject counts=new JSONObject();try(Cursor c=getReadableDatabase().rawQuery("SELECT kind,COUNT(*) FROM gov_events WHERE day=? GROUP BY kind",new String[]{day})){while(c.moveToNext())counts.put(c.getString(0),c.getInt(1));}
        s.put("ok",true).put("today",day).put("counters",counts);return s;
    }
    public synchronized JSONObject autonomy()throws Exception{return readState("autonomy",obj("master",true,"writesAuthorized",false,"tasks",obj("feedscan",true,"digest",true,"patchwatch",true,"autofold",false,"autoreply",false,"autoupvote",false),"last",new JSONObject())).put("ok",true);}
    public synchronized JSONObject autonomyControl(JSONObject q)throws Exception{JSONObject s=autonomy();s.remove("ok");if(q.has("master"))s.put("master",q.optBoolean("master"));if(q.has("writesAuthorized"))s.put("writesAuthorized",q.optBoolean("writesAuthorized"));
        if(q.has("task")){String task=q.optString("task");if(!known(task,TASKS))return fail("unknown-task");JSONObject tasks=s.optJSONObject("tasks");tasks.put(task,q.optBoolean("enabled"));}
        saveState("autonomy",s);return s.put("ok",true);
    }
    public synchronized JSONObject autonomyRun(String task)throws Exception{if(!known(task,TASKS))return fail("unknown-task");JSONObject s=autonomy();boolean write=task.startsWith("auto");
        if(write&&!s.optBoolean("writesAuthorized"))return fail("writes-disabled");
        if(write)return fail("external-write-requires-provider-and-explicit-per-operation-verification");
        JSONObject last=s.optJSONObject("last");last.put(task,now());s.remove("ok");saveState("autonomy",s);return obj("ok",true,"task",task,"ranAt",now(),"status","read-task-scheduled","note","No external Moltbook key or AI result is fabricated");
    }
}
