package android.content;
import java.util.*;
public final class Context {
 public static final String NSD_SERVICE="nsd", WIFI_SERVICE="wifi";
 public static final int MODE_PRIVATE=0;
 private final Object service;
 private final Map<String,SharedPreferences> preferences=new HashMap<>();
 public Object wifiService;
 public Context(Object service){this.service=service;}
 public Object getSystemService(String name){return WIFI_SERVICE.equals(name)?wifiService:service;}
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String name,int mode){return preferences.computeIfAbsent(name,k->new SharedPreferences());}
}
