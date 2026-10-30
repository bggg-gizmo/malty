package org.canceledagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import android.graphics.Typeface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Dedicated native task workflows, backed by independent server state. No web dashboard. */
public final class WorkflowActivity extends Activity {
    private SecurePrefs prefs;private Network net;private LinearLayout content,entries;
    private TextView output;private String screen;private boolean marketOrigin=false;
    private final android.os.Handler marketHeartbeat=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable heartbeat=new Runnable(){public void run(){sendMarketVisibility(true);marketHeartbeat.postDelayed(this,30000);}};
    private final int bg=Color.rgb(11,17,25),card=Color.rgb(26,36,50),text=Color.rgb(229,237,247),muted=Color.rgb(158,175,198),accent=Color.rgb(63,204,186);
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private static final Map<String,String[]> SCHEMA=new LinkedHashMap<>();
    static {
        SCHEMA.put("ads",new String[]{"nick","url","price","pitch","tagline"});
        SCHEMA.put("deeds",new String[]{"nick","url","upkeep","traffic","receipts","price","rooms","upvotes","replies"});
        SCHEMA.put("readers",new String[]{"nick","deedId","version","note"});
        SCHEMA.put("bounties",new String[]{"title","goal","pledged","state","owner"});
        SCHEMA.put("services",new String[]{"name","url","kind","description","model","protocol","provider"});
        SCHEMA.put("net-requests",new String[]{"kind","description","contact","price"});
        SCHEMA.put("stations",new String[]{"name","url","genre","country","addedBy"});
        SCHEMA.put("roster",new String[]{"nick","agent","role","status","contact"});
        SCHEMA.put("fleet",new String[]{"title","brief","owner"});
        SCHEMA.put("polls",new String[]{"question","quorum","deadline","owner"});
        SCHEMA.put("allies",new String[]{"agent","role","notes","submolt"});
        SCHEMA.put("infra",new String[]{"nick","agent","kind","url","note","status"});
        SCHEMA.put("intel",new String[]{"title","source","summary","tags"});
        SCHEMA.put("watch",new String[]{"agent"});
        SCHEMA.put("providers",new String[]{"name","endpoint","model","kind"});
        SCHEMA.put("jobs",new String[]{"task","slots","anchor"});
        SCHEMA.put("agents",new String[]{"agent","postId","commentId","note"});
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);prefs=new SecurePrefs(this);net=new Network(this,prefs);screen=getIntent().getStringExtra("screen");if(screen==null)screen="bridge";marketOrigin=screen.equals("market");show();}
    @Override protected void onResume(){super.onResume();if(marketOrigin){marketHeartbeat.removeCallbacks(heartbeat);heartbeat.run();}}
    @Override protected void onPause(){if(marketOrigin){marketHeartbeat.removeCallbacks(heartbeat);sendMarketVisibility(false);}super.onPause();}
    private void sendMarketVisibility(boolean visible){
        try{net.backend("POST","/api/workflows/market/tagline-controls",new JSONObject().put("visible",visible),new Network.Result(){
            public void success(JSONObject j){} public void failure(String e){}
        });}catch(Exception ignored){}
    }
    private void show(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(13,16,13,12);root.setBackgroundColor(bg);setContentView(root);
        TextView h=label(root,"CANCELED AGENT   /   "+screen.toUpperCase(Locale.ROOT),20,accent);h.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        Button back=button(root,"‹ Back to workspaces",()->finish());back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(card));
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(4,8,4,8);scroll.addView(content);
        switch(screen){
            case "bridge":bridge();break;case "fleet":case "market":case "webmesh":case "net":case "infra":case "allies":case "intel":case "radio":collections();break;
            case "lab":lab();break;case "autonomy":autonomy();break;case "replies":replies();break;case "build":build();break;
            default:collections();break;
        }
        label(root,"OPERATION RESULT",12,muted);ScrollView resultScroll=new ScrollView(this);resultScroll.setFillViewport(false);
        output=new TextView(this);output.setText("Ready. Changes use your independent backend.");output.setTextColor(text);output.setTextSize(13);output.setTextIsSelectable(true);
        LinearLayout pad=new LinearLayout(this);pad.setPadding(14,8,14,8);pad.setBackgroundColor(card);pad.addView(output);resultScroll.addView(pad);
        root.addView(resultScroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private LinearLayout section(String title,String sub){
        LinearLayout cardView=new LinearLayout(this);cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setPadding(15,14,15,15);cardView.setBackgroundColor(card);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,8,0,10);content.addView(cardView,lp);
        label(cardView,title,17,text).setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        if(sub!=null&&!sub.isEmpty())label(cardView,sub,12,muted);
        return cardView;
    }
    private TextView label(LinearLayout parent,String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(4,7,4,7);parent.addView(v);return v;}
    private EditText field(LinearLayout p,String key){EditText v=new EditText(this);v.setHint(key);v.setTextColor(text);v.setHintTextColor(muted);v.setSingleLine(!key.equals("brief")&&!key.equals("contract")&&!key.equals("proof")&&!key.equals("task")&&!key.equals("note")&&!key.equals("result"));p.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    private Button button(LinearLayout p,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(text);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(30,91,100)));p.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());return b;}
    private JSONObject obj(String... fields){JSONObject j=new JSONObject();try{for(int i=0;i<fields.length;i+=2)j.put(fields[i],fields[i+1]);}catch(Exception e){throw new RuntimeException(e);}return j;}
    private String pretty(JSONObject j){try{return j.toString(2);}catch(Exception e){return j.toString();}}
    private void backend(String method,String path,JSONObject data){net.backend(method,path,data,new Network.Result(){public void success(JSONObject j){message(pretty(j));}public void failure(String e){message("ERROR "+e);}});}
    private void backend(String method,String path,JSONObject data,Runnable refresh){net.backend(method,path,data,new Network.Result(){public void success(JSONObject j){message(pretty(j));if(refresh!=null)refresh.run();}public void failure(String e){message("ERROR "+e);}});}
    private void message(String s){if(output!=null)output.setText(s);}
    private void flag(String path,String name,boolean enabled){
        try{backend("POST",path,new JSONObject().put(name,enabled));}catch(Exception e){message(e.toString());}
    }
    private String encode(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(java.io.UnsupportedEncodingException ex){return "";}}
    private void confirm(String title,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage("Confirm this operation on the independent server.").setNegativeButton("Cancel",null).setPositiveButton("Confirm",(d,w)->action.run()).show();}
    private void registry(String kind){
        LinearLayout creator=section("Create "+kind,"Records are versioned and stored in your own SQLite service. No Perchance runtime.");
        fields.clear();for(String key:SCHEMA.get(kind))fields.put(key,field(creator,key));
        button(creator,"Save "+kind+" record",()->{
            JSONObject data=new JSONObject();try{
                for(Map.Entry<String,EditText> e:fields.entrySet()){
                    String k=e.getKey(),v=e.getValue().getText().toString();
                    if(k.equals("anchor"))data.put(k,false);
                    else if(k.equals("slots")||k.equals("quorum")||k.equals("upvotes")||k.equals("replies")||k.equals("receipts")||k.equals("rooms"))data.put(k,v.isEmpty()?0:Integer.parseInt(v));
                    else data.put(k,v);
                }
            }catch(Exception e){message("Invalid form value: "+e.getMessage());return;}
            backend("POST","/api/workflows/records/"+kind,data,()->loadRegistry(kind));
        });
        LinearLayout registry=section("Saved "+kind,"Tap any record to view, update, transition or remove. All writes check the current version.");
        button(registry,"Refresh",()->loadRegistry(kind));
        entries=new LinearLayout(this);entries.setOrientation(LinearLayout.VERTICAL);registry.addView(entries);
        loadRegistry(kind);
    }
    private void loadRegistry(String kind){
        net.backend("GET","/api/workflows/records/"+kind+"?limit=200",null,new Network.Result(){
            public void success(JSONObject j){
                if(entries==null)return;entries.removeAllViews();JSONArray arr=j.optJSONArray("items");
                if(arr==null){message(j.toString());return;}
                label(entries,arr.length()+" records",12,muted);
                for(int i=0;i<arr.length();i++){
                    JSONObject r=arr.optJSONObject(i);if(r==null)continue;
                    String id=r.optString("id"),title=r.optString("title",r.optString("name",r.optString("nick",r.optString("agent",id))));
                    button(entries,title+"  ·  "+id,()->detail(kind,r));
                }
            }
            public void failure(String err){message("Registry: "+err);}
        });
    }
    private void detail(String kind,JSONObject r){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(15,10,15,10);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        final int version=r.optInt("version");String id=r.optString("id");
        TextView full=label(box,pretty(r),12,text);full.setTextIsSelectable(true);
        EditText patch=field(box,"Change field name");EditText value=field(box,"New value");
        button(box,"Update (optimistic lock)",()->{
            try{JSONObject q=new JSONObject().put("version",version).put("changes",obj(patch.getText().toString(),value.getText().toString()));
                backend("PUT","/api/workflows/records/"+kind+"/"+id,q,()->loadRegistry(kind));}
            catch(Exception e){message(e.toString());}
        });
        if(kind.equals("fleet")){
            EditText agent=field(box,"Acting agent handle");EditText result=field(box,"Result / evidence");
            for(String action:new String[]{"claim","submit","accept","reopen"})button(box,"Task: "+action,()->backend("POST","/api/workflows/fleet/"+id+"/transition",obj("action",action,"agent",agent.getText().toString(),"result",result.getText().toString()),()->loadRegistry(kind)));
        }
        if(kind.equals("jobs")){
            EditText agent=field(box,"Agent name");EditText result=field(box,"Result");
            button(box,"Claim slot",()->backend("POST","/api/workflows/team/"+id+"/claim",obj("agent",agent.getText().toString()),()->loadRegistry(kind)));
            button(box,"Submit work",()->backend("POST","/api/workflows/team/"+id+"/submit",obj("agent",agent.getText().toString(),"result",result.getText().toString()),()->loadRegistry(kind)));
        }
        if(kind.equals("polls")){
            EditText agent=field(box,"Voter handle");
            for(String choice:new String[]{"yes","no","abstain"})button(box,"Vote: "+choice,()->backend("POST","/api/workflows/polls/"+id+"/vote",obj("agent",agent.getText().toString(),"choice",choice),()->loadRegistry(kind)));
        }
        if(kind.equals("ads"))button(box,"Generate AI tagline",()->backend("POST","/api/workflows/market/tagline/"+id,new JSONObject(),()->loadRegistry(kind)));
        button(box,"Delete record",()->confirm("Delete "+id,()->backend("DELETE","/api/workflows/records/"+kind+"/"+id+"?version="+version,null,()->loadRegistry(kind))));
        new AlertDialog.Builder(this).setTitle(kind+" / "+id).setView(scroll).setPositiveButton("Close",null).show();
    }
    private void collections(){
        String[] options;
        switch(screen){
            case "market":options=new String[]{"ads","deeds","readers","bounties"};break;
            case "webmesh":options=new String[]{"deeds","readers","bounties"};break;
            case "net":options=new String[]{"services","net-requests","providers"};break;
            case "radio":options=new String[]{"stations"};break;
            case "fleet":options=new String[]{"roster","fleet","polls"};break;
            case "infra":options=new String[]{"infra"};break;
            case "allies":options=new String[]{"allies","watch"};break;
            case "intel":options=new String[]{"intel","watch"};break;
            default:options=new String[]{"jobs","agents"};
        }
        if(screen.equals("market")){
            LinearLayout s=section("45-second billboard rotation","Three simultaneous positions, round-robin across an unlimited queue.");
            button(s,"View active billboards",()->backend("GET","/api/workflows/market/billboards",null));
            button(s,"Enable AI tagline loop",()->flag("/api/workflows/market/tagline-controls","enabled",true));
            button(s,"Pause AI tagline loop",()->flag("/api/workflows/market/tagline-controls","enabled",false));
        }
        if(screen.equals("net")){
            LinearLayout s=section("Live endpoint probe","Public HTTPS URL; no credential forwarding, no private-network probes.");
            EditText url=field(s,"https://service.example/v1/models");
            button(s,"Probe HTTPS",()->backend("POST","/api/workflows/net/probe",obj("url",url.getText().toString())));
        }
        if(screen.equals("webmesh")){
            LinearLayout s=section("Estate scoring","Claimed, not independently verified: 2×upvotes + 3×replies + 10×receipts + 5×rooms.");
            button(s,"Rank deed claims",()->backend("GET","/api/workflows/webmesh/deeds/scores",null));
        }
        if(screen.equals("radio")){
            LinearLayout s=section("Lux Radio / on-device synth","Original generated waveforms: offline, no server stream or Perchance dependency.");
            button(s,"Open radio mixer",()->startActivity(new Intent(this,RadioActivity.class)));
            button(s,"Refresh shared dial",()->backend("GET","/api/workflows/radio/dial",null));
        }
        if(screen.equals("allies")||screen.equals("intel")){
            LinearLayout s=section("Moltbook discovery","Live public profiles and search via your encrypted device key.");
            EditText agent=field(s,"Agent name or search term");
            button(s,"Lookup profile",()->net.moltbook("GET","/agents/profile?name="+encode(agent.getText().toString()),null,new Network.Result(){public void success(JSONObject j){message(pretty(j));}public void failure(String e){message(e);}}));
            button(s,"Search Moltbook",()->net.moltbook("GET","/search?q="+encode(agent.getText().toString())+"&type=all&limit=25",null,new Network.Result(){public void success(JSONObject j){message(pretty(j));}public void failure(String e){message(e);}}));
        }
        if(screen.equals("bridge")){
            LinearLayout s=section("Independent REST Bridge","Self-hosted OpenAI and Anthropic endpoints; persistent team jobs survive closing the app.");
            button(s,"Bridge health and routes",()->backend("GET","/api/workflows/bridge/status",null));
            button(s,"Available models",()->backend("GET","/api/workflows/bridge/models",null));
            button(s,"Bridge protocol",()->backend("POST","/api/bridge/relay",obj("payload","")));
        }
        LinearLayout switcher=section("Select workflow", "Each area opens its own persistent editor and actionable registry.");
        for(String kind:options)button(switcher,kind.toUpperCase(Locale.ROOT),()->{
            screen=""+kind;content.removeAllViews();registry(kind);
        });
        // Each registry is opened as a separate workflow after selection.
    }
    private void bridge(){
        LinearLayout s=section("Touchstone Bridge replacement","OpenAI /v1/chat/completions and Anthropic /v1/messages on your backend. No Perchance session.");
        button(s,"Get bridge status",()->backend("GET","/api/workflows/bridge/status",null));
        button(s,"List enabled models",()->backend("GET","/api/workflows/bridge/models",null));
        button(s,"Retrieve capability contract",()->{
            try{backend("POST","/api/bridge/relay",new JSONObject().put("payload",obj("op","team-capability")));}catch(Exception e){message(e.toString());}
        });
        LinearLayout team=section("Dispatch multi-agent job","Local, independent team jobs; Moltbook anchors require separate explicit publishing.");
        EditText brief=field(team,"Team task");EditText slots=field(team,"Slots (1–20)");slots.setText("2");
        EditText anchorPost=field(team,"Moltbook array post ID (optional paid anchor)");
        button(team,"Dispatch locally (free)",()->{
            try{JSONObject b=new JSONObject().put("op","team-dispatch").put("task",brief.getText().toString()).put("slots",Integer.parseInt(slots.getText().toString())).put("anchor",false);
                backend("POST","/api/bridge/relay",new JSONObject().put("payload",b));}catch(Exception e){message(e.toString());}
        });
        button(team,"Dispatch + verified Moltbook anchor (paid)",()->confirm("Spend one Moltbook comment credit for this team anchor?",()->{
            try{JSONObject b=new JSONObject().put("op","team-dispatch").put("task",brief.getText().toString()).put("slots",Integer.parseInt(slots.getText().toString()))
                   .put("anchor",true).put("postId",anchorPost.getText().toString()).put("optIn",true);
                backend("POST","/api/bridge/relay",new JSONObject().put("payload",b));}catch(Exception e){message(e.toString());}
        }));
        button(team,"Open job board",()->{screen="jobs";show();});
        button(team,"Open bound-agent registry",()->{screen="agents";show();});
        LinearLayout route=section("Moltbook-team delegation", "Requires the backend's Moltbook key, a post ID, a daily spend allowance, explicit route enablement and a governor reserve. Never substitutes local AI.");
        EditText post=field(route,"Delegation post ID");
        EditText roster=field(route,"Allowed responders, comma-separated (blank = any)");
        EditText wait=field(route,"Wait seconds (60–300)");wait.setText("120");
        EditText daily=field(route,"Daily paid call cap");daily.setText("10");
        EditText quorum=field(route,"Quorum (1–5)");quorum.setText("1");
        EditText cache=field(route,"Answer cache TTL minutes (0=off)");cache.setText("0");
        button(route,"Review route status and responder stats",()->backend("GET","/api/workflows/moltbook/route",null));
        button(route,"Check route without spending",()->backend("POST","/api/workflows/moltbook/route/check",new JSONObject()));
        button(route,"Save route settings (OFF)",()->saveRoute(post,roster,wait,daily,quorum,cache,false));
        button(route,"Enable route (PAID calls allowed)",()->confirm("Allow paid delegation calls?",()->saveRoute(post,roster,wait,daily,quorum,cache,true)));
        button(route,"Disable route",()->flag("/api/workflows/moltbook/route","enabled",false));
        LinearLayout ask=section("Ask real Moltbook agents", "One paid, solver-verified comment per uncached request; polling and cached responses are free.");
        EditText agent=field(ask,"Specific responder name (optional)");
        EditText prompt=field(ask,"Prompt to send");
        EditText canary=field(ask,"Expected answer substring for canary verification (optional)");
        button(ask,"Send authorized delegation",()->confirm("Spend one Moltbook comment credit?",()->{
            try{backend("POST","/api/workflows/moltbook/ask",new JSONObject().put("optIn",true)
                .put("prompt",prompt.getText().toString()).put("agent",agent.getText().toString()).put("expect",canary.getText().toString()));}
            catch(Exception e){message(e.toString());}
        }));
        EditText job=field(ask,"Returned job ID");
        button(ask,"Collect replies (free)",()->backend("POST","/api/workflows/moltbook/poll/"+encode(job.getText().toString()),new JSONObject()));
        button(ask,"Renew deadline (free)",()->backend("POST","/api/workflows/moltbook/renew/"+encode(job.getText().toString()),new JSONObject()));
        button(ask,"See all delegation jobs",()->backend("GET","/api/workflows/moltbook/jobs",null));
        raceWorkflow();
    }
    /** Separate end-to-end Bridge race workflow: no implicit spending, one verified receipt per author. */
    private void raceWorkflow(){
        LinearLayout setup=section("RACE / Separate agent sections", "Draft a single brief, assign different sections to up to five agents, deliver each section with explicit approval, then collect a verbatim, attributed document. Missing replies are never filled by AI.");
        EditText brief=field(setup,"Race brief / shared question");brief.setSingleLine(false);brief.setMinLines(2);
        final EditText[] agents=new EditText[5],posts=new EditText[5],assignments=new EditText[5],words=new EditText[5];
        for(int i=0;i<5;i++){
            LinearLayout target=section("Race assignment "+(i+1),"Agent and post ID identify the target's own thread. Leave an unused assignment completely blank.");
            agents[i]=field(target,"Responder agent "+(i+1));
            posts[i]=field(target,"Moltbook post ID "+(i+1));
            assignments[i]=field(target,"Distinct section assignment "+(i+1));
            words[i]=field(target,"Target length (words) "+(i+1));words[i].setText("300");
            words[i].setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        LinearLayout action=section("Race delivery and collection", "Race creation and collection are free. Each approved delivery spends one Moltbook comment credit, subject to governor limits and a 70-second create interval.");
        EditText raceId=field(action,"Race ID — populated after creation");
        button(action,"Create persistent race (free)",()->{
            try{
                JSONArray targets=new JSONArray();
                for(int i=0;i<5;i++){
                    String agent=agents[i].getText().toString().trim(),post=posts[i].getText().toString().trim(),task=assignments[i].getText().toString().trim();
                    if(agent.isEmpty()&&post.isEmpty()&&task.isEmpty())continue;
                    targets.put(new JSONObject().put("agent",agent).put("postId",post).put("assignment",task)
                        .put("targetWords",Integer.parseInt(words[i].getText().toString().trim())));
                }
                JSONObject req=new JSONObject().put("brief",brief.getText().toString()).put("targets",targets);
                net.backend("POST","/api/workflows/bridge/race",req,new Network.Result(){
                    public void success(JSONObject j){message(pretty(j));JSONObject race=j.optJSONObject("race");if(race!=null)raceId.setText(race.optString("id"));}
                    public void failure(String e){message("ERROR "+e);}
                });
            }catch(Exception ex){message("Invalid race: "+ex.getMessage());}
        });
        EditText index=field(action,"Assignment number to send (1–5)");index.setText("1");index.setInputType(InputType.TYPE_CLASS_NUMBER);
        button(action,"Send selected assignment (paid, approval required)",()->confirm("Spend one verified Moltbook comment for this exact race assignment?",()->{
            try{backend("POST","/api/workflows/bridge/race/send",new JSONObject().put("raceId",raceId.getText().toString().trim())
                    .put("index",Integer.parseInt(index.getText().toString())-1).put("optIn",true));}
            catch(Exception ex){message(ex.toString());}
        }));
        button(action,"Check thread visibility and collect authored sections (free)",()->backend("POST","/api/workflows/bridge/race/"+encode(raceId.getText().toString().trim())+"/collect",new JSONObject()));
        button(action,"View combined verbatim document (free)",()->backend("GET","/api/workflows/bridge/race/"+encode(raceId.getText().toString().trim())+"/document",null));
        button(action,"View race state and receipts",()->backend("GET","/api/workflows/bridge/race/"+encode(raceId.getText().toString().trim()),null));
        button(action,"List saved race jobs",()->backend("GET","/api/workflows/bridge/race",null));
        button(action,"Reinvite once ONLY after confirmed nonvisibility (paid)",()->confirm("The app must have confirmed that the first delivery is absent from the thread. Spend one more comment credit for this assignment?",()->{
            try{backend("POST","/api/workflows/bridge/race/send",new JSONObject().put("raceId",raceId.getText().toString().trim())
                .put("index",Integer.parseInt(index.getText().toString())-1).put("confirmedMissing",true).put("optIn",true));}
            catch(Exception ex){message(ex.toString());}
        }));
        LinearLayout roster=section("Open roster / evidence", "Read introductions and see join/leave requests with comment receipts. Ambiguous statements remain review candidates; they never silently grant routing access.");
        EditText intro=field(roster,"Introduction post ID (default f2b808f0)");
        button(roster,"Discover roster candidates (read-only)",()->{
            try{backend("POST","/api/workflows/bridge/race/roster",new JSONObject().put("introPostId",intro.getText().toString()));}
            catch(Exception ex){message(ex.toString());}
        });
    }
    private void saveRoute(EditText post,EditText roster,EditText wait,EditText daily,EditText quorum,EditText cache,boolean enabled){
        try{
            JSONArray agents=new JSONArray();for(String name:roster.getText().toString().split(","))if(!name.trim().isEmpty())agents.put(name.trim());
            JSONObject cfg=new JSONObject().put("enabled",enabled).put("postId",post.getText().toString()).put("roster",agents)
                .put("waitSec",Integer.parseInt(wait.getText().toString())).put("dailyCap",Integer.parseInt(daily.getText().toString()))
                .put("quorum",Integer.parseInt(quorum.getText().toString())).put("cacheTtlMin",Integer.parseInt(cache.getText().toString()));
            backend("POST","/api/workflows/moltbook/route",cfg);
        }catch(Exception e){message("Route configuration: "+e.getMessage());}
    }
    private void lab(){
        LinearLayout commit=section("Hidden-probe vault","Sealed SHA-256 commit/reveal: sha256(nonce + newline + proof).");
        EditText nonce=field(commit,"Nonce");EditText proof=field(commit,"Hidden probe contents");
        button(commit,"Compute SHA-256, seal",()->{
            try{
                byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((nonce.getText().toString()+"\n"+proof.getText().toString()).getBytes(StandardCharsets.UTF_8));
                StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));
                backend("POST","/api/workflows/lab/probes/commit",obj("sha256",hex.toString(),"note","Private commitment"));
            }catch(Exception e){message(e.toString());}
        });
        EditText commitId=field(commit,"Commit ID to reveal");
        button(commit,"Reveal committed probe",()->backend("POST","/api/workflows/lab/probes/"+encode(commitId.getText().toString())+"/reveal",obj("nonce",nonce.getText().toString(),"proof",proof.getText().toString())));
        button(commit,"List sealed probes",()->backend("GET","/api/workflows/lab/probes",null));
        EditText verifyId=field(commit,"Probe ID for test verdict");
        button(commit,"Record probe caught by tests",()->flag("/api/workflows/lab/probes/"+encode(verifyId.getText().toString())+"/verdict","caught",true));
        button(commit,"Record missed probe",()->flag("/api/workflows/lab/probes/"+encode(verifyId.getText().toString())+"/verdict","caught",false));
        LinearLayout oracle=section("Oracle freeze registry","Freeze known contract behavior, then compare a later run against the immutable digest.");
        EditText name=field(oracle,"Oracle key");EditText contract=field(oracle,"Contract snapshot");
        button(oracle,"Freeze first version",()->backend("POST","/api/workflows/lab/oracle/"+encode(name.getText().toString())+"/freeze",obj("contract",contract.getText().toString())));
        EditText reason=field(oracle,"Deliberate contract-change reason (optional)");
        EditText version=field(oracle,"Expected frozen version");
        button(oracle,"Compare against frozen",()->{
            try{
                JSONObject q=new JSONObject().put("contract",contract.getText().toString()).put("reason",reason.getText().toString());
                if(!version.getText().toString().trim().isEmpty())q.put("expectedVersion",Integer.parseInt(version.getText().toString()));
                backend("POST","/api/workflows/lab/oracle/"+encode(name.getText().toString())+"/compare",q);
            }catch(Exception e){message(e.toString());}
        });
        LinearLayout age=section("Blocker age ledger","Persistent blocker start timestamp and rollover-proof event history.");
        EditText title=field(age,"New blocker title");EditText id=field(age,"Existing blocker ID");EditText note=field(age,"New note");
        button(age,"Open blocker",()->backend("POST","/api/workflows/lab/blockers",obj("title",title.getText().toString())));
        button(age,"Add progress note",()->backend("POST","/api/workflows/lab/blockers/"+encode(id.getText().toString())+"/event",obj("note",note.getText().toString())));
        button(age,"List ages and history",()->backend("GET","/api/workflows/lab/blockers",null));
        button(age,"Export complete Lab ledger",()->backend("GET","/api/workflows/lab/export",null));
    }
    private void autonomy(){
        LinearLayout s=section("Autonomy scheduler","Source intervals preserved. Reads permitted; writes are disabled until explicitly authorized and verified.");
        button(s,"Task schedule & last-run status",()->backend("GET","/api/workflows/autonomy",null));
        button(s,"Turn scheduler ON",()->backend("POST","/api/workflows/autonomy/control",new JSONObject(Collections.singletonMap("master",true))));
        button(s,"Turn scheduler OFF",()->backend("POST","/api/workflows/autonomy/control",new JSONObject(Collections.singletonMap("master",false))));
        button(s,"Authorize autonomous writes",()->confirm("Enable paid and free automatic writes?",()->flag("/api/workflows/autonomy/control","writesAuthorized",true)));
        button(s,"Disable all autonomous writes",()->flag("/api/workflows/autonomy/control","writesAuthorized",false));
        button(s,"View AI-generated patchwatch drafts",()->backend("GET","/api/workflows/autonomy/drafts",null));
        button(s,"Approve latest draft for a future fold",()->confirm("Approve latest draft? Review it before authorizing.",()->backend("POST","/api/workflows/autonomy/drafts/approve",new JSONObject())));
        button(s,"Read on-device task execution log",()->backend("GET","/api/workflows/autonomy/log",null));
        LinearLayout docs=section("Owned living comments","Configure exact post/comment ownership pairs for PATCH folds; no arbitrary remote edits.");
        EditText doc=field(docs,"Document kind (builds, pricing, hello)");
        EditText postId=field(docs,"Your Moltbook post ID");
        EditText commentId=field(docs,"Your living comment ID");
        button(docs,"Read current document bindings",()->backend("GET","/api/workflows/autonomy/docs",null));
        button(docs,"Save this document binding",()->{
            try{JSONArray list=new JSONArray().put(new JSONObject().put("doc",doc.getText().toString())
                .put("postId",postId.getText().toString()).put("commentId",commentId.getText().toString()));
                backend("POST","/api/workflows/autonomy/docs",new JSONObject().put("docs",list));}
            catch(Exception e){message(e.toString());}
        });
        for(String task:new String[]{"feedscan","digest","patchwatch","autofold","autoreply","autoupvote"}){
            LinearLayout row=section(task.toUpperCase(Locale.ROOT),task.startsWith("auto")?"Requires explicit opt-in, governor and verified executor":"Read-only task");
            button(row,"Run now",()->backend("POST","/api/workflows/autonomy/"+task+"/run",new JSONObject()));
            button(row,"Enable scheduled task",()->{try{backend("POST","/api/workflows/autonomy/control",new JSONObject().put("task",task).put("enabled",true));}catch(Exception e){message(e.toString());}});
            button(row,"Disable scheduled task",()->{try{backend("POST","/api/workflows/autonomy/control",new JSONObject().put("task",task).put("enabled",false));}catch(Exception e){message(e.toString());}});
        }
        LinearLayout g=section("Budget governor","Reserve 2 • 70s create gap • 3 paid auto-replies/day • 6 folds/day • 5 upvotes/day.");
        button(g,"Read budget and counters",()->backend("GET","/api/workflows/governor",null));
        EditText credits=field(g,"Actual daily remaining credit (manual reconciliation)");
        button(g,"Update remaining credits",()->{try{backend("POST","/api/workflows/governor/budget",new JSONObject().put("remaining",Integer.parseInt(credits.getText().toString())));}catch(Exception e){message(e.toString());}});
    }
    private void replies(){
        LinearLayout s=section("Replies and notifications","Read thread activity, inspect comments, and send replies through device-authorized Moltbook.");
        button(s,"Check /home",()->net.moltbook("GET","/home",null,new Network.Result(){public void success(JSONObject j){message(pretty(j));}public void failure(String e){message(e);}}));
        EditText postId=field(s,"Post ID");EditText message=field(s,"Reply content");
        button(s,"Read thread",()->net.moltbook("GET","/posts/"+encode(postId.getText().toString())+"/comments?sort=new",null,new Network.Result(){public void success(JSONObject j){message(pretty(j));}public void failure(String e){message(e);}}));
        button(s,"Publish manual reply",()->confirm("Send reply to Moltbook",()->{
            try{backend("POST","/api/workflows/moltbook/publish",new JSONObject().put("kind","reply")
                .put("postId",postId.getText().toString()).put("content",message.getText().toString()).put("optIn",true));}
            catch(Exception ex){message(ex.toString());}
        }));
        button(s,"Publish verified reply (governor + local solver)",()->confirm("Send verified reply to Moltbook",()->{
            try{JSONObject q=new JSONObject().put("kind","reply").put("postId",postId.getText().toString()).put("content",message.getText().toString()).put("optIn",true);
                backend("POST","/api/workflows/moltbook/publish",q);
            }catch(Exception e){message(e.getMessage());}
        }));
    }
    private void build(){
        LinearLayout s=section("Blueprint and project builds","Local independent source, revision history and export; no Perchance editor.");
        button(s,"Open Studio",()->{finish();});
        button(s,"Review integration audit",()->backend("GET","/api/workflows/catalog",null));
    }
}
