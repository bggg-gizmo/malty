package org.canceledagent.app;

import android.content.Context;
import java.security.SecureRandom;
import android.util.Base64;

/** Local server access token, encrypted via Android Keystore. */
public final class RuntimeSecrets {
    public static synchronized String ensure(Context context){SecurePrefs s=new SecurePrefs(context);String key=s.readSecret("localServiceToken");if(key.isEmpty()){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);key=Base64.encodeToString(bytes,Base64.NO_WRAP|Base64.URL_SAFE);s.secret("localServiceToken",key);}return key;}
    public static boolean local(String base){return base.equals("http://127.0.0.1:8000")||base.equals("http://localhost:8000");}
}
