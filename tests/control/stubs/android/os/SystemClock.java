package android.os;
public final class SystemClock {
 static volatile long now=1000;
 public static long elapsedRealtime(){return now;}
}
