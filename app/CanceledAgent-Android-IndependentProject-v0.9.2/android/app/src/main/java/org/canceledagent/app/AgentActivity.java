package org.canceledagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native character-chat workflow; all transcripts and character source data remain device-owned. */
public final class AgentActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private AgentRuntime runtime;private LinearLayout page,chatList,botPicker;private TextView info;private String thread="";
    private final int bg=0xff0b1119,panel=0xff192332,ink=0xffe6eef7,soft=0xffaabccf,teal=0xff3fccba;
    private final LinkedHashMap<String,CheckBox> selected=new LinkedHashMap<>();
    private String pendingFileAgent="";
    @Override public void onCreate(Bundle saved){super.onCreate(saved);runtime=new AgentRuntime(this);show();}
    @Override public void onDestroy(){io.shutdownNow();runtime.close();super.onDestroy();}
    private TextView text(LinearLayout p,String val,int size,int color){TextView x=new TextView(this);x.setText(val);x.setTextSize(size);x.setTextColor(color);x.setPadding(7,9,7,9);p.addView(x);return x;}
    private LinearLayout panel(String title){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(12,12,12,13);p.setBackgroundColor(panel);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,8,0,10);page.addView(p,lp);text(p,title,17,teal).setTypeface(Typeface.DEFAULT,Typeface.BOLD);return p;}
    private Button btn(LinearLayout p,String title,Runnable r){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(ink);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff20626b));p.addView(b);b.setOnClickListener(v->r.run());return b;}
    private EditText input(LinearLayout p,String hint,boolean multi){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(soft);e.setTextColor(ink);e.setSingleLine(!multi);if(multi)e.setMinLines(2);p.addView(e);return e;}
    private void update(String msg){runOnUiThread(()->{if(info!=null)info.setText(msg);});}
    private interface Action{JSONObject run()throws Exception;}
    private void work(String label,Action task,Runnable afterward){update(label);io.execute(()->{try{JSONObject result=task.run();update(result.toString(2));if(afterward!=null)runOnUiThread(afterward);}catch(Exception ex){update("ERROR "+ex.getClass().getSimpleName()+": "+ex.getMessage());}});}
    private void show(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(12,9,12,10);root.setBackgroundColor(bg);setContentView(root);
        text(root,"CANCELED AGENT / CHARACTER NETWORK",20,teal).setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        btn(root,"Back to workspaces",this::finish);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);scroll.addView(page);
        LinearLayout registry=panel("Character identities & source import");
        text(registry,"Shared profiles are downloaded as gzip JSON and preserved locally. Imported source does not execute scripts. Chat generation uses your independent AI provider.",13,soft);
        for(String[] data:AgentRuntime.INITIAL){
            String id=data[0];LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);registry.addView(item);
            btn(item,"View "+data[1],()->work("Inspecting "+id,()->runtime.character(id),null));
            btn(item,"Import/refresh "+data[1]+" from share URL",()->work("Downloading and verifying "+id,()->runtime.downloadAndImport(id),this::loadStatus));
            btn(item,"Import "+data[1]+" from saved .gz",()->{pendingFileAgent=id;Intent chooser=new Intent(Intent.ACTION_OPEN_DOCUMENT);chooser.setType("*/*");chooser.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(chooser,47);});
        }
        btn(registry,"Refresh character status",this::loadStatus);
        LinearLayout creation=panel("New independent conversation");
        text(creation,"Select one character for a private thread or several for a shared conversation. Select a specific bot to take each AI turn.",13,soft);
        botPicker=new LinearLayout(this);botPicker.setOrientation(LinearLayout.VERTICAL);creation.addView(botPicker);
        for(String[] bot:AgentRuntime.INITIAL){CheckBox c=new CheckBox(this);c.setText(bot[1]);c.setTextColor(ink);botPicker.addView(c);selected.put(bot[0],c);}
        EditText title=input(creation,"Conversation title (optional)",false);
        btn(creation,"Create conversation",()->{
            JSONArray members=new JSONArray();for(Map.Entry<String,CheckBox> item:selected.entrySet())if(item.getValue().isChecked())members.put(item.getKey());
            if(members.length()==0){update("Select at least one character");return;}
            work("Creating conversation",()->runtime.createThread(new JSONObject().put("participants",members).put("title",title.getText().toString())),this::loadThreads);
        });
        LinearLayout history=panel("Conversations on this device");btn(history,"Refresh conversations",this::loadThreads);chatList=new LinearLayout(this);chatList.setOrientation(LinearLayout.VERTICAL);history.addView(chatList);
        info=text(root,"Ready. No character profile is imported until its source is fetched and verified.",12,soft);info.setTextIsSelectable(true);
        loadStatus();loadThreads();
    }
    private void loadStatus(){work("Loading profile status",()->runtime.agents(),null);}
    private void loadThreads(){io.execute(()->{try{JSONArray a=runtime.threads().getJSONArray("threads");runOnUiThread(()->{
        chatList.removeAllViews();if(a.length()==0)text(chatList,"No conversations yet",13,soft);
        for(int i=0;i<a.length();i++){JSONObject item=a.optJSONObject(i);if(item==null)continue;btn(chatList,item.optString("title")+"  ("+item.optJSONArray("participants").length()+" agents)",()->openConversation(item.optString("id"),item.optString("title"),item.optJSONArray("participants")));}
    });}catch(Exception e){update(e.toString());}});}
    private void openConversation(String id,String title,JSONArray participants){thread=id;
        LinearLayout holder=new LinearLayout(this);holder.setOrientation(LinearLayout.VERTICAL);holder.setBackgroundColor(bg);holder.setPadding(12,12,12,12);setContentView(holder);
        text(holder,"CONVERSATION / "+title,18,teal);btn(holder,"Back to character network",this::show);
        ScrollView sc=new ScrollView(this);holder.addView(sc,new LinearLayout.LayoutParams(-1,0,1));LinearLayout messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);sc.addView(messages);
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);holder.addView(bottom);
        EditText composer=input(bottom,"Write a message for the conversation",true);
        TextView state=text(bottom,"Independent provider; no automatic paid tasks",12,soft);
        btn(bottom,"Send my message",()->{
            String content=composer.getText().toString();work("Saving message",()->runtime.append(id,"user","user",content),()->{composer.setText("");refreshConversation(id,messages,sc);});
        });
        for(String[] bot:AgentRuntime.INITIAL){
            if(!arrayHas(participants,bot[0]))continue;
            btn(bottom,"Ask "+bot[1]+" to reply",()->new AlertDialog.Builder(this).setTitle("Generate one response as "+bot[1]+"?")
                .setMessage("This uses your configured independent inference provider. The provider may charge for the request. No Moltbook post will be created.")
                .setNegativeButton("Cancel",null).setPositiveButton("Generate",(d,w)->{
                    state.setText("Requesting response as "+bot[1]);
                    work("Generating as "+bot[1],()->runtime.generateTurn(id,bot[0],true),()->refreshConversation(id,messages,sc));
                }).show());
        }
        if(participants!=null&&participants.length()>1){
            btn(bottom,"Run one round: every selected agent replies",()->new AlertDialog.Builder(this)
                .setTitle("Run "+participants.length()+" independent AI calls?")
                .setMessage("Each selected agent receives the preceding agents' responses. Up to one response per agent, in order. Your configured inference provider may charge separately for each call. No Moltbook posts are made.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Run round",(dialog,which)->{
                    io.execute(()->{
                        try{
                            for(int i=0;i<participants.length();i++){
                                String agent=participants.getString(i);
                                JSONObject status=runtime.character(agent);
                                if(!status.optBoolean("imported")){update("Round stopped: "+agent+" has not been imported. No additional calls made.");return;}
                            }
                            for(int i=0;i<participants.length();i++){
                                String agent=participants.getString(i);
                                update("Agent round "+(i+1)+"/"+participants.length()+" · "+agent);
                                JSONObject response=runtime.generateTurn(id,agent,true);
                                if(!response.optBoolean("ok")){update("Round stopped at "+agent+": "+response.toString());return;}
                                runOnUiThread(()->refreshConversation(id,messages,sc));
                            }
                            update("Agent round complete: "+participants.length()+" turns saved. No Moltbook operations.");
                        }catch(Exception ex){update("Agent round stopped: "+ex.getMessage());}
                    });
                }).show());
        }
        info=state;refreshConversation(id,messages,sc);
    }
    private static boolean arrayHas(JSONArray a,String id){if(a==null)return false;for(int i=0;i<a.length();i++)if(id.equals(a.optString(i)))return true;return false;}
    private void refreshConversation(String id,LinearLayout target,ScrollView scroll){io.execute(()->{try{JSONObject result=runtime.messages(id);JSONArray arr=result.getJSONArray("messages");JSONArray members=result.getJSONArray("participants");runOnUiThread(()->{
        target.removeAllViews();text(target,"Participants: "+members.toString(),12,soft);
        for(int i=0;i<arr.length();i++){JSONObject msg=arr.optJSONObject(i);if(msg==null)continue;
            LinearLayout b=new LinearLayout(this);b.setPadding(9,9,9,11);b.setOrientation(LinearLayout.VERTICAL);b.setBackgroundColor(msg.optString("role").equals("user")?0xff283b4a:panel);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,6,0,6);target.addView(b,lp);
            text(b,msg.optString("speaker"),13,teal);TextView content=text(b,msg.optString("text"),15,ink);content.setTextIsSelectable(true);
        }
        scroll.post(()->scroll.fullScroll(android.view.View.FOCUS_DOWN));
    });}catch(Exception ex){update(ex.toString());}});}
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);
        if(request!=47||result!=RESULT_OK||intent==null||intent.getData()==null)return;
        final String id=pendingFileAgent;final android.net.Uri uri=intent.getData();
        work("Importing original compressed character data",()->{
            try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){out.write(block,0,n);if(out.size()>16*1024*1024)throw new IllegalArgumentException("Compressed profile too large");}
                String link=runtime.character(id).optString("shareUrl");return runtime.importGzip(id,link,out.toByteArray());
            }
        },this::loadStatus);
    }
}
