package android.net.nsd;
import java.net.InetAddress;
public final class NsdServiceInfo {
 public String name,type;public InetAddress host;public int port;
 public NsdServiceInfo(){}
 public NsdServiceInfo(String name,InetAddress host,int port){this.name=name;this.host=host;this.port=port;}
 public String getServiceName(){return name;}
 public void setServiceName(String value){name=value;}
 public String getServiceType(){return type;}
 public void setServiceType(String value){type=value;}
 public void setPort(int value){port=value;}
 public void setAttribute(String key,String value){}
 public InetAddress getHost(){return host;}
 public int getPort(){return port;}
}
