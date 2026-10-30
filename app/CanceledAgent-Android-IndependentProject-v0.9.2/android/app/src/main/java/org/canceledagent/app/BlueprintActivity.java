package org.canceledagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline Blueprint lifecycle: configure -> adopt -> verify -> revise -> rollback. */
public final class BlueprintActivity extends Activity {
    private BlueprintProtocol protocols;private LocalProjects projects;private WorkflowProfile profile;
    private Spinner project;private EditText source;private TextView report;private String[] ids;
    private LinearLayout body;
    @Override public void onCreate(Bundle b){super.onCreate(b);protocols=new BlueprintProtocol(this);projects=new LocalProjects(this);profile=new WorkflowProfile(this);
        ScrollView sc=new ScrollView(this);body=new LinearLayout(this);body.setPadding(18,14,18,24);body.setOrientation(1);body.setBackgroundColor(Color.rgb(11,17,25));sc.addView(body);setContentView(sc);
        label("BLUEPRINT · Project protocol",21);
        label("Executable acceptance lifecycle: generate or write Blueprint JSON checks, adopt, verify the actual project source, inspect audit and roll back. Unstructured text can be archived but cannot pass verification. No remote instruction execution.",14);
        LinearLayout modes=new LinearLayout(this);modes.setOrientation(0);body.addView(modes);
        for(String mode:new String[]{"Core","Full"})btn(modes,mode,()->{profile.mode(mode);report("Mode: "+profile.mode());});
        for(String key:BlueprintProtocol.OVERLAYS){CheckBox cb=new CheckBox(this);cb.setText("Enable "+key+" workflow overlay");cb.setTextColor(Color.WHITE);cb.setChecked(profile.overlay(key));cb.setOnCheckedChangeListener((v,on)->profile.overlay(key,on));body.addView(cb);}
        label("Project",17);project=new Spinner(this);body.addView(project);refresh();btn(body,"Refresh project list",this::refresh);
        label("Protocol source (editable, stored locally with SHA-256)",16);source=new EditText(this);source.setMinLines(6);source.setGravity(48);source.setSingleLine(false);source.setTextColor(Color.WHITE);
        source.setHint("Paste protocol text you own or authored, then adopt it.");source.setHintTextColor(0xffa5b2c1);body.addView(source);
        btn(body,"Generate executable baseline from selected project's saved source",()->{
            String id=chosen();if(id==null)return;
            try{JSONObject saved=projects.project(id);if(saved==null)throw new IllegalArgumentException("Project not found");
                source.setText(BlueprintAcceptance.baseline(id,saved.optString("source"),saved.optInt("revision")).toString(2));
                report("Baseline generated. Review checks, adopt it, then run verification against the saved revision.");
            }catch(Exception e){report("Baseline failed: "+e.getMessage());}
        });
        btn(body,"Adopt selected project protocol",()->{
            String id=chosen();if(id==null)return;try{String p=protocols.adopt(id,source.getText().toString());report("Adopted "+p+" · sha256 "+BlueprintProtocol.sha256(source.getText().toString()));}catch(Exception e){report(e.getMessage());}
        });
        btn(body,"Verify current revision against adopted protocol",()->withProtocol(false));
        btn(body,"Inspect protocol and revision audit",()->{String id=chosen();if(id!=null)report("Adoptions:\n"+protocols.protocols(id).toString()+"\nChecks:\n"+protocols.audit(id));});
        btn(body,"Open local project revision/rollback history",()->{String id=chosen();if(id==null)return;JSONArray revisions=projects.revisions(id);String[] choices=new String[revisions.length()];for(int i=0;i<choices.length;i++)choices[i]="Revision "+revisions.optJSONObject(i).optInt("revision");
            new AlertDialog.Builder(this).setTitle("Choose revision to restore").setItems(choices,(d,i)->new AlertDialog.Builder(this).setMessage("Create a new revision using old source? All subsequent revisions remain available.")
            .setPositiveButton("Restore",(x,y)->{try{JSONObject old=projects.project(id);int rev=projects.revert(id,revisions.optJSONObject(i).optInt("revision"),old.optInt("revision"));report("Restored as revision "+rev);}catch(Exception e){report(e.toString());}}).setNegativeButton("Cancel",null).show()).show();});
        report=new TextView(this);report.setTextColor(0xff88e9c8);report.setTextIsSelectable(true);report.setPadding(12,14,12,14);report.setText("Ready · " + profile.mode() + " mode");body.addView(report);
    }
    private void withProtocol(boolean unused){String id=chosen();if(id==null)return;JSONArray all=protocols.protocols(id);String[] choices=new String[all.length()];for(int i=0;i<choices.length;i++)choices[i]=all.optJSONObject(i).optString("id");
        new AlertDialog.Builder(this).setTitle("Verify adopted protocol").setItems(choices,(d,which)->{try{report(protocols.verify(id,choices[which],projects).toString(2));}catch(Exception e){report(e.getMessage());}}).show();}
    private void refresh(){JSONArray all=projects.projects();ids=new String[all.length()];String[] names=new String[all.length()];for(int i=0;i<all.length();i++){JSONObject p=all.optJSONObject(i);ids[i]=p.optString("id");names[i]=p.optString("name")+" · "+ids[i];}project.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));}
    private String chosen(){int i=project.getSelectedItemPosition();if(ids==null||i<0||i>=ids.length){report("Create a project in Studio first");return null;}return ids[i];}
    private void report(String s){if(report!=null)report.setText(s);}
    private void label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xffdbeaf6);t.setPadding(4,10,4,10);body.addView(t);}
    private void btn(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);parent.addView(b);b.setOnClickListener(v->action.run());}
}
