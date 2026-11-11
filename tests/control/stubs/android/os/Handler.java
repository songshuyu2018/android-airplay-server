package android.os;
import java.util.*;
public final class Handler {
 private record Task(Runnable run,long when,long order){}
 private static long order;
 private static final PriorityQueue<Task> tasks=new PriorityQueue<>(Comparator.comparingLong(Task::when).thenComparingLong(Task::order));
 public Handler(Looper looper){}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){synchronized(tasks){tasks.add(new Task(r,SystemClock.now+delay,order++));}return true;}
 public void removeCallbacks(Runnable r){synchronized(tasks){tasks.removeIf(t->t.run==r);}}
 public static void drain(){for(int i=0;i<10000;i++){Task t;synchronized(tasks){if(tasks.isEmpty()||tasks.peek().when>SystemClock.now)return;t=tasks.remove();}t.run.run();}throw new AssertionError("looper spin");}
 public static void advance(long ms){SystemClock.now+=ms;drain();}
}
