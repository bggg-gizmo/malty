package org.canceledagent.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Standalone, opt-in blueprint adoption and verification. Does not execute remote instructions. */
public final class BlueprintProtocol extends SQLiteOpenHelper {
    public static final String[] OVERLAYS={"git","thinking","plugins"};
    private final WorkflowProfile profile;
    public BlueprintProtocol(Context ctx){super(ctx,"blueprints.db",null,1);profile=new WorkflowProfile(ctx);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE protocols(id TEXT PRIMARY KEY,project TEXT NOT NULL,source TEXT NOT NULL,hash TEXT NOT NULL,mode TEXT NOT NULL,overlays TEXT NOT NULL,created INTEGER NOT NULL,verified INTEGER NOT NULL DEFAULT 0,verify_note TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE checks(id TEXT PRIMARY KEY,protocol TEXT NOT NULL,project TEXT NOT NULL,revision INTEGER NOT NULL,source_hash TEXT NOT NULL,operation TEXT NOT NULL,outcome TEXT NOT NULL,created INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){throw new IllegalStateException("Blueprint schema migration required");}
    public static String sha256(String s){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public String adopt(String project,String protocolText){
        if(project==null||project.trim().isEmpty())throw new IllegalArgumentException("Project required");
        if(protocolText==null||protocolText.trim().isEmpty())throw new IllegalArgumentException("Protocol source required");
        if(protocolText.length()>200000)throw new IllegalArgumentException("Protocol too large");
        String id="bp-"+UUID.randomUUID();
        SQLiteDatabase db=getWritableDatabase();db.execSQL("INSERT INTO protocols(id,project,source,hash,mode,overlays,created) VALUES(?,?,?,?,?,?,?)",
            new Object[]{id,project,protocolText,sha256(protocolText),profile.mode(),profile.snapshot().toString(),System.currentTimeMillis()});return id;
    }
    public JSONArray protocols(String project){JSONArray out=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,hash,mode,overlays,created,verified,verify_note FROM protocols WHERE project=? ORDER BY created DESC",new String[]{project})){
            while(c.moveToNext())out.put(new JSONObject().put("id",c.getString(0)).put("sha256",c.getString(1)).put("mode",c.getString(2)).put("overlays",new JSONObject(c.getString(3))).put("createdAt",c.getLong(4)).put("verified",c.getInt(5)==1).put("verification",c.getString(6)));
        }catch(Exception e){throw new IllegalStateException(e);}return out;
    }
    public JSONObject verify(String project,String protocolId,LocalProjects projects){
        JSONObject current=projects.project(project);if(current==null)throw new IllegalArgumentException("Project not found");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT hash,source,mode,overlays FROM protocols WHERE id=? AND project=?",new String[]{protocolId,project})){
            if(!c.moveToFirst())throw new IllegalArgumentException("Protocol not adopted into this project");
            String source=c.getString(1),expected=c.getString(0);boolean protocolIntact=expected.equals(sha256(source));
            String code=current.optString("source"),codeHash=sha256(code);
            boolean sourceValid=!code.trim().isEmpty()&&code.length()<=200000;
            boolean versioned=projects.revisions(project).length()>=1;
            JSONObject acceptance=BlueprintAcceptance.evaluate(source,project,code,current.optInt("revision"));
            boolean valid=protocolIntact&&sourceValid&&versioned&&acceptance.optBoolean("ok");
            String note=valid?"VERIFIED: accepted executable Blueprint checks on saved revision":"FAILED: acceptance check(s), integrity, source, or revision history";
            getWritableDatabase().execSQL("UPDATE protocols SET verified=?,verify_note=? WHERE id=?",new Object[]{valid?1:0,note,protocolId});
            check(protocolId,project,current.optInt("revision"),codeHash,"verify",valid?"pass":"fail");
            return new JSONObject().put("ok",valid).put("protocolId",protocolId).put("protocolSha256",expected).put("projectSha256",codeHash).put("revision",current.optInt("revision")).put("note",note).put("modeAtAdoption",c.getString(2)).put("overlaysAtAdoption",new JSONObject(c.getString(3))).put("acceptance",acceptance);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public void check(String protocol,String project,int revision,String hash,String operation,String outcome){
        getWritableDatabase().execSQL("INSERT INTO checks VALUES(?,?,?,?,?,?,?,?)",new Object[]{"ck-"+UUID.randomUUID(),protocol,project,revision,hash,operation,outcome,System.currentTimeMillis()});
    }
    public JSONArray audit(String project){JSONArray out=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT protocol,revision,source_hash,operation,outcome,created FROM checks WHERE project=? ORDER BY created DESC",new String[]{project})){
            while(c.moveToNext())out.put(new JSONObject().put("protocolId",c.getString(0)).put("revision",c.getInt(1)).put("sha256",c.getString(2)).put("operation",c.getString(3)).put("outcome",c.getString(4)).put("time",c.getLong(5)));
        }catch(Exception e){throw new IllegalStateException(e);}return out;
    }
}
