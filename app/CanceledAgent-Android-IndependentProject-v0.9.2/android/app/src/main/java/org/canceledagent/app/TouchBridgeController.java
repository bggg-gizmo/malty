package org.canceledagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Independent TouchBridge: keyboard/gamepad/pointer and custom mappings for user-owned HTML previews.
 * No Perchance code is injected. Every event goes only to the local, sandboxed editor WebView.
 */
public final class TouchBridgeController {
    private final Activity activity;private final WebView target;private final LinearLayout parent;private final SecurePrefs prefs;
    private final Map<String,String> keys=new LinkedHashMap<>();
    private final Map<Integer,Boolean> held=new HashMap<>();
    private LinearLayout panel;
    private String mode="dual";private int pointer=0;
    public TouchBridgeController(Activity a,WebView browser,LinearLayout container){activity=a;target=browser;parent=container;prefs=new SecurePrefs(a);restore();}
    public void show(){if(panel!=null)parent.removeView(panel);panel=new LinearLayout(activity);panel.setOrientation(1);parent.addView(panel);
        LinearLayout bar=new LinearLayout(activity);bar.setOrientation(0);panel.addView(bar);
        button(bar,"Controls",()->profiles());button(bar,"Self-test",()->selfTest());button(bar,"Mode: "+mode,()->{String[] all={"dual","keyboard","pointer","gamepad","hidden"};new AlertDialog.Builder(activity).setItems(all,(d,i)->{mode=all[i];store();show();}).show();});
        if(mode.equals("hidden"))return;
        if(!mode.equals("pointer")){
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER);panel.addView(row);
            addKey(row,"Q");addKey(row,"▲");addKey(row,"E");
            row=new LinearLayout(activity);row.setGravity(Gravity.CENTER);panel.addView(row);
            addKey(row,"◀");addKey(row,"▼");addKey(row,"▶");
            row=new LinearLayout(activity);row.setGravity(Gravity.CENTER);panel.addView(row);
            for(String k:new String[]{"A","B","X","Y","L","R","Enter","Space"})addKey(row,k);
        }
        if(mode.equals("pointer")||mode.equals("dual")||mode.equals("gamepad")){
            TextView instruction=new TextView(activity);instruction.setText("Touchpad: tap/click · drag · long press/right click · two-finger pinch/zoom. Press and hold any mapped button for keydown/keyup.");
            instruction.setTextColor(0xffdbeaf8);instruction.setTextSize(12);panel.addView(instruction);
            View pad=new View(activity);pad.setBackgroundColor(0xff1c3d4b);panel.addView(pad,new LinearLayout.LayoutParams(-1,155));
            pad.setOnTouchListener(new View.OnTouchListener(){private float lastX,lastY,initialGap;private long start;
                public boolean onTouch(View v,MotionEvent e){int action=e.getActionMasked();float x=e.getX()/Math.max(v.getWidth(),1),y=e.getY()/Math.max(v.getHeight(),1);
                    if(action==MotionEvent.ACTION_DOWN){lastX=x;lastY=y;start=System.currentTimeMillis();dispatchMouse("pointerdown",x,y,0,1);return true;}
                    if(action==MotionEvent.ACTION_POINTER_DOWN&&e.getPointerCount()>=2){initialGap=gap(e);return true;}
                    if(action==MotionEvent.ACTION_MOVE){if(e.getPointerCount()>=2){float g=gap(e);dispatchWheel((initialGap-g)*300);initialGap=g;}else{dispatchMouse("pointermove",x,y,0,1);lastX=x;lastY=y;}return true;}
                    if(action==MotionEvent.ACTION_UP){int button=System.currentTimeMillis()-start>650?2:0;dispatchMouse("pointerup",x,y,button,0);dispatchMouse(button==2?"contextmenu":"click",x,y,button,0);return true;}
                    if(action==MotionEvent.ACTION_CANCEL){dispatchMouse("pointercancel",x,y,0,0);return true;}return true;
                }
            });
        }
    }
    private static float gap(MotionEvent e){float dx=e.getX(0)-e.getX(1),dy=e.getY(0)-e.getY(1);return (float)Math.sqrt(dx*dx+dy*dy);}
    private void addKey(LinearLayout row,String label){String key=keys.get(label);if(key==null)key=label;String finalKey=key;
        Button b=new Button(activity);b.setAllCaps(false);b.setText(label);b.setTextSize(11);b.setMinimumWidth(0);b.setMinWidth(0);b.setPadding(2,0,2,0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,55,1);row.addView(b,p);
        b.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){dispatchKey(finalKey,true);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){dispatchKey(finalKey,false);return true;}return true;
        });
    }
    private void button(LinearLayout row,String label,Runnable r){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);row.addView(b);b.setOnClickListener(v->r.run());}
    /** Install standards-compatible Gamepad state in the local preview only. */
    public void installBridge(){
        js("(function(){if(window.__touchBridgeGamepadInstalled)return;window.__touchBridgeGamepadInstalled=true;"+
          "var state=window.__touchBridge=window.__touchBridge||{keys:{},axes:[0,0],buttons:{}};"+
          "var pad={id:'Canceled Agent TouchBridge Virtual Controller',index:0,connected:true,mapping:'standard',"+
          "timestamp:0,axes:[0,0,0,0],buttons:Array.from({length:17},function(){return {pressed:false,touched:false,value:0}})};"+
          "state.gamepad=pad;state.axes=pad.axes;"+
          "try{Object.defineProperty(navigator,'getGamepads',{configurable:true,value:function(){return [pad,null,null,null]}})}catch(e){state.getGamepads=function(){return [pad]}}"+
          "try{var evt=new Event('gamepadconnected');Object.defineProperty(evt,'gamepad',{value:pad});window.dispatchEvent(evt)}catch(e){}"+
          "})()");
    }
    public void dispatchKey(String key,boolean down){
        String safe=JSONObject.quote(key);String kind=down?"keydown":"keyup";
        // Expose both DOM keyboard and a deterministic virtual gamepad state to owned HTML.
        js("(function(){let k="+safe+";let state=window.__touchBridge=window.__touchBridge||{keys:{},axes:[0,0],buttons:{}};state.keys[k]="+(down?"true":"false")+";state.buttons[k]="+(down?"true":"false")+";let mapping={ArrowUp:12,ArrowDown:13,ArrowLeft:14,ArrowRight:15,A:0,B:1,X:2,Y:3,L:4,R:5,Q:6,E:7,Enter:9,' ':8};"+
          "if(state.gamepad){let i=mapping[k];if(i!==undefined){let b=state.gamepad.buttons[i];b.pressed="+down+";b.touched="+down+";b.value="+(down?"1":"0")+";}"+
          "state.gamepad.axes[0]=(state.keys.ArrowRight?1:0)-(state.keys.ArrowLeft?1:0);"+
          "state.gamepad.axes[1]=(state.keys.ArrowDown?1:0)-(state.keys.ArrowUp?1:0);state.gamepad.timestamp=performance.now();}"+
          "let code=k===' '?'Space':k.length===1?'Key'+k.toUpperCase():k;let ev=new KeyboardEvent('"+kind+"',{key:k,code:code,bubbles:true,cancelable:true});"+
          "let el=document.activeElement||document;el.dispatchEvent(ev);if(el!==document)document.dispatchEvent(new KeyboardEvent('"+kind+"',{key:k,code:code,bubbles:true}));"+
          "window.dispatchEvent(new CustomEvent('touchbridge',{detail:{kind:'key',key:k,down:"+down+"}}));})()");
    }
    private void dispatchMouse(String type,float x,float y,int button,int buttons){
        js("(function(){let x="+x+",y="+y+";let px=Math.max(0,Math.min(innerWidth-1,innerWidth*x));let py=Math.max(0,Math.min(innerHeight-1,innerHeight*y));"+
           "let el=document.elementFromPoint(px,py)||document.body;let t="+JSONObject.quote(type)+";"+
           "let o={clientX:px,clientY:py,button:"+button+",buttons:"+buttons+",pointerId:1,pointerType:'touch',isPrimary:true,bubbles:true,cancelable:true,view:window};"+
           "let ev=t.indexOf('pointer')===0&&window.PointerEvent?new PointerEvent(t,o):new MouseEvent(t,o);el.dispatchEvent(ev);"+
           "if(t==='pointerdown')el.dispatchEvent(new MouseEvent('mousedown',o));if(t==='pointerup')el.dispatchEvent(new MouseEvent('mouseup',o));"+
           "window.dispatchEvent(new CustomEvent('touchbridge',{detail:{kind:'pointer',type:t,x:px,y:py}}));})()");
    }
    private void dispatchWheel(float delta){js("(function(){let el=document.activeElement||document.body;el.dispatchEvent(new WheelEvent('wheel',{deltaY:"+delta+",bubbles:true,cancelable:true}));})()");}
    private void js(String code){target.post(()->target.evaluateJavascript(code,null));}
    private void restore(){
        mode=prefs.get("touchbridge-mode","dual");if(!Arrays.asList("dual","keyboard","pointer","gamepad","hidden").contains(mode))mode="dual";
        for(String label:new String[]{"◀","▶","▲","▼","Q","E","A","B","X","Y","L","R","Enter","Space"}){
            String fallback=label.equals("◀")?"ArrowLeft":label.equals("▶")?"ArrowRight":label.equals("▲")?"ArrowUp":label.equals("▼")?"ArrowDown":label.equals("Space")?" ":label;
            keys.put(label,prefs.get("touchbridge-key-"+label,fallback));
        }
    }
    private void store(){prefs.put("touchbridge-mode",mode);for(Map.Entry<String,String> e:keys.entrySet())prefs.put("touchbridge-key-"+e.getKey(),e.getValue());}
    public JSONObject exportProfile(){JSONObject o=new JSONObject();try{JSONObject map=new JSONObject();for(Map.Entry<String,String> e:keys.entrySet())map.put(e.getKey(),e.getValue());o.put("schema","independent-touchbridge/1").put("mode",mode).put("keys",map);}catch(Exception e){throw new IllegalStateException(e);}return o;}
    public void importProfile(JSONObject o){if(!"independent-touchbridge/1".equals(o.optString("schema")))throw new IllegalArgumentException("Unsupported profile schema");
        String m=o.optString("mode");if(!Arrays.asList("dual","keyboard","pointer","gamepad","hidden").contains(m))throw new IllegalArgumentException("Bad controller mode");
        JSONObject j=o.optJSONObject("keys");if(j==null)throw new IllegalArgumentException("Missing keymap");
        for(Iterator<String> it=j.keys();it.hasNext();){String k=it.next();if(!keys.containsKey(k))throw new IllegalArgumentException("Unknown button "+k);String v=j.optString(k);if(v.length()>40)throw new IllegalArgumentException("Oversized key name");keys.put(k,v);}
        mode=m;store();show();
    }
    /** Validates the live preview bridge without loading a test site or replacing project content. */
    public void selfTest(){
        installBridge();
        String script="(function(){var t=window.__touchBridge;var pad=typeof navigator.getGamepads==='function'?navigator.getGamepads()[0]:null;"+
           "var events=[];var handler=function(e){events.push({type:e.type,key:e.key})};document.addEventListener('keydown',handler,true);"+
           "document.dispatchEvent(new KeyboardEvent('keydown',{key:'F9',code:'F9',bubbles:true}));document.removeEventListener('keydown',handler,true);"+
           "return JSON.stringify({bridgeInstalled:!!window.__touchBridgeGamepadInstalled,gamepadAvailable:!!pad,"+
           "buttons:pad&&pad.buttons?pad.buttons.length:0,axes:pad&&pad.axes?pad.axes.length:0,"+
           "keyboardEvents:events,profileMode:"+JSONObject.quote(mode)+"})})()";
        target.post(()->target.evaluateJavascript(script,value->{
            String report=value;
            try{report=new JSONArray("["+value+"]").getString(0);report=new JSONObject(report).toString(2);}
            catch(Exception e){report="WebView evaluation response: "+value+"; "+e.getMessage();}
            new AlertDialog.Builder(activity).setTitle("Live TouchBridge compatibility self-test")
                .setMessage(report).setPositiveButton("Close",null).show();
        }));
    }
    private void profiles(){LinearLayout layout=new LinearLayout(activity);layout.setOrientation(1);ScrollView scroll=new ScrollView(activity);scroll.addView(layout);
        TextView title=new TextView(activity);title.setText("Map buttons to DOM KeyboardEvent.key names. Profiles are saved to the app, not a website.");layout.addView(title);
        LinkedHashMap<String,EditText> edit=new LinkedHashMap<>();for(Map.Entry<String,String> e:keys.entrySet()){
            EditText t=new EditText(activity);t.setSingleLine(true);t.setHint(e.getKey());t.setText(e.getValue());layout.addView(t);edit.put(e.getKey(),t);
        }
        new AlertDialog.Builder(activity).setTitle("TouchBridge mappings").setView(scroll).setPositiveButton("Save",(d,w)->{
            for(Map.Entry<String,EditText> e:edit.entrySet()){String v=e.getValue().getText().toString();if(v.length()<=40)keys.put(e.getKey(),v);}store();show();
        }).setNeutralButton("Export profile",(d,w)->{
            String json=exportProfile().toString();android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(Activity.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TouchBridge profile",json));Toast.makeText(activity,"Profile copied as JSON",Toast.LENGTH_SHORT).show();
        }).setNegativeButton("Import profile",(d,w)->{
            EditText data=new EditText(activity);data.setSingleLine(false);data.setMinLines(7);
            new AlertDialog.Builder(activity).setTitle("Import TouchBridge JSON").setView(data).setPositiveButton("Import",(a,b)->{
                try{importProfile(new JSONObject(data.getText().toString()));}catch(Exception ex){Toast.makeText(activity,ex.getMessage(),Toast.LENGTH_LONG).show();}
            }).setNegativeButton("Cancel",null).show();
        }).show();
    }
}
