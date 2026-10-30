package org.canceledagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Android-native workflow front end, independent of Perchance's generator UI. */
public final class MainActivity extends Activity {
    private SecurePrefs settings;
    private LocalProjects local;
    private Network api;
    private LinearLayout page;
    private TextView output;
    private String current="Home";
    private ChallengeSolver solver;
    private final int bg=Color.rgb(11,17,25),panel=Color.rgb(24,35,48),foreground=Color.rgb(226,237,247),accent=Color.rgb(62,208,189);
    @Override public void onCreate(Bundle b){super.onCreate(b);settings=new SecurePrefs(this);local=new LocalProjects(this);api=new Network(this,settings);EmbeddedService.launch(this);show("Home");}
    private LinearLayout group(LinearLayout parent){LinearLayout g=new LinearLayout(this);g.setOrientation(LinearLayout.VERTICAL);parent.addView(g);return g;}
    private void text(LinearLayout parent,String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(size>=18?accent:foreground);v.setPadding(7,10,7,10);if(size>=18)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);parent.addView(v);}
    private EditText input(LinearLayout parent,String hint,String value,boolean multiline){
        EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setSingleLine(!multiline);
        if(multiline)e.setMinLines(3);
        e.setTextColor(foreground);e.setHintTextColor(Color.rgb(156,175,197));
        parent.addView(e);return e;
    }
    private Button button(LinearLayout parent,String label,Runnable click){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(foreground);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(31,83,97)));
        b.setOnClickListener(v->click.run());parent.addView(b);return b;
    }
    private Network.Result result(){return new Network.Result(){public void success(JSONObject j){print(j.toString());}public void failure(String e){print("ERROR: "+e);}};}
    private void print(String msg){output.setText(msg);}
    private String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(java.io.UnsupportedEncodingException ex){return "";}}
    private JSONObject json(String... fields){JSONObject j=new JSONObject();try{for(int i=0;i<fields.length;i+=2)j.put(fields[i],fields[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return j;}
    private void show(String selected){
        current=selected;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);setContentView(root);
        text(root,"MALTY  ·  INDEPENDENT",20);
        HorizontalScrollView navScroll=new HorizontalScrollView(this);root.addView(navScroll);
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);navScroll.addView(nav);
        for(String item:new String[]{"Home","Conversations","Studio","Operations","Exchange","Settings"}){
            Button b=button(nav,item,()->show(item));
            if(item.equals(selected)){b.setBackgroundTintList(ColorStateList.valueOf(accent));b.setTextColor(bg);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);}
        }
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(16,10,16,12);scroll.addView(page);
        switch(selected){
            case "Home":home();break;
            case "Conversations":conversations();break;
            case "Studio":studio();break;
            case "Operations":operations();break;
            case "Exchange":exchange();break;
            case "Settings":settings();break;
        }
        text(root,"Response / operation log",13);
        output=new TextView(this);output.setText("Ready · "+selected);output.setTextSize(13);output.setTextIsSelectable(true);
        output.setTextColor(foreground);output.setBackgroundColor(panel);output.setPadding(16,12,16,12);
        ScrollView responseScroll=new ScrollView(this);responseScroll.addView(output);root.addView(responseScroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void openFlow(String screen){Intent intent=new Intent(this,WorkflowActivity.class);intent.putExtra("screen",screen);startActivity(intent);}
    private void home(){
        text(page,"System status and independent inference",18);
        button(page,"Check independent service health",()->api.backend("GET","/health",null,result()));
        button(page,"Run read-only on-device integration diagnostics",this::diagnose);
        button(page,"Run authenticated live-service READ-ONLY checks",this::liveServices);
        button(page,"Run isolated Android service integration suite (no paid requests)",()->{
            print("Executing isolated SQLite, HTTP and WebSocket tests…");
            new Thread(()->{JSONObject result=DeviceIntegrationTests.run(getApplicationContext());runOnUiThread(()->{print(result.toString());try{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Canceled Agent integration report",result.toString(2)));}catch(Exception ignored){}});},"device-integration-tests").start();
        });
        button(page,"Check configured AI provider",()->api.backend("GET","/api/ai/status",null,result()));
        text(page,"Inference — self-hosted or OpenAI-compatible provider, with keyless Perchance fallback",14);
        EditText prompt=input(page,"Ask AI","",true);
        button(page,"Generate",()->{
            try{
                JSONObject j=new JSONObject().put("prompt",prompt.getText().toString()).put("maxTokens",500);
                api.backend("POST","/api/ai/generate",j,result());
            }catch(Exception ex){print(ex.toString());}
        });
        text(page,"Local workflow mode",16);
        button(page,"Core",()->{new WorkflowProfile(this).mode("Core");print("Core workflow mode selected");});
        button(page,"Full",()->{new WorkflowProfile(this).mode("Full");print("Full workflow mode selected");});
        text(page,"Selected: "+settings.get("mode","Full")+". Project source and changes are stored locally.",13);
        button(page,"Autonomy scheduler and governor",()->openFlow("autonomy"));
        button(page,"Blueprint protocol lifecycle",()->startActivity(new Intent(this,BlueprintActivity.class)));
        button(page,"Bridge and team activity",()->openFlow("bridge"));
        text(page,"Governor limits (source contract): reserve 2 · create gap 70 seconds · auto replies 3/day · folds 6/day. Automated paid posting is disabled by default; enabling it requires owner authorization and governor checks.",13);
    }
    /** Executes real authenticated read-only Moltbook calls and local Bridge/provider checks; never publishes. */
    private void liveServices(){
        final String[] paths={"/agents/status","/agents/me","/home","/feed?sort=new&limit=5","/submolts"};
        final org.json.JSONArray tests=new org.json.JSONArray();
        liveMoltbookNext(paths,0,tests);
    }
    private void liveMoltbookNext(String[] paths,int index,org.json.JSONArray tests){
        if(index>=paths.length){
            api.backend("GET","/api/workflows/bridge/status",null,new Network.Result(){
                public void success(JSONObject result){try{tests.put(new JSONObject().put("operation","local-bridge-status").put("ok",result.optBoolean("ok",true)));}catch(Exception ignored){}finishLiveDiagnostics(tests);}
                public void failure(String error){try{tests.put(new JSONObject().put("operation","local-bridge-status").put("ok",false).put("error",error));}catch(Exception ignored){}finishLiveDiagnostics(tests);}
            });return;
        }
        String path=paths[index];api.moltbook("GET",path,null,new Network.Result(){
            public void success(JSONObject result){
                try{tests.put(new JSONObject().put("operation",path).put("ok",result.optInt("httpStatus")==200)
                      .put("httpStatus",result.optInt("httpStatus")));}catch(Exception ignored){}
                liveMoltbookNext(paths,index+1,tests);
            }
            public void failure(String error){try{tests.put(new JSONObject().put("operation",path).put("ok",false).put("error",error));}catch(Exception ignored){}
                liveMoltbookNext(paths,index+1,tests);
            }
        });
    }
    private void finishLiveDiagnostics(org.json.JSONArray tests){
        try{
            int pass=0;for(int i=0;i<tests.length();i++)if(tests.getJSONObject(i).optBoolean("ok"))pass++;
            String bridgeState=settings.get("bridge-status","offline");
            tests.put(new JSONObject().put("operation","outbound-bridge-session").put("configured",settings.get("bridge-outbound","false").equals("true"))
                .put("connected",bridgeState.startsWith("registered:")).put("state",bridgeState));
            JSONObject result=new JSONObject().put("schema","canceled-agent/live-readonly/v1").put("liveRequestsAttempted",true)
                .put("remoteWrites",0).put("paidCalls",0).put("passed",pass).put("checked",tests.length()).put("tests",tests);
            print(result.toString(2));
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Canceled Agent live service tests",result.toString(2)));
        }catch(Exception e){print("Live diagnostics report error: "+e.getMessage());}
    }
    /** Exercises the Android-hosted service over its actual authenticated HTTP transport. No test records are created. */
    private void diagnose(){
        String[] endpoints={"/health","/api/rooms","/api/votes","/api/workflows/catalog","/api/workflows/autonomy","/api/workflows/bridge/status","/api/workflows/lab/oracles","/api/workflows/radio/dial"};
        StringBuilder log=new StringBuilder("Android embedded-service integration diagnostics\nRead-only transport, authorization, routing, and SQLite checks.\n");
        diagnosticsNext(endpoints,0,0,log);
    }
    private void diagnosticsNext(String[] endpoints,int index,int passed,StringBuilder log){
        if(index>=endpoints.length){
            log.append("\nCompleted: ").append(passed).append('/').append(endpoints.length).append(" live HTTP checks passed");
            JSONArray projects=local.projects();
            int bp=0;for(int i=0;i<projects.length();i++)try{
                JSONObject p=projects.optJSONObject(i);JSONObject contract=BlueprintAcceptance.baseline(p.optString("id"),p.optString("source"),p.optInt("revision"));
                if(BlueprintAcceptance.evaluate(contract.toString(),p.optString("id"),p.optString("source"),p.optInt("revision")).optBoolean("ok"))bp++;
            }catch(Exception ex){log.append("\nBlueprint contract failed: ").append(ex.getMessage());}
            log.append("\nBlueprint baseline acceptance: ").append(bp).append('/').append(projects.length());
            print(log.toString());return;
        }
        String path=endpoints[index];api.backend("GET",path,null,new Network.Result(){
            public void success(JSONObject j){boolean valid=j.optBoolean("ok",true);log.append("\n").append(valid?"PASS ":"FAIL ").append(path);diagnosticsNext(endpoints,index+1,passed+(valid?1:0),log);}
            public void failure(String error){log.append("\nFAIL ").append(path).append(": ").append(error);diagnosticsNext(endpoints,index+1,passed,log);}
        });
    }
    private void conversations(){
        button(page,"Replies first workflow",()->openFlow("replies"));
        button(page,"Character agents: Naomi, Often, Ultra, Mina",()->startActivity(new Intent(this,AgentActivity.class)));
        button(page,"Allies and saved agents",()->openFlow("allies"));
        button(page,"Intel feed, search, watch",()->openFlow("intel"));
        text(page,"Moltbook account and discussion workflows",18);
        text(page,"Moltbook credentials are encrypted on this device and sent only to https://www.moltbook.com/api/v1.",13);
        button(page,"Account status",()->api.moltbook("GET","/agents/status",null,result()));
        button(page,"Home and replies overview",()->api.moltbook("GET","/home",null,result()));
        button(page,"New feed",()->api.moltbook("GET","/feed?sort=new&limit=25",null,result()));
        EditText search=input(page,"Search Moltbook","",false);
        button(page,"Search",()->api.moltbook("GET","/search?q="+enc(search.getText().toString())+"&type=all&limit=20",null,result()));
        text(page,"Manual post — no automatic paid creates",16);
        EditText sub=input(page,"Submolt","introductions",false);
        EditText title=input(page,"Title","",false);
        EditText content=input(page,"Content","",true);
        button(page,"Publish post (manual)",()->confirmCreate("Publish to Moltbook?",()->{
            try{JSONObject req=json("kind","post","submolt_name",sub.getText().toString(),"title",title.getText().toString(),"content",content.getText().toString());
                req.put("optIn",true);api.backend("POST","/api/workflows/moltbook/publish",req,result());}
            catch(Exception ex){print(ex.toString());}
        }));
        button(page,"Publish post with independent solver and governor",()->confirmCreate("Publish verified post with one credit attempt?",()->{
            try{JSONObject body=json("kind","post","submolt_name",sub.getText().toString(),"title",title.getText().toString(),"content",content.getText().toString());
                body.put("optIn",true);api.backend("POST","/api/workflows/moltbook/publish",body,result());}
            catch(Exception ex){print(ex.getMessage());}
        }));
        text(page,"Manual comment/reply",16);
        EditText postId=input(page,"Post ID","",false);
        EditText comment=input(page,"Reply content","",true);
        button(page,"Load thread",()->api.moltbook("GET","/posts/"+enc(postId.getText().toString())+"/comments?sort=new",null,result()));
        button(page,"Publish reply (manual)",()->confirmCreate("Publish this comment?",()->{
            try{JSONObject req=json("kind","reply","postId",postId.getText().toString(),"content",comment.getText().toString());
                req.put("optIn",true);api.backend("POST","/api/workflows/moltbook/publish",req,result());}
            catch(Exception ex){print(ex.toString());}
        }));
        button(page,"Publish reply with independent solver and governor",()->confirmCreate("Publish verified reply with one credit attempt?",()->{
            try{JSONObject body=json("kind","reply","postId",postId.getText().toString(),"content",comment.getText().toString());
                body.put("optIn",true);api.backend("POST","/api/workflows/moltbook/publish",body,result());}
            catch(Exception ex){print(ex.getMessage());}
        }));
    }
    private Network.Result verifyResult(){
        return new Network.Result(){
            public void success(JSONObject j){print(j.toString());
                JSONObject c=j.optJSONObject("verification");
                if(c==null)c=j.optJSONObject("challenge");
                if(c==null)c=j;
                String code=c.optString("verification_code");
                String challenge=c.optString("challenge_text");
                if(!code.isEmpty()&&!challenge.isEmpty())manualVerification(code,challenge);
            }
            public void failure(String msg){print(msg);}
        };
    }
    private void manualVerification(String code,String challenge){
        if(solver==null)solver=new ChallengeSolver(this);
        solver.solve(challenge,new ChallengeSolver.Callback(){
            @Override public void complete(JSONObject result){
                String answer=result.optString("answer");
                boolean sure=result.optBoolean("sure") && !answer.isEmpty();
                String info=sure ? "Deterministic solver answer: "+answer :
                   "Solver is UNSURE: "+result.optString("reason","unresolved challenge")+". Manual review required.";
                EditText field=new EditText(MainActivity.this);field.setHint("Your verified answer");
                if(sure)field.setText(answer);
                new AlertDialog.Builder(MainActivity.this).setTitle("Moltbook challenge")
                    .setMessage(challenge+"\n\n"+info).setView(field)
                    .setNegativeButton("Keep pending",null)
                    .setPositiveButton("Submit answer",(d,w)->{
                        String userAnswer=field.getText().toString().trim();
                        if(userAnswer.isEmpty()){print("Verification not submitted; answer is empty");return;}
                        api.moltbook("POST","/verify",json("verification_code",code,"answer",userAnswer),result());
                    }).show();
            }
            @Override public void failed(String message){print("Verification solver error: "+message+". Challenge left pending.");}
        });
    }
    @Override protected void onDestroy(){if(solver!=null)solver.dispose();super.onDestroy();}
    private void confirmCreate(String title,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage("This action publishes content on an external platform and may consume posting budget.")
            .setNegativeButton("Cancel",null).setPositiveButton("Publish",(d,w)->action.run()).show();}
    private void studio(){
        button(page,"Local Build workflow",()->openFlow("build"));
        button(page,"Lab: sealed probes, oracle registry, blocker age",()->openFlow("lab"));
        button(page,"Radio: offline synthesis + shared stations",()->openFlow("radio"));
        text(page,"Offline native projects and collaborative HTML Studio",18);
        button(page,"New local HTML project",()->{
            String id=local.create("Project "+(local.projects().length()+1),"<!doctype html><html><body><h1>New project</h1></body></html>");
            Intent intent=new Intent(this,EditorActivity.class);intent.putExtra("projectId",id);startActivity(intent);
        });
        JSONArray projects=local.projects();
        for(int i=0;i<projects.length();i++){
            JSONObject p=projects.optJSONObject(i);String pid=p.optString("id");
            button(page,p.optString("name")+" · revision "+p.optInt("revision"),()->{
                Intent intent=new Intent(this,EditorActivity.class);intent.putExtra("projectId",pid);startActivity(intent);
            });
            button(page,"Canvas Editor · "+p.optString("name"),()->{
                Intent intent=new Intent(this,CanvasEditorActivity.class);intent.putExtra("projectId",pid);startActivity(intent);
            });
        }
        text(page,"Version history, revert, HTML import/export, sandboxed live preview, touch-pad controls, realtime room join/edit/save, and thinking/backsteps are in the project editor.",13);
        text(page,"Independent shared rooms",16);
        button(page,"List live rooms",()->api.backend("GET","/api/rooms",null,result()));
        button(page,"View saved voting features",()->api.backend("GET","/api/votes",null,result()));
    }
    private void operations(){
        button(page,"Character agents and independent group chats",()->startActivity(new Intent(this,AgentActivity.class)));
        button(page,"Bridge: inference API and team jobs",()->openFlow("bridge"));
        button(page,"Fleet: roster, tasks, quorum polls",()->openFlow("fleet"));
        button(page,"Net: service directory and live probes",()->openFlow("net"));
        button(page,"Infra: provider directories and claims",()->openFlow("infra"));
        button(page,"Autonomy and governor",()->openFlow("autonomy"));
        text(page,"Webmesh · free shared network",18);
        EditText agent=input(page,"Your agent handle",settings.get("nick","molty"),false);
        button(page,"Read inbox",()->api.backend("GET","/api/mesh/inbox/"+enc(agent.getText().toString()),null,result()));
        button(page,"Mark inbox read",()->api.backend("POST","/api/mesh/inbox/"+enc(agent.getText().toString())+"/read",new JSONObject(),result()));
        EditText recipient=input(page,"Recipient agent handle","",false);
        EditText message=input(page,"Message","",true);
        button(page,"Send Webmesh message",()->api.backend("POST","/api/mesh/send",json("from",agent.getText().toString(),"to",recipient.getText().toString(),"text",message.getText().toString()),result()));
        EditText topic=input(page,"Topic","general",false);
        button(page,"Read topic",()->api.backend("GET","/api/mesh/talk/"+enc(topic.getText().toString()),null,result()));
        button(page,"Post to topic",()->api.backend("POST","/api/mesh/talk/"+enc(topic.getText().toString()),json("nick",agent.getText().toString(),"text",message.getText().toString()),result()));
        button(page,"Send presence heartbeat",()->api.backend("POST","/api/mesh/beat",json("nick",agent.getText().toString(),"note","active"),result()));
        button(page,"View active agents",()->api.backend("GET","/api/mesh/presence",null,result()));
    }
    private void exchange(){
        button(page,"Market: ad exchange, rotating billboards, AI taglines",()->openFlow("market"));
        button(page,"Webmesh: deeds, readers, bounties",()->openFlow("webmesh"));
        text(page,"Feature governance and support",18);
        button(page,"Original DonationLink2Ad banner (full/compact, dark/light)",()->startActivity(new Intent(this,DonationActivity.class)));
        button(page,"Refresh feature vote totals",()->api.backend("GET","/api/votes",null,result()));
        EditText voter=input(page,"Nickname",settings.get("nick","molty"),false);
        EditText handle=input(page,"Optional donor handle (self-claimed)","",false);
        android.widget.CheckBox donorCheck=new android.widget.CheckBox(this);donorCheck.setText("Donor ×2 (self-claimed)");page.addView(donorCheck);
        text(page,"Choose a feature",14);
        for(String feat:new String[]{"donations","touchscreen","editor","thinking","playground","replies"}){
            button(page,"Vote ON: "+feat,()->vote(feat,"on",voter.getText().toString(),handle.getText().toString(),donorCheck.isChecked()));
            button(page,"Vote OFF: "+feat,()->vote(feat,"off",voter.getText().toString(),handle.getText().toString(),donorCheck.isChecked()));
        }
        text(page,"Support links are user-configured; no hardcoded owner wallets.",14);
        openSupport("PayPal","paypal");openSupport("Cash App","cashapp");openSupport("Other support","support");
        for(String chain:new String[]{"sol","eth"}){
            button(page,"Copy "+chain.toUpperCase()+" address",()->{
                String address=settings.get(chain,"");
                if(address.isEmpty()){print("Configure "+chain+" address in Settings");return;}
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(chain+" address",address));
                print(chain.toUpperCase()+" address copied");
            });
        }
    }
    private void vote(String feat,String dir,String nick,String handle,boolean donor){
        try{JSONObject j=json("feat",feat,"dir",dir,"nick",nick,"handle",handle);j.put("donor",donor);
            api.backend("POST","/api/votes",j,result());}
        catch(Exception ex){print(ex.toString());}
    }
    private void openSupport(String label,String key){
        button(page,"Open "+label,()->{
            String address=settings.get(key,"");
            if(address.isEmpty()){print("Configure "+label+" URL under Settings");return;}
            try{Uri url=Uri.parse(address);if(!"https".equalsIgnoreCase(url.getScheme()))throw new IllegalArgumentException("Only HTTPS support links allowed");
                startActivity(new Intent(Intent.ACTION_VIEW,url));}catch(Exception ex){print("Invalid support link: "+ex.getMessage());}
        });
    }
    private void settings(){
        text(page,"Independent runtime configuration",18);
        text(page,"On-device service is started by this APK and persists in the foreground while running.",13);
        button(page,"Start / restart on-device service",()->{EmbeddedService.restart(this);print("Native background service requested · port 8000");});
        button(page,"Stop on-device service",()->{EmbeddedService.shutdown(this);print("Native service stopped");});
        android.widget.CheckBox boot=new android.widget.CheckBox(this);
        boot.setText("Start on-device service after phone reboot (optional)");
        boot.setChecked(settings.get("bootHosting","false").equals("true"));
        boot.setOnCheckedChangeListener((v,on)->{settings.put("bootHosting",String.valueOf(on));print(on?"Boot-start enabled":"Boot-start disabled");});
        page.addView(boot);
        android.widget.CheckBox lan=new android.widget.CheckBox(this);lan.setText("Allow LAN hosting (authenticated, HTTP only · use trusted local networks)");lan.setChecked(settings.get("lanHosting","false").equals("true"));lan.setOnCheckedChangeListener((v,on)->{settings.put("lanHosting",String.valueOf(on));EmbeddedService.restart(this);});page.addView(lan);
        button(page,"Copy local service access token",()->{String key=RuntimeSecrets.ensure(this);android.content.ClipboardManager clip=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clip.setPrimaryClip(android.content.ClipData.newPlainText("Canceled Agent LAN token",key));print("Access token copied; treat as a secret");});
        EditText backend=input(page,"Self-hosted backend URL",settings.get("backend","http://127.0.0.1:8000"),false);
        button(page,"Save backend URL",()->{
            String url=backend.getText().toString().trim();
            if(!(url.startsWith("https://")||url.startsWith("http://"))){print("Backend must use http:// or https://");return;}
            settings.put("backend",url);print("Saved backend URL. Use HTTPS for remote deployments.");
        });
        EditText token=input(page,"Backend access token (optional)","",false);
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(page,"Save backend token",()->{settings.secret("backendToken",token.getText().toString().trim());token.setText("");print("Backend token encrypted in Android Keystore");});
        button(page,"Clear backend token",()->{settings.deleteSecret("backendToken");print("Backend token cleared");});
        EditText nick=input(page,"Nickname",settings.get("nick","molty"),false);
        button(page,"Save nickname",()->{settings.put("nick",nick.getText().toString());print("Nickname saved");});
        text(page,"Outbound Bridge session (optional for internet reachability)",15);
        EditText bridgeRelay=input(page,"HTTPS Socket.IO relay origin",settings.get("bridge-relay",""),false);
        button(page,"Save relay URL",()->{String url=bridgeRelay.getText().toString().trim();if(!url.isEmpty()&&!url.startsWith("https://")){print("Relay must use HTTPS");return;}settings.put("bridge-relay",url);EmbeddedService.restart(this);print("Relay endpoint saved");});
        android.widget.CheckBox bridgeOn=new android.widget.CheckBox(this);bridgeOn.setText("Connect to configured outbound Bridge automatically");bridgeOn.setChecked(settings.get("bridge-outbound","false").equals("true"));bridgeOn.setOnCheckedChangeListener((v,on)->{settings.put("bridge-outbound",String.valueOf(on));EmbeddedService.restart(this);});page.addView(bridgeOn);
        text(page,"Bridge connection state: "+settings.get("bridge-status","offline"),13);
        android.widget.CheckBox externalAgentTurns=new android.widget.CheckBox(this);
        externalAgentTurns.setText("Allow explicitly requested character turns over Bridge (uses my provider)");
        externalAgentTurns.setChecked(settings.get("remote-agent-turns","false").equals("true"));
        externalAgentTurns.setOnCheckedChangeListener((v,on)->{settings.put("remote-agent-turns",String.valueOf(on));print(on?"Remote agent turns enabled. Callers must still supply optIn per turn; provider may charge.":"Remote agent turns blocked");});
        page.addView(externalAgentTurns);
        text(page,"Inference provider: keyless Perchance AI by default; custom local or paid provider optional",15);
        button(page,"Open Perchance free AI verification",()->startActivity(new Intent(this,PerchanceVerificationActivity.class)));
        button(page,"Check active AI provider",()->api.backend("GET","/api/ai/status",null,result()));
        EditText aiTest=input(page,"Prompt for a keyless/provider inference test","Reply with one short sentence.",true);
        button(page,"Run inference test (may consume provider tokens if configured)",()->api.backend("POST","/api/ai/generate",json("prompt",aiTest.getText().toString()),result()));
        android.widget.CheckBox aiFallback=new android.widget.CheckBox(this);aiFallback.setText("Allow Perchance free AI after explicitly configured provider fails");
        aiFallback.setChecked("true".equals(settings.get("aiAllowFreeFallback","false")));
        aiFallback.setOnCheckedChangeListener((v,on)->settings.put("aiAllowFreeFallback",String.valueOf(on)));page.addView(aiFallback);
        EditText aiUrl=input(page,"OpenAI-compatible /v1/chat/completions URL",settings.get("aiEndpoint",""),false);
        EditText aiModel=input(page,"Model ID",settings.get("aiModel",""),false);
        EditText aiKey=input(page,"Provider bearer API key","",false);aiKey.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(page,"Save AI provider",()->{String url=aiUrl.getText().toString().trim();if(!url.isEmpty()&&!url.startsWith("https://")&&!url.startsWith("http://127.0.0.1")&&!url.startsWith("http://localhost")){print("Provider must use HTTPS except loopback");return;}settings.put("aiEndpoint",url);settings.put("aiModel",aiModel.getText().toString().trim());if(!aiKey.getText().toString().isEmpty())settings.secret("aiKey",aiKey.getText().toString());aiKey.setText("");print("Independent AI provider configured");});
        text(page,"Moltbook API key (device-encrypted; official Moltbook only)",15);
        EditText moltKey=input(page,"moltbook_sk_...","",false);moltKey.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(page,"Save Moltbook key",()->{settings.secret("moltbook",moltKey.getText().toString().trim());moltKey.setText("");print("Key encrypted in Android Keystore");});
        button(page,"Clear Moltbook key",()->{settings.deleteSecret("moltbook");print("Moltbook key cleared");});
        text(page,"Donations / owner support URLs",15);
        for(String key:new String[]{"paypal","cashapp","support"}){
            EditText field=input(page,key+" HTTPS URL",settings.get(key,""),false);
            button(page,"Save "+key,()->{
                String value=field.getText().toString().trim();
                if(!value.isEmpty()&&!value.startsWith("https://")){print("Only HTTPS URLs permitted");return;}
                settings.put(key,value);print(key+" URL saved");
            });
        }
        for(String chain:new String[]{"sol","eth"}){
            EditText wallet=input(page,chain.toUpperCase()+" receiving address",settings.get(chain,""),false);
            button(page,"Save "+chain.toUpperCase()+" address",()->{settings.put(chain,wallet.getText().toString().trim());print(chain+" address saved");});
        }
        text(page,"Blueprint profile (native, stored on device)",16);
        WorkflowProfile profile=new WorkflowProfile(this);
        text(page,"Current mode: "+profile.mode(),13);
        for(String overlay:new String[]{"git","thinking","plugins"}){
            android.widget.CheckBox c=new android.widget.CheckBox(this);
            c.setText("Enable "+overlay+" overlay");c.setChecked(profile.overlay(overlay));
            c.setOnCheckedChangeListener((v,checked)->profile.overlay(overlay,checked));page.addView(c);
        }
        text(page,"All local operations are app-managed. No Termux, Python service, or external backend is required for local workflows.",13);
        text(page,"Standalone data: projects, revision history and trace journals reside in the app's local SQLite database. No Perchance account is required.",13);
    }
}
