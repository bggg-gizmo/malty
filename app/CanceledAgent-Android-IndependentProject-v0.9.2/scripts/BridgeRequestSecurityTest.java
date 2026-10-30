import org.canceledagent.app.BridgeRequestSecurity;
public final class BridgeRequestSecurityTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        String secret="test-only-not-a-real-access-token",challenge="challenge",session="session",id="id1";
        String scope="bridge:write",body="{\"op\":\"mesh\",\"action\":\"send\"}";long now=1783000000000L;
        String signature=BridgeRequestSecurity.sign(secret,challenge,session,id,now,scope,body);
        check(BridgeRequestSecurity.verify(secret,challenge,session,challenge,session,id,now,scope,body,signature,now),"valid");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,challenge,session,id,now,scope,body+" ",signature,now),"tampered body");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,challenge,session,id,now,"bridge:read",body,signature,now),"wrong scope");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,challenge,"wrong-session",id,now,scope,body,signature,now),"wrong session");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,"wrong-challenge",session,id,now,scope,body,signature,now),"wrong challenge");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,challenge,session,id,now-120001,scope,body,signature,now),"expired");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,challenge,session,id,now+120001,scope,body,signature,now),"future");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,challenge,session,id,now,scope,body,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",now),"forged");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,challenge,session,"bad\nid",now,scope,body,signature,now),"malformed ID");
        check(!BridgeRequestSecurity.verify(secret,challenge,session,challenge,session,id,now,"admin",body,signature,now),"unrecognized scope");
        System.out.println("BridgeRequestSecurity 10/10");
    }
}
