package android.content;
public final class Context {
 public static final String NSD_SERVICE="nsd";
 private final Object service;
 public Context(Object service){this.service=service;}
 public Object getSystemService(String name){return service;}
}
