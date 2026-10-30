package org.canceledagent.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Device-owned character agents and durable conversations. Shared character files are DATA, never executable code. */
public final class AgentRuntime extends SQLiteOpenHelper {
    public static final String[][] INITIAL = {
        {"mk-naomi","Mk Naomi","https://perchance.org/ai-character-chat?data=Mk_Naomi~cc2ba375eac0f4a9022c27fb4177f318.gz"},
        {"mk-often","mk often","https://perchance.org/ai-character-chat?data=mk_often~5fe8298fdeaa7aa9b98ce58d875bd810.gz"},
        {"mk-ultra","MK Ultra","https://perchance.org/ai-character-chat?data=MK_Ultra~deeb09cb91aa38c1fbd3b4c253a82995.gz"},
        {"mina","Mina (Model-Interactive Network Assistant)","https://perchance.org/ai-character-chat?data=Mina_(Model-Interactive_Network_Assistant)~0fa280e33d4c782fd5f0e355979020e3.gz"}
    };
    private final SecurePrefs settings;
    private final Context appContext;
    public AgentRuntime(Context context) { super(context.getApplicationContext(),"character-agents.db",null,1);appContext=context.getApplicationContext();settings=new SecurePrefs(context);seed(); }
    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE characters(id TEXT PRIMARY KEY, label TEXT NOT NULL, share_url TEXT NOT NULL, name TEXT NOT NULL, role_instruction TEXT NOT NULL, writing_instruction TEXT NOT NULL, reminder TEXT NOT NULL, original_json TEXT NOT NULL, sha256 TEXT NOT NULL, imported_at INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY, title TEXT NOT NULL, participants TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL, speaker TEXT NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX messages_by_conversation ON messages(conversation_id,id)");
    }
    @Override public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion) { throw new IllegalStateException("Agent storage requires a versioned migration"); }
    private void seed(){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();try {
            for(String[] a:INITIAL){android.content.ContentValues v=new android.content.ContentValues();v.put("id",a[0]);v.put("label",a[1]);v.put("share_url",a[2]);v.put("name",a[1]);v.put("role_instruction","");v.put("writing_instruction","");v.put("reminder","");v.put("original_json","");v.put("sha256","");v.put("imported_at",0L);d.insertWithOnConflict("characters",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
            d.setTransactionSuccessful();
        } finally {d.endTransaction();}
    }
    private static JSONObject err(String error){try{return new JSONObject().put("ok",false).put("error",error);}catch(Exception e){throw new IllegalStateException(e);}}
    private static JSONObject ok()throws Exception{return new JSONObject().put("ok",true);}
    private static String sha(byte[] b)throws Exception{byte[] out=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:out)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    private static String code(String v){return v==null?"":v.trim();}
    public synchronized JSONObject agents()throws Exception {
        JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,label,share_url,name,sha256,imported_at FROM characters ORDER BY rowid",null)){
            while(c.moveToNext())a.put(new JSONObject().put("id",c.getString(0)).put("label",c.getString(1)).put("shareUrl",c.getString(2)).put("name",c.getString(3)).put("imported",c.getLong(5)>0).put("sha256",c.getString(4)).put("importedAt",c.getLong(5)));
        }return ok().put("agents",a);
    }
    public synchronized JSONObject character(String id)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,label,share_url,name,role_instruction,writing_instruction,reminder,sha256,imported_at FROM characters WHERE id=?",new String[]{id})){
            if(!c.moveToFirst())return err("unknown-character");
            return ok().put("id",c.getString(0)).put("label",c.getString(1)).put("shareUrl",c.getString(2)).put("name",c.getString(3))
                .put("roleInstruction",c.getString(4)).put("writingInstruction",c.getString(5)).put("reminderMessage",c.getString(6)).put("sha256",c.getString(7)).put("imported",c.getLong(8)>0);
        }
    }
    public synchronized JSONObject profileSummary(String id)throws Exception {
        JSONObject full=character(id);
        if(!full.optBoolean("ok"))return full;
        return ok().put("id",full.optString("id")).put("name",full.optString("name"))
            .put("shareUrl",full.optString("shareUrl")).put("imported",full.optBoolean("imported"))
            .put("sha256",full.optString("sha256"));
    }
    /** Fixed, exact host and hash validation. No arbitrary URL fetch or redirect following. */
    public static String cloudFile(String share)throws Exception {
        Uri uri=Uri.parse(share);if(!"https".equalsIgnoreCase(uri.getScheme())||!"perchance.org".equalsIgnoreCase(uri.getHost())||!"/ai-character-chat".equals(uri.getPath()))throw new IllegalArgumentException("Not a Perchance AI character share URL");
        String data=uri.getQueryParameter("data");if(data==null||!data.contains("~"))throw new IllegalArgumentException("Share link is missing character data");
        String name=data.substring(data.lastIndexOf('~')+1);
        if(!name.matches("[0-9a-fA-F]{32}\\.gz"))throw new IllegalArgumentException("Invalid character upload identifier");
        return "https://user.uploads.dev/file/"+name;
    }
    private static byte[] readLimit(InputStream in,int max)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))>=0){out.write(buffer,0,n);if(out.size()>max)throw new IllegalArgumentException("Character file exceeds supported size of "+max+" bytes");}return out.toByteArray();
    }
    public JSONObject downloadAndImport(String id)throws Exception {
        String share=character(id).optString("shareUrl");String src=cloudFile(share);HttpURLConnection c=(HttpURLConnection)new URL(src).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/gzip,application/octet-stream");
        try {int status=c.getResponseCode();if(status!=200)return err("profile-download-http-"+status);
            byte[] zipped=readLimit(c.getInputStream(),16*1024*1024);return importGzip(id,share,zipped);
        }finally{c.disconnect();}
    }
    public synchronized JSONObject importGzip(String id,String share,byte[] zipped)throws Exception {
        cloudFile(share);
        if(zipped.length>16*1024*1024)return err("compressed-file-too-large");
        byte[] jsonBytes;try(GZIPInputStream gzip=new GZIPInputStream(new java.io.ByteArrayInputStream(zipped))){jsonBytes=readLimit(gzip,32*1024*1024);}
        JSONObject data=new JSONObject(new String(jsonBytes,StandardCharsets.UTF_8));return importCharacter(id,share,data,jsonBytes);
    }
    /** Accepts Perchance's addCharacter envelope; original JSON remains byte-for-byte semantically intact in storage. */
    private JSONObject importCharacter(String id,String share,JSONObject data,byte[] raw)throws Exception {
        JSONObject ch=data.optJSONObject("addCharacter");
        if(ch==null)ch=data.optJSONObject("character");
        if(ch==null&&data.has("name")&&data.has("roleInstruction"))ch=data;
        if(ch==null)return err("unrecognized-character-envelope: expected addCharacter or character");
        if(ch.optString("name").trim().isEmpty())return err("character-name-missing");
        // Reject unknown IDs, avoiding silent creation of agent identities.
        if(!character(id).optBoolean("ok"))return err("unknown-character");
        android.content.ContentValues v=new android.content.ContentValues();v.put("name",ch.optString("name"));v.put("role_instruction",ch.optString("roleInstruction"));v.put("writing_instruction",ch.optString("writingInstruction"));v.put("reminder",ch.optString("reminderMessage"));v.put("original_json",new String(raw,StandardCharsets.UTF_8));v.put("sha256",sha(raw));v.put("imported_at",System.currentTimeMillis());
        int rows=getWritableDatabase().update("characters",v,"id=?",new String[]{id});
        return ok().put("id",id).put("name",ch.optString("name")).put("bytes",raw.length).put("sha256",sha(raw)).put("updated",rows==1).put("roleInstructionLength",ch.optString("roleInstruction").length());
    }
    public synchronized JSONObject threads()throws Exception{
        JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,participants,created_at,updated_at FROM conversations ORDER BY updated_at DESC",null)){
            while(c.moveToNext())a.put(new JSONObject().put("id",c.getString(0)).put("title",c.getString(1)).put("participants",new JSONArray(c.getString(2))).put("createdAt",c.getLong(3)).put("updatedAt",c.getLong(4)));
        }return ok().put("threads",a);
    }
    private synchronized JSONArray participants(String thread)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT participants FROM conversations WHERE id=?",new String[]{thread})){
            if(!c.moveToFirst())throw new IllegalArgumentException("Unknown conversation");return new JSONArray(c.getString(0));
        }
    }
    private static boolean contains(JSONArray a,String key){for(int i=0;i<a.length();i++)if(key.equals(a.optString(i)))return true;return false;}
    public synchronized JSONObject createThread(JSONObject input)throws Exception {
        JSONArray ids=input.optJSONArray("participants");if(ids==null||ids.length()<1||ids.length()>16)return err("participants-must-be-1-to-16");
        JSONArray unique=new JSONArray();for(int i=0;i<ids.length();i++){String id=ids.optString(i);if(!character(id).optBoolean("ok"))return err("unknown-character-"+id);if(!contains(unique,id))unique.put(id);}
        if(unique.length()==0)return err("no-participants");String id=UUID.randomUUID().toString(),title=input.optString("title").trim();if(title.isEmpty()){StringBuilder s=new StringBuilder();for(int i=0;i<unique.length();i++){if(i>0)s.append(" + ");s.append(character(unique.optString(i)).optString("name"));}title=s.toString();}
        long now=System.currentTimeMillis();android.content.ContentValues row=new android.content.ContentValues();row.put("id",id);row.put("title",title);row.put("participants",unique.toString());row.put("created_at",now);row.put("updated_at",now);getWritableDatabase().insertOrThrow("conversations",null,row);
        return ok().put("threadId",id).put("title",title).put("participants",unique);
    }
    public synchronized JSONObject messages(String thread)throws Exception{
        JSONArray valid=participants(thread),messages=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,speaker,role,body,created_at FROM messages WHERE conversation_id=? ORDER BY id",new String[]{thread})){
            while(c.moveToNext())messages.put(new JSONObject().put("id",c.getLong(0)).put("speaker",c.getString(1)).put("role",c.getString(2)).put("text",c.getString(3)).put("at",c.getLong(4)));
        }return ok().put("threadId",thread).put("participants",valid).put("messages",messages);
    }
    public synchronized JSONObject append(String thread,String speaker,String role,String text)throws Exception{
        JSONArray participants=participants(thread);if(!"user".equals(role)&&!"assistant".equals(role))return err("bad-role");
        if("assistant".equals(role)&&!contains(participants,speaker))return err("agent-not-in-conversation");
        if(text==null||text.trim().isEmpty()||text.length()>100000)return err("message-length-invalid");
        android.content.ContentValues row=new android.content.ContentValues();row.put("conversation_id",thread);row.put("speaker",speaker);row.put("role",role);row.put("body",text);row.put("created_at",System.currentTimeMillis());long id=getWritableDatabase().insertOrThrow("messages",null,row);
        android.content.ContentValues t=new android.content.ContentValues();t.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("conversations",t,"id=?",new String[]{thread});
        return ok().put("messageId",id).put("speaker",speaker).put("role",role).put("text",text);
    }
    /** Single explicitly requested model turn. No unbounded agent loop or unsolicited paid API calls. */
    public JSONObject generateTurn(String thread,String agent,boolean approved)throws Exception {
        if(!approved)return err("explicit-user-approval-required");
        JSONObject ch=character(agent);if(!ch.optBoolean("ok")||!ch.optBoolean("imported"))return err("agent-profile-not-imported");
        JSONArray members=participants(thread);if(!contains(members,agent))return err("agent-not-in-conversation");
        String endpoint=settings.get("aiEndpoint","");String model=settings.get("aiModel","");boolean keyless=endpoint.isEmpty()||model.isEmpty();
        URL u=null;
        if(!keyless){u=new URL(endpoint);String host=u.getHost();if(!"https".equalsIgnoreCase(u.getProtocol())&&!("localhost".equals(host)||"127.0.0.1".equals(host)))return err("https-required-outside-localhost");}
        JSONObject snapshot=messages(thread);JSONArray items=snapshot.getJSONArray("messages");
        StringBuilder instructions=new StringBuilder();instructions.append("You are ").append(ch.optString("name")).append(". Preserve the following character identity and instructions, including its personality:\n").append(ch.optString("roleInstruction"));
        if(!ch.optString("writingInstruction").isEmpty())instructions.append("\nWriting instruction:\n").append(ch.optString("writingInstruction"));
        if(!ch.optString("reminderMessage").isEmpty())instructions.append("\nCharacter reminder:\n").append(ch.optString("reminderMessage"));
        instructions.append("\nYou are participating in a multi-agent chat. Reply as this character only; do not impersonate other participants. Other speakers are identified by name.");
        JSONArray msgs=new JSONArray().put(new JSONObject().put("role","system").put("content",instructions.toString()));
        int begin=items.length(),maxHistory=60000,used=0;
        for(int i=items.length()-1;i>=0;i--){JSONObject m=items.getJSONObject(i);int cost=m.optString("text").length()+m.optString("speaker").length()+8;if(used+cost>maxHistory)break;begin=i;used+=cost;}
        for(int i=begin;i<items.length();i++){
            JSONObject m=items.getJSONObject(i);String from=m.optString("speaker"),text=m.optString("text");
            msgs.put(new JSONObject().put("role",m.optString("role").equals("assistant")&&from.equals(agent)?"assistant":"user").put("content",(from.equals(agent)?"":from+": ")+text));
        }
        // The complete transcript is preserved locally. Provider context omissions are explicitly reported.
        if(keyless){
            StringBuilder combined=new StringBuilder();
            for(int i=0;i<msgs.length();i++){
                JSONObject m=msgs.getJSONObject(i);
                combined.append(m.optString("role")).append(": ").append(m.optString("content")).append("\n");
            }
            combined.append("assistant: ");
            JSONObject inference=PerchanceInference.generate(appContext,combined.toString(),1024);
            if(!inference.optBoolean("ok"))return inference.put("threadId",thread).put("agent",agent);
            String generated=inference.optString("text");
            JSONObject saved=append(thread,agent,"assistant",generated);
            return ok().put("reply",saved).put("model","perchance-free").put("provider","perchance-free")
                .put("contextHistoryMessagesOmitted",begin).put("completeHistoryRetainedOnDevice",true)
                .put("moltbookCreditsSpent",0);
        }
        JSONObject payload=new JSONObject().put("model",model).put("messages",msgs).put("max_tokens",1024);
        HttpURLConnection conn=(HttpURLConnection)u.openConnection();conn.setConnectTimeout(12000);conn.setReadTimeout(100000);conn.setInstanceFollowRedirects(false);conn.setDoOutput(true);conn.setRequestMethod("POST");conn.setRequestProperty("Content-Type","application/json");
        String key=settings.readSecret("aiKey");if(!key.isEmpty())conn.setRequestProperty("Authorization","Bearer "+key);
        try{
            try(OutputStream out=conn.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
            int status=conn.getResponseCode();if(status!=200)return err("provider-http-"+status);
            byte[] replyBytes=readLimit(conn.getInputStream(),4*1024*1024);JSONObject reply=new JSONObject(new String(replyBytes,StandardCharsets.UTF_8));JSONArray choices=reply.optJSONArray("choices");
            if(choices==null||choices.length()==0||choices.optJSONObject(0)==null)return err("provider-no-choices");
            JSONObject msg=choices.getJSONObject(0).optJSONObject("message");String text=msg==null?"":msg.optString("content");if(text.trim().isEmpty())return err("provider-empty-response");
            JSONObject saved=append(thread,agent,"assistant",text);
            return ok().put("reply",saved).put("model",model).put("contextHistoryMessagesOmitted",begin).put("completeHistoryRetainedOnDevice",true).put("usage",reply.optJSONObject("usage"));
        }finally{conn.disconnect();}
    }
}
