package android.net.nsd;
import java.util.*;
public final class NsdManager {
 public static final int PROTOCOL_DNS_SD=1;
 public interface DiscoveryListener {
  void onDiscoveryStarted(String t);void onServiceFound(NsdServiceInfo i);void onServiceLost(NsdServiceInfo i);void onDiscoveryStopped(String t);void onStartDiscoveryFailed(String t,int c);void onStopDiscoveryFailed(String t,int c);
 }
 public interface ResolveListener {void onServiceResolved(NsdServiceInfo i);void onResolveFailed(NsdServiceInfo i,int c);}
 public final List<DiscoveryListener> listeners=new ArrayList<>();
 public NsdServiceInfo info;public int failStarts;public boolean announce=true;
 public void discoverServices(String type,int protocol,DiscoveryListener l){listeners.add(l);if(failStarts>0){failStarts--;l.onStartDiscoveryFailed(type,3);}else{l.onDiscoveryStarted(type);if(announce)l.onServiceFound(info);}}
 public void stopServiceDiscovery(DiscoveryListener l){l.onDiscoveryStopped("_dacp._tcp");}
 public void resolveService(NsdServiceInfo info,ResolveListener l){l.onServiceResolved(info);}
 public DiscoveryListener current(){return listeners.get(listeners.size()-1);}
}
