package android.net.nsd;
import java.net.InetAddress;
public final class NsdServiceInfo {
 public final String name;public final InetAddress host;public final int port;
 public NsdServiceInfo(String name,InetAddress host,int port){this.name=name;this.host=host;this.port=port;}
 public String getServiceName(){return name;}
 public InetAddress getHost(){return host;}
 public int getPort(){return port;}
}
