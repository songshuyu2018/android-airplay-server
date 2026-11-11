import android.content.Context;
import android.net.nsd.*;
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
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/ctrl-int/1/",exchange->{
   received.add(exchange.getRequestURI().getPath());
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
   System.out.println("DACP Kotlin/NSD + HTTP recovery integration tests passed");
  }finally{
   for(DacpController c:controllers)c.release();Handler.drain();server.stop(0);
  }
 }
}
