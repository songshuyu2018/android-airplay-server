package android.net.wifi;
public final class WifiManager {
 public static final int WIFI_MODE_FULL_HIGH_PERF=3;
 public int multicastCreated,wifiCreated;public boolean failMulticast,failWifi;
 public MulticastLock multicast;public WifiLock wifi;
 public static class Lock {
  boolean held;public int acquires,releases;boolean fail;
  public void setReferenceCounted(boolean value){}
  public void acquire(){if(fail)throw new SecurityException();held=true;acquires++;}
  public void release(){if(!held)throw new AssertionError("unbalanced lock");held=false;releases++;}
  public boolean isHeld(){return held;}
 }
 public static final class MulticastLock extends Lock{}
 public static final class WifiLock extends Lock{}
 public MulticastLock createMulticastLock(String tag){multicastCreated++;multicast=new MulticastLock();multicast.fail=failMulticast;return multicast;}
 public WifiLock createWifiLock(int mode,String tag){if(mode!=3)throw new AssertionError();wifiCreated++;wifi=new WifiLock();wifi.fail=failWifi;return wifi;}
}
