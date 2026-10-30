package org.canceledagent.app;

import android.content.Context;
import org.json.JSONObject;

/** Saved native profile replaces the Perchance blueprint protocol import.
 * The source's default is Full with git, thinking, plugins overlays enabled.
 * No instructions from a remote plugin are executed by this app.
 */
public final class WorkflowProfile {
    private final SecurePrefs settings;
    public WorkflowProfile(Context context){settings=new SecurePrefs(context);}
    public String mode(){return settings.get("mode","Full");}
    public void mode(String value){
        if(!"Core".equals(value)&&!"Full".equals(value))throw new IllegalArgumentException("Unknown mode");
        settings.put("mode",value);
    }
    public boolean overlay(String feature){
        validate(feature);return Boolean.parseBoolean(settings.get("overlay-"+feature,"true"));
    }
    public void overlay(String feature,boolean enabled){validate(feature);settings.put("overlay-"+feature,Boolean.toString(enabled));}
    private static void validate(String f){if(!"git".equals(f)&&!"thinking".equals(f)&&!"plugins".equals(f))throw new IllegalArgumentException("Unknown overlay");}
    public JSONObject snapshot(){
        JSONObject result=new JSONObject();
        try{result.put("mode",mode()).put("git",overlay("git")).put("thinking",overlay("thinking")).put("plugins",overlay("plugins"));}
        catch(Exception e){throw new IllegalStateException(e);}
        return result;
    }
}
