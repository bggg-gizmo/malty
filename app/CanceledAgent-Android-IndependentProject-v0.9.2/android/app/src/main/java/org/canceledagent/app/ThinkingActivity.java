package org.canceledagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Full device-owned trace chain: pivots, linked snapshots, ratings, reversible revisions and feedback export. */
public final class ThinkingActivity extends Activity {
    private LocalProjects db;private String project;private LinearLayout listing;private TextView status;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        db=new LocalProjects(this);project=getIntent().getStringExtra("projectId");
        if(project==null||db.project(project)==null){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);setContentView(root);
        status=new TextView(this);status.setText("Thinking / backstep pipeline");root.addView(status);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);root.addView(actions);
        button(actions,"Think",()->record("think"));button(actions,"Action",()->record("action"));
        button(actions,"Change + snapshot",()->record("change"));button(actions,"Backstep",()->record("backstep"));
        button(actions,"Copy ratings",()->{ClipboardManager m=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);m.setPrimaryClip(ClipData.newPlainText("Thinking feedback",db.feedbackExport(project)));status.setText("Rated feedback copied including trace back-chains");});
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        listing=new LinearLayout(this);listing.setOrientation(LinearLayout.VERTICAL);scroll.addView(listing);refresh();
    }
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);parent.addView(b);b.setOnClickListener(v->action.run());}
    private void record(String kind){
        EditText content=new EditText(this);content.setHint("What changed, why, or what should be reconsidered?");
        EditText label=new EditText(this);label.setHint("Optional change label");
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.addView(content);fields.addView(label);
        new AlertDialog.Builder(this).setTitle("Record "+kind).setView(fields).setNegativeButton("Cancel",null)
            .setPositiveButton("Record",(dlg,w)->{
                String note=content.getText().toString().trim();if(note.isEmpty()){status.setText("Nothing recorded: text required");return;}
                JSONArray history=db.detailedTrace(project);String parent=history.length()==0?null:history.optJSONObject(history.length()-1).optString("id");
                JSONObject p=db.project(project);
                String snapshot="change".equals(kind)||"backstep".equals(kind)?p.optString("source"):null;
                db.pipe(project,kind,note,parent,"backstep".equals(kind),label.getText().toString(),snapshot);
                refresh();
            }).show();
    }
    private void rate(JSONObject item,int direction){
        EditText feedback=new EditText(this);feedback.setHint("Feedback for this pivot");feedback.setText(item.optString("feedback"));
        new AlertDialog.Builder(this).setTitle(direction>0?"Accept pivot":"Reject pivot").setView(feedback)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
                db.rate(project,item.optString("id"),direction,feedback.getText().toString());refresh();
            }).show();
    }
    private void restore(JSONObject item){
        new AlertDialog.Builder(this).setTitle("Restore linked snapshot?")
          .setMessage("Restoration will create a NEW project revision. No previous version or trace is deleted.")
          .setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->{
              try{JSONObject p=db.project(project);int rev=db.restoreTraceSnapshot(project,item.optString("id"),p.optInt("revision"));
                  status.setText("Restored snapshot as revision "+rev+". Reopen the editor to view it.");
              }catch(Exception ex){status.setText("Restore failed: "+ex.getMessage());}
          }).show();
    }
    private void refresh(){
        listing.removeAllViews();JSONArray history=db.detailedTrace(project);
        for(int i=0;i<history.length();i++){
            JSONObject item=history.optJSONObject(i);LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(12,12,12,18);listing.addView(card);
            TextView text=new TextView(this);text.setText("#"+item.optInt("seq")+"  "+item.optString("kind")+"  "+item.optString("changeLabel")+"\n"+item.optString("text")+"\nID: "+item.optString("id")+"  parent: "+item.optString("parentId")+"  rating: "+item.optInt("rating"));card.addView(text);
            LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);card.addView(controls);
            button(controls,"👍",()->rate(item,1));button(controls,"👎",()->rate(item,-1));
            button(controls,"Clear rating",()->{db.rate(project,item.optString("id"),0,"");refresh();});
            if(!item.isNull("snapshot"))button(controls,"Restore snapshot",()->restore(item));
        }
        status.setText("Thinking pipeline · "+history.length()+" entries · project "+project);
    }
}
