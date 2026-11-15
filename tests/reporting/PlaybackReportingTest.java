import io.github.jqssun.airplay.reporting.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class PlaybackReportingTest {
 static void check(boolean value){if(!value)throw new AssertionError();}
 static CurlRequest request(String path){return CurlRequest.parse("curl -X POST http://127.0.0.1/"+path+" -H 'Authorization: Bearer TEST_SECRET' -d '{\"entity_id\":\"input_boolean.test\"}'");}
 static void rejects(String value){try{CurlRequest.parse(value);throw new AssertionError("accepted invalid command");}catch(IllegalArgumentException expected){check(!expected.getMessage().contains("TEST_SECRET"));}}
 static final class Fake implements PlaybackStateReporter.Scheduler {
  static final class Job implements PlaybackStateReporter.Ticket {long due,order;Runnable run;boolean cancelled;public void cancel(){cancelled=true;}}
  long now,order;boolean stopped;
  PriorityQueue<Job> tasks=new PriorityQueue<>(Comparator.<Job>comparingLong(j->j.due).thenComparingLong(j->j.order));
  public PlaybackStateReporter.Ticket schedule(Runnable task,long delay){if(stopped)throw new AssertionError("scheduled after close");Job j=new Job();j.due=now+delay;j.order=order++;j.run=task;tasks.add(j);return j;}
  public void shutdown(){stopped=true;}
  void drain(){int count=0;while(!tasks.isEmpty()&&tasks.peek().due<=now){Job j=tasks.remove();if(!j.cancelled)j.run.run();if(++count>1000)throw new AssertionError("scheduler loop");}}
  void advance(long ms){now+=ms;drain();}
 }
 static final class Harness {
  Fake clock=new Fake();List<String> paths=new ArrayList<>(),logs=new ArrayList<>();int failures;
  Consumer<CurlRequest> during;
  PlaybackStateReporter reporter=new PlaybackStateReporter(clock,r->{paths.add(URI.create(r.url).getPath());if(during!=null)during.accept(r);return failures-->0?503:204;},()->clock.now,logs::add);
  void enable(){reporter.configure(request("on"),request("off"));}
 }
 public static void main(String[] args)throws Exception {
  String body="{\"entity_id\": \"input_boolean.bathroom_music_running\"}";
  CurlRequest on=CurlRequest.parse("curl -X POST http\\://192.168.3.49/api/services/input_boolean/turn_on -H \"Authorization: Bearer TEST_SECRET\" -H \"Content-Type: application/json\" -d '"+body+"'");
  check(on.method.equals("POST")&&on.headers.get("authorization").equals("Bearer TEST_SECRET")&&on.body.equals(body));
  CurlRequest off=CurlRequest.parse("curl -X POST http://192.168.3.49/api/services/input_boolean/turn_off \\\n -H 'Authorization: Bearer TEST_SECRET' \\\n -d '"+body+"'");
  check(off.headers.get("content-type").equals("application/json"));
  check(CurlRequest.parse("curl --url=https://example.test/a?secret=TEST_SECRET --request=PUT --header='X-Test: a b' --data-raw='hello world'").summary().equals("PUT https://example.test/a"));
  check(CurlRequest.parse("curl -XPOST https://example.test -H'X-Test: yes' -d'{}'").body.equals("{}"));
  check(CurlRequest.parse("curl https://example.test \\\r\n --json '{}' ").headers.get("content-type").equals("application/json"));
  check(CurlRequest.parse("curl https://example.test -d \"{\\\"text\\\":\\\"中文\\\"}\"").body.equals("{\"text\":\"中文\"}"));
  for(String bad:List.of("", "curl file:///tmp/a", "curl http://u:TEST_SECRET@example.test", "curl http://example.test -d @/tmp/secret", "curl http://example.test; curl http://other.test", "curl http://example.test -H 'Authorization: TEST_SECRET", "curl http://example.test --output /tmp/file", "curl http://example.test http://other.test", "curl http://example.test -H 'X-Test: a\nb'", "curl http://example.test -X GET -d '{}'"))rejects(bad);

  Harness h=new Harness();h.reporter.setPlaying(true);h.clock.drain();check(h.paths.isEmpty());
  h.enable();h.clock.drain();check(h.paths.equals(List.of("/on")));
  h.clock.advance(39999);check(h.paths.size()==1);h.clock.advance(1);check(h.paths.size()==2);
  h.clock.advance(40000);check(h.paths.size()==3);
  h.reporter.setPlaying(false);h.clock.drain();check(h.paths.equals(List.of("/on","/on","/on","/off")));
  h.clock.advance(120000);check(h.paths.size()==4);h.reporter.close();h.clock.drain();check(h.paths.size()==4);

  // Rapid transitions discard queued stale states, rather than sending off after resume.
  h=new Harness();h.enable();h.reporter.setPlaying(true);h.reporter.setPlaying(false);h.reporter.setPlaying(true);h.clock.drain();
  check(h.paths.equals(List.of("/on")));h.reporter.configure(null,null);h.clock.advance(40000);check(h.paths.size()==1);h.reporter.close();

  // No overlapping HTTP; a pause during an in-flight on must be sent last.
  h=new Harness();Harness inflight=h;h.during=r->{if(r.url.endsWith("/on"))inflight.reporter.setPlaying(false);};
  h.reporter.setPlaying(true);h.enable();h.clock.drain();check(h.paths.equals(List.of("/on","/off")));h.reporter.close();

  // Paused callback retries are bounded and cancelled immediately on resume.
  h=new Harness();h.failures=10;h.enable();h.clock.drain();h.clock.advance(2000);h.clock.advance(5000);h.clock.advance(10000);
  check(h.paths.equals(List.of("/off","/off","/off")));h.reporter.close();h.clock.drain();
  h=new Harness();h.failures=1;h.enable();h.clock.drain();h.reporter.setPlaying(true);h.clock.drain();h.clock.advance(7000);
  check(h.paths.equals(List.of("/off","/on")));h.reporter.close();h.clock.drain();check(h.paths.get(2).equals("/off"));

  // Reconfiguration cancels old pending payloads; equal configuration does not restart cadence.
  h=new Harness();h.reporter.setPlaying(true);h.enable();h.reporter.configure(request("new-on"),request("new-off"));h.clock.drain();
  check(h.paths.equals(List.of("/new-on")));h.reporter.configure(request("new-on"),request("new-off"));h.clock.drain();check(h.paths.size()==1);
  h.clock.advance(400000);check(h.paths.size()==2);h.reporter.close();h.clock.drain();check(h.paths.get(2).equals("/new-off"));

  // Exception messages may contain credentials: diagnostics must not copy them.
  Fake clock=new Fake();List<String> logs=new ArrayList<>();
  PlaybackStateReporter broken=new PlaybackStateReporter(clock,r->{throw new Exception("TEST_SECRET");},()->clock.now,logs::add);
  broken.configure(on,off);clock.drain();check(!String.join("",logs).contains("TEST_SECRET"));broken.configure(null,null);broken.close();

  // Exercise the real HTTP transport and real executor, including the final off on close.
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  List<String> wire=Collections.synchronizedList(new ArrayList<>()),diagnostics=Collections.synchronizedList(new ArrayList<>());
  CountDownLatch arrivedOn=new CountDownLatch(1),arrivedOff=new CountDownLatch(1);
  server.createContext("/",exchange->{
   String path=exchange.getRequestURI().getPath();
   check(exchange.getRequestMethod().equals("POST"));check(exchange.getRequestHeaders().getFirst("Authorization").equals("Bearer TEST_SECRET"));
   check(exchange.getRequestHeaders().getFirst("Content-Type").equals("application/json"));
   check(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).equals(body));wire.add(path);
   exchange.sendResponseHeaders(204,-1);exchange.close();if(path.equals("/on"))arrivedOn.countDown();else arrivedOff.countDown();
  });server.start();
  PlaybackStateReporter real=new PlaybackStateReporter(diagnostics::add);
  try {
   String url="http://127.0.0.1:"+server.getAddress().getPort();
   real.setPlaying(true);
   real.configure(CurlRequest.parse("curl "+url+"/on -H 'Authorization: Bearer TEST_SECRET' -d '"+body+"'"),CurlRequest.parse("curl "+url+"/off -H 'Authorization: Bearer TEST_SECRET' -d '"+body+"'"));
   check(arrivedOn.await(5,TimeUnit.SECONDS));real.close();check(arrivedOff.await(5,TimeUnit.SECONDS));
   check(wire.equals(List.of("/on","/off")));check(!String.join("",diagnostics).contains("TEST_SECRET"));
  } finally {real.close();server.stop(0);}
  System.out.println("PASS: curl import, 40s cadence, pause/resume ordering, retry cancellation, stop, credential-safe logs and real HTTP");
 }
}
