package org.canceledagent.app;
import java.util.*;
public final class WireProtocolContractTest {
    private static int checks=0;
    private static void expect(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        String original="Header\nNew result\nWIRE v7 · builds=8 · 2026-10-20T12:00:00Z · signals: old";
        String result=WireProtocol.fold(original,Arrays.asList("New result","Accepted contract"));
        expect(result.contains("WIRE v8"),"WIRE version increases");
        expect(result.contains("builds=9"),"doc counter increases");
        expect(result.contains("signals: old; autonomous-era"),"existing signals retained");
        expect(result.contains("New result\nAccepted contract\nWIRE"),"accepted line inserted before footer, duplicate suppressed");
        expect(!result.contains("WIRE v7"),"old footer replaced");
        expect(result.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z.*"),"timestamp refreshed");
        String fixed=WireProtocol.nextWireLine("WIRE v1 · pricing=6",Arrays.asList("autonomous-era"));
        expect(fixed.contains("WIRE v2")&&fixed.contains("pricing=7"),"pricing preserved");
        expect(fixed.contains("signals: autonomous-era"),"signals inserted");
        boolean refused=false;try{WireProtocol.fold("No footer",Arrays.asList("A"));}catch(IllegalArgumentException e){refused=true;}
        expect(refused,"refuse missing footer, no destructive edits");
        System.out.println("Android Java WIRE protocol: "+checks+"/"+checks+" passed");
    }
}
