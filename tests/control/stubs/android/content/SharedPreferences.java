package android.content;
import java.util.*;
public final class SharedPreferences {
 private final Map<String,String> values=new HashMap<>();
 public Map<String,?> getAll(){return new HashMap<>(values);}
 public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
 public Editor edit(){return new Editor();}
 public final class Editor {
  private final Map<String,String> updates=new HashMap<>();
  public Editor putString(String k,String v){updates.put(k,v);return this;}
  public Editor remove(String k){updates.put(k,null);return this;}
  public void apply(){updates.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});}
 }
}
