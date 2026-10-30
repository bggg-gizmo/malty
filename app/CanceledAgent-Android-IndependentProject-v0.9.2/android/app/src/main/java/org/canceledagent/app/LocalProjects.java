package org.canceledagent.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Offline-first versioned projects, saved HTML and trace/backstep records. */
public final class LocalProjects extends SQLiteOpenHelper {
    public LocalProjects(Context c){this(c,"projects.db");}
    public LocalProjects(Context c,String database){super(c,database,null,2);}
    @Override public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,source TEXT NOT NULL,revision INTEGER NOT NULL,updated INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE revisions(project TEXT NOT NULL,revision INTEGER NOT NULL,source TEXT NOT NULL,label TEXT NOT NULL,PRIMARY KEY(project,revision))");
        d.execSQL("CREATE TABLE traces(id TEXT PRIMARY KEY,project TEXT NOT NULL,seq INTEGER NOT NULL,kind TEXT NOT NULL,content TEXT NOT NULL,parent TEXT,superseded TEXT,created INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE trace_details(trace_id TEXT PRIMARY KEY,project TEXT NOT NULL,rating INTEGER NOT NULL DEFAULT 0,pivot INTEGER NOT NULL DEFAULT 0,change_label TEXT NOT NULL DEFAULT '',snapshot TEXT,feedback TEXT NOT NULL DEFAULT '')");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int old,int next){
        if(old<2)d.execSQL("CREATE TABLE IF NOT EXISTS trace_details(trace_id TEXT PRIMARY KEY,project TEXT NOT NULL,rating INTEGER NOT NULL DEFAULT 0,pivot INTEGER NOT NULL DEFAULT 0,change_label TEXT NOT NULL DEFAULT '',snapshot TEXT,feedback TEXT NOT NULL DEFAULT '')");
        if(next>2)throw new IllegalStateException("Unknown project database version: "+next);
    }
    public String create(String name,String html){
        String id="prj-"+UUID.randomUUID();
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try {
            insertProject(d,id,name,html,1);
            insertRevision(d,id,1,html,"Initial revision");
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
        return id;
    }
    private void insertProject(SQLiteDatabase d,String id,String name,String source,int rev){
        ContentValues v=new ContentValues();v.put("id",id);v.put("name",name);v.put("source",source);v.put("revision",rev);v.put("updated",System.currentTimeMillis());
        d.insertOrThrow("projects",null,v);
    }
    private void insertRevision(SQLiteDatabase d,String id,int rev,String source,String label){
        ContentValues v=new ContentValues();v.put("project",id);v.put("revision",rev);v.put("source",source);v.put("label",label);
        d.insertOrThrow("revisions",null,v);
    }
    public JSONObject project(String id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT name,source,revision,updated FROM projects WHERE id=?",new String[]{id})){
            if(!c.moveToFirst())return null;
            JSONObject j=new JSONObject();j.put("id",id);j.put("name",c.getString(0));j.put("source",c.getString(1));j.put("revision",c.getInt(2));j.put("updatedAt",c.getLong(3));return j;
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public JSONArray projects(){
        JSONArray out=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM projects ORDER BY updated DESC",null)){
            while(c.moveToNext())out.put(project(c.getString(0)));
        }
        return out;
    }
    public int save(String id,String name,String source,String label,int expected){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try {
            JSONObject p=project(id);
            if(p==null)throw new IllegalArgumentException("Project not found");
            int version=p.optInt("revision");
            if(version!=expected)throw new IllegalStateException("Project edited elsewhere: revision conflict");
            ContentValues v=new ContentValues();v.put("name",name);v.put("source",source);v.put("revision",version+1);v.put("updated",System.currentTimeMillis());
            d.update("projects",v,"id=?",new String[]{id});
            insertRevision(d,id,version+1,source,label);
            d.setTransactionSuccessful();return version+1;
        }finally{d.endTransaction();}
    }
    public int revert(String id,int oldRevision,int expected){
        String src;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT source FROM revisions WHERE project=? AND revision=?",new String[]{id,Integer.toString(oldRevision)})){
            if(!c.moveToFirst())throw new IllegalArgumentException("Revision not found");src=c.getString(0);
        }
        JSONObject p=project(id);
        return save(id,p.optString("name"),src,"Revert to revision "+oldRevision,expected);
    }
    public JSONArray revisions(String id){
        JSONArray a=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT revision,label FROM revisions WHERE project=? ORDER BY revision DESC",new String[]{id})){
            while(c.moveToNext()){JSONObject j=new JSONObject();try{j.put("revision",c.getInt(0));j.put("label",c.getString(1));}catch(Exception ex){throw new IllegalStateException(ex);}a.put(j);}
        }
        return a;
    }
    public String thought(String project,String kind,String text,String parent){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            int seq=1;
            try(Cursor c=d.rawQuery("SELECT COALESCE(MAX(seq),0)+1 FROM traces WHERE project=?",new String[]{project})){
                if(c.moveToFirst())seq=c.getInt(0);
            }
            if(parent!=null){try(Cursor c=d.rawQuery("SELECT id FROM traces WHERE id=? AND project=?",new String[]{parent,project})){
                if(!c.moveToFirst())throw new IllegalArgumentException("Superseded entry not found");
            }}
            String id="th-"+UUID.randomUUID();ContentValues v=new ContentValues();v.put("id",id);v.put("project",project);
            v.put("seq",seq);v.put("kind",kind);v.put("content",text);v.put("parent",parent);v.put("created",System.currentTimeMillis());
            d.insertOrThrow("traces",null,v);
            if("backstep".equals(kind)&&parent!=null){ContentValues update=new ContentValues();update.put("superseded",id);d.update("traces",update,"id=?",new String[]{parent});}
            d.setTransactionSuccessful();return id;
        }finally{d.endTransaction();}
    }
    public JSONArray trace(String project){
        JSONArray a=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,seq,kind,content,parent,superseded FROM traces WHERE project=? ORDER BY seq",new String[]{project})){
            while(c.moveToNext()){
                JSONObject j=new JSONObject();try{j.put("id",c.getString(0));j.put("seq",c.getInt(1));j.put("kind",c.getString(2));j.put("text",c.getString(3));j.put("parentId",c.getString(4));j.put("supersededBy",c.getString(5));}catch(Exception ex){throw new IllegalStateException(ex);}a.put(j);
            }
        }
        return a;
    }
    /** Revision-linked thinking pipeline. Historical traces are unchanged; details use a versioned add-on table. */
    public synchronized String pipe(String project,String kind,String text,String parent,boolean pivot,String changeLabel,String snapshot){
        if(!"think".equals(kind)&&!"action".equals(kind)&&!"change".equals(kind)&&!"backstep".equals(kind))throw new IllegalArgumentException("Unknown thinking kind");
        String id=thought(project,kind,text,parent);
        ContentValues v=new ContentValues();v.put("trace_id",id);v.put("project",project);v.put("pivot",pivot?1:0);
        v.put("change_label",changeLabel==null?"":changeLabel);v.put("snapshot",snapshot);
        getWritableDatabase().insertOrThrow("trace_details",null,v);
        return id;
    }
    public synchronized void rate(String project,String traceId,int rating,String feedback){
        if(rating<-1||rating>1)throw new IllegalArgumentException("rating must be -1, 0 or +1");
        SQLiteDatabase d=getWritableDatabase();ContentValues v=new ContentValues();v.put("rating",rating);
        v.put("feedback",feedback==null?"":feedback);
        if(d.update("trace_details",v,"trace_id=? AND project=?",new String[]{traceId,project})==0){
            ContentValues init=new ContentValues();init.put("trace_id",traceId);init.put("project",project);init.put("rating",rating);init.put("feedback",feedback==null?"":feedback);
            // Foreign relationship is verified before an inherited/legacy trace is rated.
            try(Cursor c=d.rawQuery("SELECT id FROM traces WHERE id=? AND project=?",new String[]{traceId,project})){
                if(!c.moveToFirst())throw new IllegalArgumentException("Unknown trace entry");
            }
            d.insertOrThrow("trace_details",null,init);
        }
    }
    public JSONArray detailedTrace(String project){
        JSONArray result=new JSONArray();SQLiteDatabase d=getReadableDatabase();
        try(Cursor c=d.rawQuery("SELECT t.id,t.seq,t.kind,t.content,t.parent,t.superseded,t.created,COALESCE(x.rating,0),COALESCE(x.pivot,0),COALESCE(x.change_label,''),x.snapshot,COALESCE(x.feedback,'') FROM traces t LEFT JOIN trace_details x ON x.trace_id=t.id WHERE t.project=? ORDER BY t.seq",new String[]{project})){
            while(c.moveToNext()){
                JSONObject j=new JSONObject();try{
                    j.put("id",c.getString(0));j.put("seq",c.getInt(1));j.put("kind",c.getString(2));j.put("text",c.getString(3));j.put("parentId",c.getString(4));j.put("supersededBy",c.getString(5));j.put("created",c.getLong(6));
                    j.put("rating",c.getInt(7));j.put("pivot",c.getInt(8)!=0);j.put("changeLabel",c.getString(9));j.put("snapshot",c.getString(10));j.put("feedback",c.getString(11));
                }catch(Exception ex){throw new IllegalStateException(ex);}result.put(j);
            }
        }return result;
    }
    public String feedbackExport(String project){
        JSONArray history=detailedTrace(project);StringBuilder b=new StringBuilder("Thinking pipeline feedback\n");
        for(int i=0;i<history.length();i++){
            JSONObject e=history.optJSONObject(i);int rating=e.optInt("rating");
            if(rating==0)continue;
            b.append(rating>0?"ACCEPT ":"REJECT ").append(e.optString("id")).append(" #").append(e.optInt("seq"))
             .append(" ").append(e.optString("kind")).append(": ").append(e.optString("text"))
             .append("\nReason: ").append(e.optString("feedback")).append("\nTRACE-BACK:");
            java.util.HashSet<String> seen=new java.util.HashSet<>();String next=e.optString("parentId","");
            while(!next.isEmpty()&&seen.add(next)){
                JSONObject parent=null;for(int j=0;j<history.length();j++){JSONObject candidate=history.optJSONObject(j);if(next.equals(candidate.optString("id"))){parent=candidate;break;}}
                if(parent==null)break;b.append("\n - ").append(parent.optString("id")).append(": ").append(parent.optString("text"));
                next=parent.optString("parentId","");
            }b.append("\n\n");
        }return b.toString();
    }
    public int restoreTraceSnapshot(String project,String traceId,int expected){
        String snapshot=null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT snapshot FROM trace_details WHERE trace_id=? AND project=?",new String[]{traceId,project})){
            if(!c.moveToFirst())throw new IllegalArgumentException("No snapshot associated with trace");snapshot=c.getString(0);
        }
        if(snapshot==null)throw new IllegalArgumentException("Trace has no snapshot");
        JSONObject p=project(project);return save(project,p.optString("name"),snapshot,"Restore thinking snapshot "+traceId,expected);
    }

}
