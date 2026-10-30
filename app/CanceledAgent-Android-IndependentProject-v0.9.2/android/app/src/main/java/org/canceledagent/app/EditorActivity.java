package org.canceledagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Native edit + local sandboxed preview; WebView renders only user-authored HTML.
 *  It NEVER loads the Perchance dashboard or any remote generator.
 */
public final class EditorActivity extends Activity {
    private static final int EXPORT_FILE=107;
    private static final int IMPORT_FILE=108;
    private LocalProjects local;
    private SecurePrefs settings;
    private String id,name;
    private int revision;
    private EditText editor;
    private WebView preview;
    private TouchBridgeController touchBridge;
    private TextView status;
    private LiveRooms rooms;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Runnable pending;
    private boolean remoteUpdate=false;
    private String availableRemote=null;
    private String lastSaved="";
    private static final String STARTER="<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body><h1>Independent Studio</h1><p>Edit the HTML to update this live preview.</p></body></html>";
    @Override public void onCreate(Bundle b){
        super.onCreate(b);local=new LocalProjects(this);settings=new SecurePrefs(this);
        id=getIntent().getStringExtra("projectId");
        JSONObject p=id==null?null:local.project(id);
        if(p==null){name="Untitled project";id=local.create(name,STARTER);p=local.project(id);}
        name=p.optString("name");revision=p.optInt("revision");lastSaved=p.optString("source");
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(12,8,12,8);
        setContentView(page);
        TextView title=new TextView(this);title.setText("Studio / "+name);title.setTextSize(19);page.addView(title);
        status=new TextView(this);status.setText("Offline project · revision "+revision);page.addView(status);
        ScrollView bar=new ScrollView(this);bar.setHorizontalScrollBarEnabled(true);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(actions);page.addView(bar);
        button(actions,"Save",v->save());button(actions,"Versions",v->versions());
        button(actions,"Export HTML",v->exportFile());button(actions,"Import HTML",v->importFile());
        button(actions,"Copy source",v->{android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(android.content.ClipData.newPlainText(name,editor.getText()));status("Source copied");});
        button(actions,"Share",v->share());button(actions,"Trace",v->trace());
        button(actions,"Thinking History",v->{Intent i=new Intent(this,ThinkingActivity.class);i.putExtra("projectId",id);startActivity(i);});
        button(actions,"Canvas Editor",v->{save();Intent i=new Intent(this,CanvasEditorActivity.class);i.putExtra("projectId",id);startActivity(i);});
        button(actions,"Blueprint",v->startActivity(new Intent(this,BlueprintActivity.class)));
        button(actions,"Join room",v->joinRoom());button(actions,"Load remote",v->loadRemote());button(actions,"Save room",v->{if(rooms!=null)rooms.save(editor.getText().toString());else status("Join a room first");});
        editor=new EditText(this);editor.setSingleLine(false);editor.setTextSize(13);editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setGravity(android.view.Gravity.TOP);editor.setHorizontallyScrolling(true);editor.setText(lastSaved);
        page.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
        preview=new WebView(this);WebSettings s=preview.getSettings();s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setBlockNetworkLoads(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);s.setDomStorageEnabled(false);
        preview.setBackgroundColor(Color.WHITE);
        preview.setWebViewClient(new android.webkit.WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){if(touchBridge!=null)touchBridge.installBridge();}
        });
        page.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout pad=new LinearLayout(this);pad.setOrientation(LinearLayout.VERTICAL);pad.setGravity(android.view.Gravity.CENTER);page.addView(pad);
        touchBridge=new TouchBridgeController(this,preview,pad);touchBridge.show();
        
        editor.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                if(pending!=null)handler.removeCallbacks(pending);
                String html=s.toString();pending=()->{
                    render(html);
                    if(rooms!=null&&!remoteUpdate)rooms.edit(html);
                    if(!remoteUpdate)status("Unsaved changes · revision "+revision);
                };handler.postDelayed(pending,380);
            }
            public void afterTextChanged(Editable e){}
        });
        render(lastSaved);
    }
    private void button(LinearLayout p,String label,View.OnClickListener action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(action);p.addView(b);
    }
    private void status(String s){status.setText(s);}
    private void render(String html){preview.loadDataWithBaseURL("https://appassets.androidplatform.net/preview/",html,"text/html","UTF-8",null);}
    private void sendKey(String key){
        String q=JSONObject.quote(key);
        preview.evaluateJavascript("(function(){let k="+q+";document.dispatchEvent(new KeyboardEvent('keydown',{key:k,bubbles:true}));document.dispatchEvent(new KeyboardEvent('keyup',{key:k,bubbles:true}));})()",null);
    }
    private void save(){
        try{
            String src=editor.getText().toString();revision=local.save(id,name,src,"Saved from Android editor",revision);lastSaved=src;
            status("Saved locally · revision "+revision);
        }catch(Exception ex){status("Save failed: "+ex.getMessage());}
    }
    private void versions(){
        JSONArray arr=local.revisions(id);String[] options=new String[arr.length()];int[] indexes=new int[arr.length()];
        for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);options[i]="Revision "+o.optInt("revision")+" · "+o.optString("label");indexes[i]=o.optInt("revision");}
        new AlertDialog.Builder(this).setTitle("Version history · revert creates a new revision").setItems(options,(d,which)->{
            new AlertDialog.Builder(this).setTitle("Revert source?")
                 .setMessage("The current content will be preserved in version history.")
                 .setNegativeButton("Cancel",null).setPositiveButton("Revert",(di,w)->{
                     try{revision=local.revert(id,indexes[which],revision);JSONObject p=local.project(id);
                         lastSaved=p.optString("source");editor.setText(lastSaved);render(lastSaved);status("Reverted · new revision "+revision);
                     }catch(Exception ex){status("Revert failed: "+ex.getMessage());}
                 }).show();
        }).show();
    }
    private void exportFile(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("text/html");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,name.replaceAll("[^a-zA-Z0-9_-]","-")+".html");startActivityForResult(i,EXPORT_FILE);}
    private void importFile(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("text/html");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT_FILE);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        try{
            if(request==EXPORT_FILE){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));}status("HTML exported");}
            if(request==IMPORT_FILE){byte[] bytes;try(java.io.InputStream in=getContentResolver().openInputStream(data.getData());java.io.ByteArrayOutputStream buf=new java.io.ByteArrayOutputStream()){byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))>=0)buf.write(chunk,0,n);bytes=buf.toByteArray();}editor.setText(new String(bytes,StandardCharsets.UTF_8));status("Imported; save to create a revision");}
        }catch(Exception ex){status("File operation failed: "+ex.getMessage());}
    }
    private void share(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,editor.getText().toString());startActivity(Intent.createChooser(i,"Share project source"));}
    private void trace(){
        JSONArray existing=local.trace(id);StringBuilder sb=new StringBuilder();
        for(int i=0;i<existing.length();i++){JSONObject e=existing.optJSONObject(i);sb.append("#").append(e.optInt("seq")).append(" ").append(e.optString("kind")).append(": ").append(e.optString("text")).append("\n");}
        EditText note=new EditText(this);note.setHint("Decision, correction, or reverted assumption");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.addView(note);
        TextView history=new TextView(this);history.setText(sb.toString());layout.addView(history);
        new AlertDialog.Builder(this).setTitle("Thinking / backstep journal").setView(layout)
            .setPositiveButton("Record thought",(d,w)->{if(!note.getText().toString().trim().isEmpty())local.thought(id,"think",note.getText().toString(),null);})
            .setNeutralButton("Backstep latest",(d,w)->{
                if(note.getText().toString().trim().isEmpty())return;
                JSONArray a=local.trace(id);String parent=a.length()==0?null:a.optJSONObject(a.length()-1).optString("id");
                local.thought(id,"backstep",note.getText().toString(),parent);
            }).setNegativeButton("Close",null).show();
    }
    private void loadRemote(){
        if(availableRemote==null){status("No room document received yet");return;}
        new AlertDialog.Builder(this).setTitle("Load live room source")
            .setMessage("Your local source will be preserved as a new revision before replacement. Remote room documents are not automatically merged.")
            .setNegativeButton("Cancel",null).setPositiveButton("Save local and load",(d,w)->{
                try{
                    save();
                    remoteUpdate=true;editor.setText(availableRemote);remoteUpdate=false;
                    render(availableRemote);status("Loaded remote room source. Save to persist locally.");
                }catch(Exception ex){remoteUpdate=false;status("Unable to load remote: "+ex.getMessage());}
            }).show();
    }
    private void joinRoom(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        EditText room=new EditText(this);room.setSingleLine();room.setHint("Room");room.setText(settings.get("room","lobby"));form.addView(room);
        EditText nick=new EditText(this);nick.setSingleLine();nick.setHint("Nickname");nick.setText(settings.get("nick","molty"));form.addView(nick);
        new AlertDialog.Builder(this).setTitle("Join live collaboration room").setView(form).setNegativeButton("Cancel",null)
            .setPositiveButton("Join",(d,w)->{
                settings.put("room",room.getText().toString());settings.put("nick",nick.getText().toString());
                if(rooms!=null)rooms.leave();
                rooms=new LiveRooms(this,new LiveRooms.Listener(){
                    @Override public void state(String s){status(s);}
                    @Override public void event(JSONObject event){
                        String kind=event.optString("t");
                        if("doc".equals(kind)){
                            String html=event.optString("html");
                            availableRemote=html;
                            status("Room joined. Participants: "+event.optJSONArray("who")+". Use Load remote to merge or replace.");
                        }else if("edit".equals(kind)){
                            // Remote edits apply to preview immediately; keep local work safe.
                            String html=event.optString("html");availableRemote=html;render(html);
                            status("Live remote preview from "+event.optString("nick")+" · use Load remote to accept");
                        }else if("rpc_result".equals(kind))status("Room save: "+event.optJSONObject("result"));
                        else if("presence".equals(kind))status("Room members: "+event.optJSONArray("who"));
                    }
                });
                rooms.join(settings.get("backend","http://127.0.0.1:8000"),room.getText().toString(),nick.getText().toString(),RuntimeSecrets.local(settings.get("backend","http://127.0.0.1:8000"))?RuntimeSecrets.ensure(this):settings.readSecret("backendToken"));
            }).show();
    }
    @Override public void onBackPressed(){
        if(!editor.getText().toString().equals(lastSaved)){
            new AlertDialog.Builder(this).setMessage("Unsaved changes. Leave editor without saving?")
              .setNegativeButton("Stay",null).setPositiveButton("Discard changes",(d,w)->super.onBackPressed()).show();
        }else super.onBackPressed();
    }
    @Override protected void onDestroy(){if(rooms!=null)rooms.leave();preview.destroy();super.onDestroy();}
}
