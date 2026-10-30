package org.canceledagent.app;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Random;

/** Original offline generative synthesis plus real independent internet station dial. */
public final class RadioActivity extends Activity {
    private static final int RATE=22050;
    private AudioTrack track;
    private Thread synthThread;
    private MediaPlayer stream;
    private volatile boolean playing=false;
    private volatile int channel=0;
    private volatile double tempo=110;
    private volatile float volume=0.6f;
    private Network network;
    private SecurePrefs settings;
    private final java.util.Set<String> favorites=new java.util.LinkedHashSet<>();
    private LinearLayout stations;
    private TextView status;
    private final int bg=Color.rgb(11,17,25),fg=Color.rgb(224,234,245),accent=Color.rgb(62,208,189);
    private TextView line(LinearLayout p,String t,int sz){TextView v=new TextView(this);v.setText(t);v.setTextSize(sz);v.setTextColor(fg);v.setPadding(8,9,8,9);p.addView(v);return v;}
    private void button(LinearLayout p,String title,Runnable onClick){Button b=new Button(this);b.setText(title);b.setAllCaps(false);p.addView(b);b.setOnClickListener(v->onClick.run());}
    @Override public void onCreate(Bundle b){super.onCreate(b);settings=new SecurePrefs(this);network=new Network(this,settings);
        try{JSONArray stored=new JSONArray(settings.get("radioFavorites","[]"));for(int i=0;i<stored.length();i++)favorites.add(stored.optString(i));}catch(Exception ignored){}
        try{volume=Math.max(0,Math.min(1,Float.parseFloat(settings.get("radioVolume","0.6"))));}catch(Exception ignored){}
        LinearLayout root=new LinearLayout(this);root.setPadding(16,16,16,12);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);setContentView(root);
        line(root,"LUX RADIO  /  INDEPENDENT",21).setTextColor(accent);
        line(root,"Three generative channels synthesized offline. Independent station dial uses your own server.",13);
        button(root,"Still Becoming • Start",()->startSynth(0));
        button(root,"Plugg Dusk • Start",()->startSynth(1));
        button(root,"Trap Night • Start",()->startSynth(2));
        SeekBar speed=new SeekBar(this);speed.setMax(100);speed.setProgress(45);root.addView(speed);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean from){tempo=65+p;}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        line(root,"Volume",12);
        SeekBar vol=new SeekBar(this);vol.setMax(100);vol.setProgress(Math.round(volume*100));root.addView(vol);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean from){volume=p/100f;settings.put("radioVolume",Float.toString(volume));AudioTrack a=track;if(a!=null)a.setVolume(volume);MediaPlayer m=stream;if(m!=null)m.setVolume(volume,volume);}
            public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}
        });
        button(root,"Stop playback",this::stopAll);
        status=line(root,"Not playing",14);
        button(root,"Refresh internet-station dial",this::loadStations);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));stations=new LinearLayout(this);stations.setOrientation(LinearLayout.VERTICAL);scroll.addView(stations);
        loadStations();
    }
    private void loadStations(){network.backend("GET","/api/workflows/radio/dial",null,new Network.Result(){
        public void success(JSONObject j){stations.removeAllViews();JSONArray a=j.optJSONArray("stations");if(a==null){status.setText(j.toString());return;}
            java.util.List<JSONObject> rows=new java.util.ArrayList<>();for(int i=0;i<a.length();i++)if(a.optJSONObject(i)!=null)rows.add(a.optJSONObject(i));
            rows.sort((x,y)->Boolean.compare(favorites.contains(y.optString("url")),favorites.contains(x.optString("url"))));
            for(JSONObject row:rows){String url=row.optString("url"),title=row.optString("name","Station");
                LinearLayout line=new LinearLayout(RadioActivity.this);line.setOrientation(LinearLayout.HORIZONTAL);stations.addView(line);
                Button play=new Button(RadioActivity.this);play.setAllCaps(false);play.setText(title+" · "+row.optString("genre","radio"));
                line.addView(play,new LinearLayout.LayoutParams(0,-2,1));play.setOnClickListener(v->playStation(url));
                Button star=new Button(RadioActivity.this);star.setText(favorites.contains(url)?"★":"☆");star.setContentDescription("Toggle favorite for "+title);line.addView(star);
                star.setOnClickListener(v->{if(!favorites.add(url))favorites.remove(url);settings.put("radioFavorites",new JSONArray(favorites).toString());loadStations();});
            }
        }
        public void failure(String msg){status.setText("Station dial: "+msg);}
    });}
    private void playStation(String url){stopAll();
        try{java.net.URI uri=new java.net.URI(url);String scheme=uri.getScheme();
            if(!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme))||uri.getHost()==null||uri.getUserInfo()!=null){status.setText("Station URL must be HTTP(S) without embedded credentials");return;}}
        catch(Exception invalid){status.setText("Station URL is invalid");return;}
        try{stream=new MediaPlayer();stream.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            stream.setDataSource(url);stream.setVolume(volume,volume);stream.setOnPreparedListener(mp->{mp.start();status.setText("Internet station playing");});
            stream.setOnErrorListener((mp,what,extra)->{status.setText("Station playback failed: "+what);return true;});
            stream.prepareAsync();status.setText("Connecting to station…");}
        catch(Exception e){status.setText("Unable to start stream: "+e.getMessage());}
    }
    private void startSynth(int requested){stopAll();channel=requested;playing=true;
        int n=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        int size=Math.max(8192,n*2);
        track=new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),size,AudioTrack.MODE_STREAM,AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.setVolume(volume);track.play();status.setText((channel==0?"Still Becoming":channel==1?"Plugg Dusk":"Trap Night")+" synthesized offline");
        synthThread=new Thread(this::synthesize,"LuxAudioSynth");synthThread.setDaemon(true);synthThread.start();
    }
    private void synthesize(){
        final Random rand=new Random(System.nanoTime());
        final int[] minor={0,3,7,10,5,8,12,15};
        final double[] roots={55.0,49.0,46.25,51.91};
        final int bufferSize=2048;short[] block=new short[bufferSize];long sample=0;
        double lp=0,noiseLP=0;int previousStep=-1;
        while(playing){
            for(int i=0;i<block.length;i++,sample++){
                double sec=sample/(double)RATE;double bpm=tempo;
                double beatSec=60.0/bpm;double beat=(sec/beatSec);
                int step=(int)Math.floor(beat*4.0);double beatPhase=(beat*4.0)%1.0;
                int bar=(step/16)%4;
                double root=roots[bar];
                double t=sec, bassNote=root*Math.pow(2,minor[(step/4)%minor.length]/12.0);
                double sub=Math.sin(2*Math.PI*bassNote*t);
                double fundamental=Math.sin(2*Math.PI*root*t);
                double pad=0;
                for(int k=0;k<3;k++){
                    double chord=root*Math.pow(2,minor[(k*2+(bar%2))%minor.length]/12.0)*4.0;
                    pad+=Math.sin(2*Math.PI*(chord+0.12*Math.sin(t*.11+k))*t+k*.7)/3.0;
                }
                double decay=Math.exp(-beatPhase*16);
                double kick=((step%4)==0)?Math.sin(2*Math.PI*(50.0+85.0*Math.exp(-beatPhase*12))*beatPhase*beatSec/4)*decay:0;
                double snare=(step%8==4)?(rand.nextDouble()*2-1)*Math.exp(-beatPhase*21):0;
                double hiHat=(step%2==0)?(rand.nextDouble()*2-1)*Math.exp(-beatPhase*33):0;
                double signal;
                if(channel==0){
                    // Still Becoming: sparse drifting pads, low sub and suspended upper harmonics.
                    double shimmer=.12*Math.sin(2*Math.PI*(root*6.0+0.8*Math.sin(t*.07))*t);
                    signal=.46*pad+.14*fundamental+.11*sub+.04*kick+shimmer;
                    signal=Math.tanh(signal*.87)*.72;
                }else if(channel==1){
                    // Plugg Dusk: syncopated sub, pitched lead, soft-clipped kick and tight hats.
                    double lead=Math.sin(2*Math.PI*(bassNote*4.0)*t)*Math.exp(-beatPhase*5);
                    signal=.34*sub+.22*kick+.16*lead+.11*snare+.06*hiHat+.10*pad;
                    signal=Math.tanh(signal*1.35)*.68;
                }else{
                    // Trap Night: deeper, slower pad color with noise and upper metallic shimmer.
                    double shimmer=Math.sin(2*Math.PI*(root*8.0+Math.sin(t*.13)*1.4)*t)*.07;
                    double noise=(rand.nextDouble()*2-1)*.04;
                    noiseLP=.992*noiseLP+.008*noise;
                    signal=.25*pad+.14*fundamental+.12*sub+.10*kick+.045*hiHat+.045*snare+shimmer+noiseLP;
                    signal=Math.tanh(signal*.9)*.72;
                }
                lp=.67*lp+.33*signal;
                block[i]=(short)(Math.max(-1,Math.min(1,lp))*32000);
            }
            try{AudioTrack current=track;if(current==null)break;int n=current.write(block,0,block.length);
                if(n<0)break;}catch(Exception ex){break;}
        }
    }
    private synchronized void stopAll(){playing=false;
        if(track!=null){try{track.pause();track.flush();track.release();}catch(Exception ignored){}track=null;}
        if(stream!=null){try{stream.stop();}catch(Exception ignored){}try{stream.release();}catch(Exception ignored){}stream=null;}
        if(status!=null)status.setText("Stopped");
    }
    @Override protected void onDestroy(){stopAll();super.onDestroy();}
}
