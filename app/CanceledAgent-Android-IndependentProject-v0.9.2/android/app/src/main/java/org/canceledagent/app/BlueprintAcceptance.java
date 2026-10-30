package org.canceledagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Pattern;

/** Executable, offline Blueprint acceptance contract; all checks run on the real saved project source. */
public final class BlueprintAcceptance {
    private BlueprintAcceptance(){}
    public static JSONObject baseline(String projectId,String source,int revision)throws Exception {
        return new JSONObject().put("schema","canceled-agent-blueprint/1").put("projectId",projectId)
            .put("checks",new JSONArray()
                .put(new JSONObject().put("id","source-integrity").put("type","sha256").put("value",BlueprintProtocol.sha256(source)))
                .put(new JSONObject().put("id","revision-history").put("type","min_revision").put("value",revision)));
    }
    public static JSONObject evaluate(String protocolText,String projectId,String source,int revision)throws Exception {
        JSONArray results=new JSONArray();JSONObject result=new JSONObject().put("schema","canceled-agent-blueprint/1").put("checks",results);
        JSONObject contract;
        try{contract=new JSONObject(protocolText);}catch(Exception ex){return result.put("ok",false).put("error","Blueprint must be structured JSON with executable checks, not an unchecked text label");}
        if(!"canceled-agent-blueprint/1".equals(contract.optString("schema")))return result.put("ok",false).put("error","Unsupported Blueprint schema");
        if(contract.has("projectId")&&!projectId.equals(contract.optString("projectId")))return result.put("ok",false).put("error","Blueprint adopted into a different project");
        JSONArray checks=contract.optJSONArray("checks");
        if(checks==null||checks.length()==0||checks.length()>100)return result.put("ok",false).put("error","Blueprint requires 1 to 100 executable checks");
        boolean pass=true;
        for(int i=0;i<checks.length();i++){
            JSONObject check=checks.optJSONObject(i);String id=check==null?"check-"+(i+1):check.optString("id","check-"+(i+1));
            String type=check==null?"":check.optString("type"),expected=check==null?"":check.optString("value");
            boolean ok=false;String detail="";
            if(expected.length()>40000){detail="Acceptance parameter exceeds limit";}
            else switch(type){
                case "sha256":ok=expected.matches("(?i)[a-f0-9]{64}")&&BlueprintProtocol.sha256(source).equalsIgnoreCase(expected);detail="Project source digest";break;
                case "contains":ok=!expected.isEmpty()&&source.contains(expected);detail="Required source text present";break;
                case "not_contains":ok=!expected.isEmpty()&&!source.contains(expected);detail="Forbidden source text absent";break;
                case "html_tag":ok=expected.matches("[A-Za-z][A-Za-z0-9-]{0,39}")&&Pattern.compile("(?is)<\\s*"+Pattern.quote(expected)+"(?:\\s|>|/)").matcher(source).find();detail="HTML element present";break;
                case "min_revision":try{ok=revision>=Integer.parseInt(expected);detail="Minimum saved revision";}catch(NumberFormatException ex){detail="Invalid revision number";}break;
                case "max_length":try{ok=source.length()<=Integer.parseInt(expected);detail="Maximum UTF-16 source length";}catch(NumberFormatException ex){detail="Invalid length";}break;
                case "equals":ok=source.equals(expected);detail="Exact project source equality";break;
                default:detail="Unknown or missing acceptance type";
            }
            pass&=ok;results.put(new JSONObject().put("id",id).put("type",type).put("pass",ok).put("detail",detail));
        }
        return result.put("ok",pass).put("passed",count(results,true)).put("failed",count(results,false)).put("total",results.length());
    }
    private static int count(JSONArray checks,boolean pass){int n=0;for(int i=0;i<checks.length();i++)if(checks.optJSONObject(i).optBoolean("pass")==pass)n++;return n;}
}
