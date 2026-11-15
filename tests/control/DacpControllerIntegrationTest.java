import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import io.github.jqssun.airplay.discovery.NsdServiceManager;
import android.os.Handler;
import com.sun.net.httpserver.HttpServer;
import com.google.common.util.concurrent.ListenableFuture;
import io.github.jqssun.airplay.audio.DacpController;
import kotlin.Unit;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class DacpControllerIntegrationTest {
 static final List<DacpController> controllers=new ArrayList<>();
 static final Queue<String> received=new ConcurrentLinkedQueue<>();
 static final Queue<String> credentials=new ConcurrentLinkedQueue<>();
 static final AtomicInteger failures=new AtomicInteger();
 static void check(boolean condition){if(!condition)throw new AssertionError();}
 static void until(BooleanSupplier test)throws Exception{
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  do{Handler.drain();if(test.getAsBoolean())return;Thread.sleep(2);}while(System.nanoTime()<end);
  throw new AssertionError("async condition timed out");
 }
 static DacpController controller(NsdManager nsd){
  DacpController c=new DacpController(new Context(nsd),()->Unit.INSTANCE);
  controllers.add(c);c.update("sender","token");Handler.drain();return c;
 }
 static NsdManager discovery(int port)throws Exception{
  NsdManager nsd=new NsdManager();nsd.info=new NsdServiceInfo("iTunes_Ctrl_sender",InetAddress.getByName("127.0.0.1"),port);return nsd;
 }
 public static void main(String[]args)throws Exception{
  // Reserve the fixed fallback before asking the OS for any ephemeral ports.
  HttpServer fallback=HttpServer.create(new InetSocketAddress("127.0.0.1",39831),0);
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/ctrl-int/1/",exchange->{
   received.add(exchange.getRequestURI().getPath());
   credentials.add(exchange.getRequestHeaders().getFirst("Active-Remote"));
   int code=failures.getAndUpdate(n->Math.max(0,n-1))>0?503:204;
   exchange.sendResponseHeaders(code,-1);exchange.close();
  });server.start();int port=server.getAddress().getPort();
  try{
   // NSD transient loss cannot grey out capability or discard a working endpoint.
   NsdManager nsd=discovery(port);DacpController c=controller(nsd);
   check(c.getReady()&&c.getCanControl());
   nsd.current().onServiceLost(nsd.info);Handler.drain();
   check(c.getReady()&&c.getCanControl());
   ListenableFuture<?> pause=c.pause("bluetooth-test");until(pause::isDone);pause.get();
   c.release();Handler.drain();

   // Real HTTP failure -> endpoint recovery -> same pause delivered, one future.
   nsd=discovery(port);c=controller(nsd);failures.set(1);
   int before=received.size();pause=c.pause("bluetooth-test");
   final DacpController retry=c;until(()->!retry.getReady());
   check(c.getCanControl()&&!pause.isDone());
   Handler.advance(1000);until(pause::isDone);pause.get();
   check(c.getReady()&&received.size()==before+2);
   c.release();Handler.drain();

   // Latest intent wins while discovery is unavailable. No late pause after play.
   nsd=discovery(port);nsd.announce=false;c=controller(nsd);
   pause=c.pause("bluetooth-test");ListenableFuture<?> play=c.play("bluetooth-test");
   check(pause.isDone()&&!play.isDone());
   before=received.size();nsd.announce=true;Handler.advance(1000);
   until(play::isDone);play.get();check(received.size()==before+1);
   check(new ArrayList<>(received).get(received.size()-1).endsWith("/play"));
   c.release();Handler.drain();

   // More than the former retry cap still recovers; stale callbacks cannot undo it.
   nsd=discovery(port);nsd.failStarts=7;c=controller(nsd);
   for(int i=0;i<9;i++)Handler.advance(8000);
   check(c.getCanControl()&&c.getReady());
   nsd.listeners.get(0).onStartDiscoveryFailed("_dacp._tcp",3);Handler.drain();
   check(c.getReady());
   pause=c.pause("bluetooth-test");until(pause::isDone);pause.get();
   c.release();Handler.drain();

   // Unresolved commands expire with a terminal result and never replay later.
   nsd=discovery(port);nsd.announce=false;c=controller(nsd);
   before=received.size();pause=c.pause("bluetooth-test");
   Handler.advance(10000);check(pause.isDone());
   try{pause.get();throw new AssertionError();}catch(ExecutionException expected){}
   nsd.announce=true;Handler.advance(8000);Thread.sleep(20);Handler.drain();
   check(received.size()==before);
   c.release();Handler.drain();

   // Ambiguous non-idempotent failures are not replayed during discovery recovery.
   nsd=discovery(port);c=controller(nsd);failures.set(1);before=received.size();
   ListenableFuture<?> next=c.nextItem();until(next::isDone);
   try{next.get();throw new AssertionError();}catch(ExecutionException expected){}
   Handler.advance(1000);Thread.sleep(20);Handler.drain();
   check(received.size()==before+1);
   c.release();Handler.drain();

   // The real system resolve survives the 4s local watchdog. Do not submit
   // duplicates, and accept a late valid result instead of throwing it away.
   nsd=discovery(port);nsd.holdResolve=true;c=controller(nsd);
   Handler.advance(5000);Handler.advance(8000);
   check(nsd.resolves==1&&nsd.alreadyActive==0&&!c.getReady());
   nsd.completeResolve(nsd.info);Handler.drain();check(c.getReady());
   pause=c.pause("late-resolve");until(pause::isDone);pause.get();
   c.release();Handler.drain();

   // An old system callback releases the resolve slot without installing its
   // endpoint in a newer session. Its queued replacement can then resolve.
   nsd=discovery(port);nsd.holdResolve=true;c=controller(nsd);
   NsdServiceInfo oldInfo=nsd.info;
   nsd.info=new NsdServiceInfo("iTunes_Ctrl_replacement",InetAddress.getByName("127.0.0.1"),port);
   c.update("replacement","replacement-token");Handler.drain();
   check(nsd.resolves==1&&!c.getReady());
   nsd.holdResolve=false;nsd.completeResolve(oldInfo);Handler.drain();
   check(c.getReady()&&nsd.resolves==2&&nsd.alreadyActive==0);
   pause=c.pause("new-session");until(pause::isDone);pause.get();
   check("replacement-token".equals(new ArrayList<>(credentials).get(credentials.size()-1)));
   c.release();Handler.drain();

   // Cache a non-default port, reconstruct the controller with no multicast,
   // and prove a new live token, rather than cached credentials, reaches HTTP.
   nsd=discovery(port);Context ctx=new Context(nsd);
   c=new DacpController(ctx,()->Unit.INSTANCE);controllers.add(c);
   c.update("sender","old-token","127.0.0.1");Handler.drain();check(c.getReady());
   c.release();Handler.drain();nsd.announce=false;
   c=new DacpController(ctx,()->Unit.INSTANCE);controllers.add(c);
   c.update("sender","current-token","127.0.0.1");Handler.drain();check(c.getReady());
   pause=c.pause("cached-without-mdns");until(pause::isDone);pause.get();
   check("current-token".equals(new ArrayList<>(credentials).get(credentials.size()-1)));
   check(nsd.resolves==1);
   c.release();Handler.drain();

   // No cache -> 39831, and a stale cached port -> 39831, both work without
   // discovery. A real command is the only HTTP request used to confirm a hint.
   AtomicInteger fallbackRequests=new AtomicInteger(), fallbackFailures=new AtomicInteger();
   fallback.createContext("/ctrl-int/1/",exchange->{
    check("fallback-token".equals(exchange.getRequestHeaders().getFirst("Active-Remote")));
    fallbackRequests.incrementAndGet();
    int code=fallbackFailures.getAndUpdate(n->Math.max(0,n-1))>0?503:204;
    exchange.sendResponseHeaders(code,-1);exchange.close();
   });fallback.start();
   try {
    for(boolean stale:new boolean[]{false,true}) {
     nsd=discovery(port);nsd.announce=false;ctx=new Context(nsd);
     if(stale){
      int deadPort;
      try(ServerSocket dead=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){deadPort=dead.getLocalPort();}
      ctx.getSharedPreferences("dacp_endpoints",0).edit().putString("sender@127.0.0.1",deadPort+","+System.currentTimeMillis()).apply();
     }
     c=new DacpController(ctx,()->Unit.INSTANCE);controllers.add(c);
     c.update("sender","fallback-token","127.0.0.1");Handler.drain();
     int count=fallbackRequests.get();check(c.getReady());
     pause=c.pause("fallback-without-mdns");until(pause::isDone);pause.get();
     check(fallbackRequests.get()==count+1);
     check(ctx.getSharedPreferences("dacp_endpoints",0).getString("sender@127.0.0.1","").startsWith("39831,"));
     c.release();Handler.drain();
    }
    // A temporary fallback failure must not blacklist the only endpoint for the
    // entire session. Retry after a bounded cooldown without multicast recovery.
    nsd=discovery(port);nsd.announce=false;ctx=new Context(nsd);
    c=new DacpController(ctx,()->Unit.INSTANCE);controllers.add(c);
    c.update("sender","fallback-token","127.0.0.1");Handler.drain();
    fallbackFailures.set(1);int count=fallbackRequests.get();
    pause=c.pause("fallback-transient-failure");final DacpController transientController=c;
    until(()->!transientController.getReady());check(!pause.isDone());
    Handler.advance(3000);until(pause::isDone);pause.get();
    check(fallbackRequests.get()==count+2);
    c.release();Handler.drain();
   }finally{fallback.stop(0);}

   // Network locks are idempotent, released on stop/failure, and independently
   // acquired: a denied Wi-Fi lock must not drop an already-held multicast lock.
   nsd=discovery(port);ctx=new Context(nsd);WifiManager wifi=new WifiManager();ctx.wifiService=wifi;
   NsdServiceManager network=new NsdServiceManager(ctx);
   network.acquireNetworkLocks();network.acquireNetworkLocks();
   check(wifi.multicastCreated==1&&wifi.wifiCreated==1&&wifi.multicast.isHeld()&&wifi.wifi.isHeld());
   network.release();network.release();check(wifi.multicast.releases==1&&wifi.wifi.releases==1);
   wifi.failWifi=true;network.acquireNetworkLocks();check(wifi.multicast.isHeld()&&!wifi.wifi.isHeld());
   network.release();check(!wifi.multicast.isHeld());
   System.out.println("DACP Kotlin/NSD + HTTP recovery, endpoint cache and network lock integration tests passed");
  }finally{
   for(DacpController c:controllers)c.release();Handler.drain();server.stop(0);fallback.stop(0);
  }
 }
}
