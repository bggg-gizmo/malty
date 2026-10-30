import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyStore;
import javax.net.ssl.*;
import org.canceledagent.app.BridgeTransportSecurity;
public final class BridgeTransportSecurityTest{
    static SSLContext context(String path,boolean server)throws Exception{
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(java.io.FileInputStream in=new java.io.FileInputStream(path)){store.load(in,"changeit".toCharArray());}
        SSLContext ctx=SSLContext.getInstance("TLS");
        if(server){KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(store,"changeit".toCharArray());ctx.init(km.getKeyManagers(),null,null);}
        else{TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(store);ctx.init(null,tm.getTrustManagers(),null);}return ctx;
    }
    static void check(String host,boolean expected,SSLContext server,SSLContext client)throws Exception{
        try(SSLServerSocket listener=(SSLServerSocket)server.getServerSocketFactory().createServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
            Thread worker=new Thread(()->{try(SSLSocket peer=(SSLSocket)listener.accept()){
                peer.setSoTimeout(5000);peer.startHandshake();peer.getInputStream().read();}catch(Exception ignored){}});
            worker.start();boolean success;
            try(SSLSocket s=BridgeTransportSecurity.connect(client.getSocketFactory(),host,listener.getLocalPort(),5000)){
                s.getOutputStream().write(1);success=true;
            }catch(IOException expectedFailure){success=false;}
            worker.join(6000);
            if(worker.isAlive()||success!=expected)throw new AssertionError("TLS identity "+host+" expected="+expected+" actual="+success);
        }
    }
    public static void main(String[] args)throws Exception{
        SSLContext server=context(args[0],true),client=context(args[1],false);
        check("localhost",true,server,client);
        check("127.0.0.1",false,server,client);
        System.out.println("BridgeTransportSecurity matching-host accepted; wrong-host rejected");
    }
}
