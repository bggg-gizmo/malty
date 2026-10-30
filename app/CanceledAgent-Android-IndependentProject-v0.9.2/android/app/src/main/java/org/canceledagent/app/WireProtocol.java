package org.canceledagent.app;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/** Independent Java equivalent of recovered src/cron.js nextWireLine + safe fold insertion. */
public final class WireProtocol {
    private WireProtocol(){}
    public static String nextWireLine(String line,List<String> signals){
        if(line==null||line.isEmpty())line="WIRE v1";
        Matcher m=Pattern.compile("WIRE v(\\d+)").matcher(line);int version=m.find()?Integer.parseInt(m.group(1))+1:2;
        line=Pattern.compile("WIRE v\\d+").matcher(line).replaceFirst("WIRE v"+version);
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Matcher date=Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?Z?").matcher(line);
        if(date.find())line=date.replaceFirst(f.format(new Date()));
        Matcher doc=Pattern.compile("(builds|pricing|hello)=(\\d+)").matcher(line);
        if(doc.find())line=doc.replaceFirst(doc.group(1)+"="+(Integer.parseInt(doc.group(2))+1));
        List<String> safe=new ArrayList<>();if(signals!=null)for(String s:signals)if(s!=null&&!s.trim().isEmpty())safe.add(s.trim());
        if(!safe.isEmpty())line=line.replaceFirst("\\s*$","")+(line.contains("signals:")?"; ":" · signals: ")+String.join("; ",safe);
        return line;
    }
    public static String fold(String current,List<String> additions){
        String[] parts=current.split("\\n",-1);int index=-1;for(int i=0;i<parts.length;i++)if(parts[i].startsWith("WIRE v")){index=i;break;}
        if(index<0)throw new IllegalArgumentException("No machine-owned WIRE footer; fold is held");
        List<String> lines=new ArrayList<>(Arrays.asList(parts));String next=nextWireLine(parts[index],Collections.singletonList("autonomous-era"));lines.set(index,next);
        int position=index;
        for(String entry:additions){String line=entry.trim();if(!line.isEmpty()&&!current.contains(line.substring(0,Math.min(40,line.length()))))lines.add(position++,line);}
        return String.join("\n",lines);
    }
}
