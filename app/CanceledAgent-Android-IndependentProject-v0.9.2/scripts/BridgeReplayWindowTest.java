import org.canceledagent.app.BridgeReplayWindow;
public final class BridgeReplayWindowTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        BridgeReplayWindow r = new BridgeReplayWindow();long now=1783000000000L;
        check(r.accept("first",now),"first request");
        check(!r.accept("first",now+1000),"immediate replay refused");
        check(r.accept("second",now),"distinct ID allowed");
        check(!r.accept("first",now+BridgeReplayWindow.HOLD_MS),"replay within retention refused");
        check(r.accept("first",now+BridgeReplayWindow.HOLD_MS+1),"expired ID released");
        r.reset();
        for(int i=0;i<BridgeReplayWindow.MAX_REQUESTS;i++)check(r.accept("id-"+i,now),"filling replay window");
        check(!r.accept("overflow",now),"full replay window fails closed");
        check(!r.accept("id-0",now),"overflow cannot evict old ID");
        r.reset();check(r.accept("id-0",now),"connection reset");
        System.out.println("BridgeReplayWindow 9/9");
    }
}
